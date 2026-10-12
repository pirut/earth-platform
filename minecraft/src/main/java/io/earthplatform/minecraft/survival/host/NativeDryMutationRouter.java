package io.earthplatform.minecraft.survival.host;

import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.interaction.host.PinnedNativeEventAdmission;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.Objects;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.ticks.TickPriority;
import net.neoforged.neoforge.common.extensions.IBlockExtension;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Native shape/leaf scheduling for the exact dry palette. This is not arbitrary Level mutation.
 * Registered inherited callbacks are admitted before writes; genuine native shape callbacks run
 * on read-your-writes data. No carrier heightmap/light/tick container or precommit packet changes.
 * Full-coordinate BlockNotification is publication metadata, not a promise of propagated light.
 */
public final class NativeDryMutationRouter implements SurvivalLevelBinding.MutationRouter {
    private final ServerLevel level;
    private final NativeWholeAction.Owner owner;
    private final PinnedNativeEventAdmission events;
    private final LongSupplier nextTickOrder;
    private io.earthplatform.minecraft.survival.world.NativeSurvivalWorld.ActionIdentity active;
    private long nextOrder;
    private int operations;
    private final int maximumOperations;

    /** Exact native scheduled-order source, not a caller callback admitted by class shape. */
    private static final class OwnedTickOrder implements LongSupplier {
        private final io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost host;
        private final NativeWholeAction.Owner owner;
        private OwnedTickOrder(io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost host) {
            this.host = Objects.requireNonNull(host); this.owner = host.actions();
        }
        @Override public long getAsLong() {
            if (host.actions() != owner) throw new SurvivalRefusal("Native dry router scheduled-order owner changed");
            return host.nextAvailableScheduledOrder();
        }
    }
    /** First registered block proofs must use this provenance-checked factory, never the callback-taking constructor. */
    public static NativeDryMutationRouter forOwnedHost(
            io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost host, int maximumOperations) {
        Objects.requireNonNull(host);
        return new NativeDryMutationRouter(host.level(),host.actions(),
                new PinnedNativeEventAdmission(net.neoforged.neoforge.common.NeoForge.EVENT_BUS),
                new OwnedTickOrder(host),maximumOperations);
    }
    public void requireOwnedBlockProofHost(io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost host) {
        owner.world().checkOwner();
        if (host == null || host.actions() != owner || host.level() != level || !(nextTickOrder instanceof OwnedTickOrder exact)
                || exact.host != host || exact.owner != owner)
            throw new SurvivalRefusal("Native block proof router lacks its original host-owned scheduled-order capability");
    }

    public NativeDryMutationRouter(ServerLevel level, NativeWholeAction.Owner owner,
            PinnedNativeEventAdmission events, LongSupplier nextTickOrder, int maximumOperations) {
        this.level = Objects.requireNonNull(level); this.owner = Objects.requireNonNull(owner);
        this.events = Objects.requireNonNull(events); this.nextTickOrder = Objects.requireNonNull(nextTickOrder);
        owner.world().checkOwner();
        if (!SurvivalLevelBinding.DIMENSION.equals(level.dimension()) || maximumOperations < 1 || maximumOperations > 8192)
            throw new IllegalArgumentException("Explicit bounded survival mutation domain required");
        this.maximumOperations = maximumOperations;
    }
    @Override public BlockState set(NativeWholeAction action, CubeCell cell, BlockState state, int flags, int recursionLeft) {
        try {
            requireAction(action);
            if (++operations > maximumOperations || recursionLeft < 0 || recursionLeft > 512 || (flags & ~127) != 0)
                throw new SurvivalRefusal("Native dry mutation operation/recursion/flag limit");
            owner.world().palette().id(state);
            BlockState before = action.read(cell);
            auditState(before); auditState(state);
            if (before == state) return null;
            if (PoiTypes.forState(before).isPresent() || PoiTypes.forState(state).isPresent())
                throw new SurvivalRefusal("Native POI mutations are outside the dry action profile");
            if (before.hasAnalogOutputSignal() || state.hasAnalogOutputSignal())
                throw new SurvivalRefusal("Native analog/redstone mutations are outside the dry action profile");
            if ((flags & 1) != 0) events.requireEmpty(BlockEvent.NeighborNotifyEvent.class);
            BlockPos pos = nativePos(cell);
            action.set(cell, state);
            before.onRemove(level, pos, state, (flags & 64) != 0);
            state.onPlace(level, pos, before, (flags & 64) != 0);
            if (action.read(cell) != state) throw new SurvivalRefusal("Admitted native placement changed its prepared state unexpectedly");
            if ((flags & 1) != 0) {
                // The pinned updateNeighborsAt posts once before its six native neighbor calls.
                EventHooks.onNeighborNotify(level, pos, state, EnumSet.allOf(Direction.class), false);
                events.requireEmpty(BlockEvent.NeighborNotifyEvent.class);
                for (Direction direction : NeighborUpdater.UPDATE_ORDER) {
                    // Exact admitted neighborChanged is debug-packet-only; do not send live debug
                    // packets during preparation. Prove every neighbor before proceeding.
                    auditState(action.read(neighbor(pos, direction)));
                }
            }
            if ((flags & 16) == 0 && recursionLeft > 0) {
                LevelAccessor view = view(action);
                int propagatedFlags = flags & -34;
                before.updateIndirectNeighbourShapes(view, pos, propagatedFlags, recursionLeft - 1);
                state.updateNeighbourShapes(view, pos, propagatedFlags, recursionLeft - 1);
                state.updateIndirectNeighbourShapes(view, pos, propagatedFlags, recursionLeft - 1);
            }
            // Both native POI identities were proved absent; no deferred live POI operation occurs.
            level.onBlockStateChange(pos, before, state);
            state.onBlockStateChange(level, pos, before);
            action.stage(new NativeEffectCodec.BlockNotification(owner.world().world(), cell,
                    owner.world().palette().id(before), owner.world().palette().id(state), flags, recursionLeft));
            return before;
        } catch (RuntimeException | Error failure) { poison(action, failure); throw failure; }
    }
    private void requireAction(NativeWholeAction action) {
        owner.world().checkOwner();
        if (!owner.owns(action) || NativeWholeAction.current() != action || action.state() != NativeWholeAction.State.PREPARING)
            throw new SurvivalRefusal("Dry mutation requires the exact preparing whole action");
        if (!action.identity().equals(active)) {
            long initialOrder = nextTickOrder.getAsLong();
            if (initialOrder < 0 || initialOrder == Long.MAX_VALUE)
                throw new SurvivalRefusal("Native scheduled order unavailable");
            // Publish the new action-local cursor only after all initialization can succeed.
            active = action.identity(); operations = 0; nextOrder = initialOrder;
        }
    }
    private LevelAccessor view(NativeWholeAction action) {
        InvocationHandler handler = (proxy, method, args) -> {
            try {
                requireAction(action);
                if (++operations > maximumOperations) throw new SurvivalRefusal("Native callback operation budget exhausted");
                return switch (method.getName()) {
                    case "getBlockState" -> action.read(cell((BlockPos)args[0]));
                    case "getFluidState" -> action.read(cell((BlockPos)args[0])).getFluidState();
                    case "getBlockEntity" -> { auditState(action.read(cell((BlockPos)args[0]))); yield null; }
                    case "isClientSide" -> false;
                    case "neighborShapeChanged" -> {
                        NeighborUpdater.executeShapeUpdate((LevelAccessor)proxy, (Direction)args[0], (BlockState)args[1],
                                (BlockPos)args[2], (BlockPos)args[3], (int)args[4], (int)args[5]);
                        yield null;
                    }
                    case "setBlock" -> set(action, cell((BlockPos)args[0]), (BlockState)args[1], (int)args[2], args.length==4?(int)args[3]:512) != null;
                    case "scheduleTick" -> {
                        if (!(args[1] instanceof Block block) || block != Blocks.OAK_LEAVES)
                            throw new SurvivalRefusal("Only admitted dry native leaf ticks may be prepared");
                        int delay = (int)args[2]; if (delay < 1 || delay > 1200) throw new SurvivalRefusal("Native leaf tick delay outside profile");
                        CubeCell target = cell((BlockPos)args[0]); auditState(action.read(target));
                        TickPriority priority = args.length==4?(TickPriority)args[3]:TickPriority.NORMAL;
                        long order = nextOrder; nextOrder = Math.incrementExact(nextOrder);
                        action.stage(new NativeEffectCodec.ScheduledBlockTick(target, BuiltInRegistries.BLOCK.getKey(block),
                                Math.addExact(level.getGameTime(), delay), priority.getValue(), order));
                        yield null;
                    }
                    case "toString" -> "NativeDryMutationReadYourWrites";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new SurvivalRefusal("Unadmitted native dry callback operation: " + method);
                };
            } catch (RuntimeException | Error failure) { poison(action, failure); throw failure; }
        };
        return (LevelAccessor)Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(), new Class<?>[]{LevelAccessor.class}, handler);
    }
    /** Reflection inspects actual override ownership, never invokes inaccessible native callbacks. */
    static void auditState(BlockState state) {
        if (state.hasBlockEntity() || !state.getFluidState().isEmpty()) throw new SurvivalRefusal("Dry callback palette required");
        auditCallbackType(state.getBlock().getClass());
    }
    static void auditCallbackType(Class<?> type) {
        requireDeclared(type, "onPlace", BlockBehaviour.class, BlockState.class, Level.class, BlockPos.class, BlockState.class, boolean.class);
        requireDeclared(type, "onRemove", BlockBehaviour.class, BlockState.class, Level.class, BlockPos.class, BlockState.class, boolean.class);
        requireDeclared(type, "neighborChanged", BlockBehaviour.class, BlockState.class, Level.class, BlockPos.class, Block.class, BlockPos.class, boolean.class);
        requireDeclared(type, "updateIndirectNeighbourShapes", BlockBehaviour.class, BlockState.class, LevelAccessor.class, BlockPos.class, int.class, int.class);
        try {
            if (type.getMethod("onBlockStateChange", LevelReader.class, BlockPos.class, BlockState.class, BlockState.class)
                    .getDeclaringClass() != IBlockExtension.class) throw new SurvivalRefusal("Custom native state-change callback");
        } catch (ReflectiveOperationException error) { throw new SurvivalRefusal("Native state-change callback inspection unavailable"); }
    }
    private static void requireDeclared(Class<?> type, String name, Class<?> expected, Class<?>... parameters) {
        for (Class<?> current=type; current!=null; current=current.getSuperclass()) {
            try {
                Method method=current.getDeclaredMethod(name, parameters);
                if (method.getDeclaringClass()!=expected) throw new SurvivalRefusal("Unadmitted native callback override: "+method);
                return;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new SurvivalRefusal("Native callback signature unavailable: "+name);
    }
    static CubeCell neighbor(BlockPos pos, Direction direction) {
        return CubeCell.of(Math.addExact(pos.getX(), direction.getStepX()),
                Math.addExact(pos.getY(), direction.getStepY()), Math.addExact(pos.getZ(), direction.getStepZ()));
    }
    private static CubeCell cell(BlockPos pos) { return CubeCell.of(pos.getX(),pos.getY(),pos.getZ()); }
    private static BlockPos nativePos(CubeCell cell) { return new BlockPos(Math.toIntExact(cell.x()),Math.toIntExact(cell.y()),Math.toIntExact(cell.z())); }
    private static void poison(NativeWholeAction action, Throwable failure) {
        if (action != null && action.state()==NativeWholeAction.State.PREPARING) try { action.poisonPreparation(); }
        catch (RuntimeException | Error secondary) { failure.addSuppressed(secondary); }
    }
}
