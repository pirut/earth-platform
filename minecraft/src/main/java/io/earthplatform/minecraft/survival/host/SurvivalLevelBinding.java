package io.earthplatform.minecraft.survival.host;

import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.interaction.host.NativeItemTickReadScope;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One server Level, one cubic world, one whole-action owner. Carrier sections never become terrain.
 * Binding this object does not supply native callback preparation, transport installation, effect
 * delivery or persistence. The host must explicitly supply and prove those facilities.
 */
public final class SurvivalLevelBinding {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("earthplatform", "survival_carrier"));
    @FunctionalInterface public interface MutationRouter {
        /**
         * Validate flags, recursion and callback support BEFORE changing state. Execute admitted
         * callbacks exactly once in this preparation scope and stage every native effect. Return
         * the previous state (null for no change). No live notifications, inventories or drops.
         * Flags 0 and 16 do not exempt vanilla onStateChange callbacks from this requirement.
         */
        BlockState set(NativeWholeAction action, CubeCell cell, BlockState state, int flags, int recursionLeft);
        /** Raw chunk callbacks differ from Level.setBlock; do not invent Level notification flags. */
        default BlockState setChunk(NativeWholeAction action, CubeCell cell, BlockState state, boolean moving) {
            throw new SurvivalRefusal("Raw LevelChunk mutation callbacks have not been admitted");
        }
    }
    @FunctionalInterface public interface CarrierTickPolicy {
        /** Own admitted cubic terrain/weather work; never delegate to the carrier's sections. */
        void tick(SurvivalLevelBinding binding, net.minecraft.world.level.chunk.LevelChunk carrier, int randomTickSpeed);
    }
    private final CarrierTickPolicy carrierTicks;
    private final ServerLevel level;
    private final NativeSurvivalWorld world;
    private final SurvivalBlockView view;
    private final NativeWholeAction.Owner actions;
    private final MutationRouter mutations;
    private boolean attached, closing, detached;
    private SurvivalCarrierSerializationRoster serializationRoster;
    private final java.util.Set<net.minecraft.world.level.chunk.LevelChunk> carriers =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public SurvivalLevelBinding(ServerLevel level, NativeSurvivalWorld world,
            NativeWholeAction.Owner actions, MutationRouter mutations) {
        this(level, world, actions, mutations, (binding, carrier, speed) -> {
            throw new SurvivalRefusal("Native carrier terrain/weather ticking has not been admitted");
        });
    }
    public SurvivalLevelBinding(ServerLevel level, NativeSurvivalWorld world,
            NativeWholeAction.Owner actions, MutationRouter mutations, CarrierTickPolicy carrierTicks) {
        this.carrierTicks = Objects.requireNonNull(carrierTicks);
        this.level = Objects.requireNonNull(level); this.world = Objects.requireNonNull(world);
        this.view = new SurvivalBlockView(world, new SurvivalBlockView.Access() {
            public BlockState state(BlockPos position) { return SurvivalLevelBinding.this.get(position); }
            public boolean resident(BlockPos position) { return SurvivalLevelBinding.this.isResident(position); }
            public boolean box(int x1,int y1,int z1,int x2,int y2,int z2) {
                return SurvivalLevelBinding.this.hasResidentBox(x1,y1,z1,x2,y2,z2);
            }
            public void geometry() { checkReadable(); }
            public void owner() { checkOpen(); }
        });
        this.actions = Objects.requireNonNull(actions); this.mutations = Objects.requireNonNull(mutations);
        if (actions.world() != world) throw new SurvivalRefusal("Foreign whole-action owner");
        if (!DIMENSION.equals(level.dimension())) throw new SurvivalRefusal("Production survival binding requires its explicit carrier dimension");
    }
    public static SurvivalLevelBinding find(Level level) {
        return level instanceof SurvivalLevelAccess access ? access.earthplatform$survivalBinding() : null;
    }
    public static void rejectTerrain(Level level, String operation) {
        var binding = find(level);
        if (binding != null) binding.reject(operation);
    }
    public void attachTo(Level exactLevel) {
        world.checkOwner();
        if (exactLevel != level || attached || closing || detached)
            throw new SurvivalRefusal("Survival binding identity/lifecycle mismatch");
        attached = true;
    }
    public ServerLevel level() { checkOpen(); return level; }
    public SurvivalBlockView view() { checkReadable(); return view; }
    public NativeSurvivalWorld world() { checkOpen(); return world; }
    public NativeWholeAction.Owner actions() { checkOpen(); return actions; }
    public BlockState get(BlockPos position) {
        Objects.requireNonNull(position);
        if(io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.permits(this))return io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.read(this,position);
        if (NativeItemTickReadScope.permits(this)) return NativeItemTickReadScope.read(this, position);
        checkOpen(); checkReadOwner();
        return world.read(CubeCell.of(position.getX(), position.getY(), position.getZ()));
    }
    public BlockState set(BlockPos position, BlockState state, int flags, int recursionLeft) {
        checkOpen();
        NativeWholeAction action = currentAction();
        if (recursionLeft < 0 || recursionLeft > 512 || (flags & ~127) != 0)
            throw new SurvivalRefusal("Unsupported native mutation flags/recursion");
        return mutations.set(action, CubeCell.of(position.getX(), position.getY(), position.getZ()),
                Objects.requireNonNull(state), flags, recursionLeft);
    }
    public BlockState setChunk(BlockPos position, BlockState state, boolean moving) {
        checkOpen();
        return mutations.setChunk(currentAction(), CubeCell.of(position.getX(), position.getY(), position.getZ()),
                Objects.requireNonNull(state), moving);
    }
    public boolean isResident(BlockPos position) {
        Objects.requireNonNull(position);
        if(io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.permits(this))return io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.hasResidentBox(this,
                position.getX(),position.getY(),position.getZ(),position.getX(),position.getY(),position.getZ());
        if (NativeItemTickReadScope.permits(this)) return NativeItemTickReadScope.hasResidentBox(this,
                position.getX(),position.getY(),position.getZ(),position.getX(),position.getY(),position.getZ());
        checkOpen(); return view.residentUnchecked(position);
    }
    public boolean hasResidentBox(int x1, int y1, int z1, int x2, int y2, int z2) {
        if(io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.permits(this))return io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.hasResidentBox(this,x1,y1,z1,x2,y2,z2);
        if (NativeItemTickReadScope.permits(this)) return NativeItemTickReadScope.hasResidentBox(this,x1,y1,z1,x2,y2,z2);
        checkOpen(); return view.residentBoxUnchecked(x1,y1,z1,x2,y2,z2);
    }
    public boolean hasResidentColumns(int x1, int z1, int x2, int z2) {
        checkOpen();
        return view.hasResidentBox(x1, view.getMinBuildHeight(), z1,
                x2, Math.addExact(view.getMinBuildHeight(), view.getHeight() - 1), z2);
    }
    public void reject(String operation) {
        io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.terrainRefusal(this,operation);
        checkOpen(); throw new SurvivalRefusal("Unsupported native terrain consumer: " + operation);
    }
    /** Explicit, bounded transport capability; registration never admits terrain. */
    public void admitCarrier(net.minecraft.world.level.chunk.LevelChunk carrier) {
        checkOpen();
        if (carrier.getLevel() != level) throw new SurvivalRefusal("Foreign carrier Level");
        if (carriers.contains(carrier)) throw new SurvivalRefusal("Carrier identity is already admitted");
        if (carriers.size() >= world.limits().ownedCubes()) throw new SurvivalRefusal("Carrier transport budget exhausted");
        SurvivalCarrierTransport.verifyEmpty(carrier);
        carriers.add(carrier);
    }
    public void releaseCarrier(net.minecraft.world.level.chunk.LevelChunk carrier) {
        world.checkOwner();
        if (!carriers.remove(carrier)) throw new SurvivalRefusal("Unknown carrier transport identity");
    }
    /** Only previously admitted empty transport identities remain usable during cleanup. */
    public void checkCarrierTransportState() {
        world.checkOwner();
        if (!attached) throw new SurvivalRefusal("Carrier Level was never bound");
    }
    public void requireCarrier(net.minecraft.world.level.chunk.LevelChunk carrier) {
        checkCarrierTransportState();
        if (carrier.getLevel() != level || !carriers.contains(carrier))
            throw new SurvivalRefusal("Carrier transport identity has not been admitted");
    }
    /** Explicit physical native metadata roster; it never spends/increases cubic gameplay ownership. */
    public void admitCarrierSerialization(int centerX, int centerZ,
            SurvivalCarrierSerializationRoster.PhysicalAdmission admission) {
        checkOpen();
        if (serializationRoster != null) throw new SurvivalRefusal("Carrier serialization roster is already sealed");
        serializationRoster = SurvivalCarrierSerializationRoster.admit(this, level, centerX, centerZ,
                Objects.requireNonNull(admission));
    }
    public SurvivalCarrierSerializationRoster.Usage carrierSerializationUsage() {
        checkCarrierTransportState();
        if (serializationRoster == null) throw new SurvivalRefusal("No carrier serialization roster admitted");
        return serializationRoster.usage();
    }
    void requireCarrierSerialization(ServerLevel exactLevel, net.minecraft.world.level.chunk.ChunkAccess chunk) {
        checkCarrierTransportState();
        if (serializationRoster == null) throw new SurvivalRefusal("No exact native save lineage was pre-admitted");
        serializationRoster.requireWrite(exactLevel, chunk);
    }
    public void tickCarrier(net.minecraft.world.level.chunk.LevelChunk carrier, int randomTickSpeed) {
        checkOpen(); requireCarrier(carrier); SurvivalCarrierTransport.verifyEmpty(carrier);
        if (randomTickSpeed < 0 || actions.hasCurrentAction() || world.hasActiveTransaction())
            throw new SurvivalRefusal("Carrier terrain tick requires an idle whole-action owner and nonnegative tick budget");
        carrierTicks.tick(this, carrier, randomTickSpeed);
        if (actions.hasCurrentAction() || world.hasActiveTransaction() || actions.fenced())
            throw new SurvivalRefusal("Carrier terrain tick left unfinished action/effect ownership");
    }
    public void beginClose() { world.checkOwner(); if (detached) throw new SurvivalRefusal("Binding already detached"); closing = true; }
    public boolean isClosing() { world.checkOwner(); return closing; }
    /** The Level keeps this identity as a tombstone; it must never revert to carrier terrain. */
    public void detachFrom(Level exactLevel) {
        var world = this.world; world.checkOwner();
        if (exactLevel != level || !attached || !closing || detached)
            throw new SurvivalRefusal("Survival detach identity/lifecycle mismatch");
        if (world.hasActiveTransaction() || actions.hasCurrentAction() || actions.fenced()
                || world.hasUncheckpointedActions() || !world.dirtyPositions().isEmpty()
                || world.hasPendingCheckpoint() || world.pinCount() != 0)
            throw new SurvivalRefusal("Survival action, dirty, checkpoint, pin or effect owner has not drained");
        detached = true;
    }
    public boolean isDetached() { world.checkOwner(); return detached; }
    public void checkOpen() {
        world.checkOwner();
        if (!attached || closing || detached || actions.fenced())
            throw new SurvivalRefusal("Survival binding is not open or has undelivered effects; authoritative queries are fenced");
    }
    private void checkReadable() {
        if (!io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.permits(this)&&!NativeItemTickReadScope.permits(this)) { checkOpen(); checkReadOwner(); }
    }
    private void checkReadOwner() {
        if (world.hasActiveTransaction()) currentAction();
    }
    private NativeWholeAction currentAction() {
        NativeWholeAction action = NativeWholeAction.current();
        if (action == null || !actions.owns(action) || action.world() != world
                || action.state() != NativeWholeAction.State.PREPARING)
            throw new SurvivalRefusal("Terrain mutations/transactional reads require the exact admitted whole-action owner");
        return action;
    }
}
