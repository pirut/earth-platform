package io.earthplatform.minecraft.survival.gametest;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.interaction.host.PinnedNativeEventAdmission;
import io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Disposable diagnostic only, disabled unless both explicit system flags are set.
 * This is not a production session/tick/transaction capability. It invokes the exact native
 * methods once, never the scheduler, with one bounded cubic fluid-residency callsite and an
 * explicitly versioned procedural biome/structure environment. Original advancement/reward
 * callbacks run only after actual listener/progress/recipe preflight; unsupported effects refuse.
 */
public final class NativePlayerTickProbeScope {
    static final String ENABLE = "earthplatform.nativePlayerTickDiagnostic";
    static final int MAX_CELLS = 4096, MAX_READS = 8192;
    private static final ThreadLocal<Prepared> ACTIVE = new ThreadLocal<>();
    private static long nextEpoch;
    private static final java.util.Set<ServerPlayer> FAILED = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private NativePlayerTickProbeScope() {}

    static boolean enabled() {
        return Boolean.getBoolean(ENABLE) && Boolean.getBoolean("neoforge.gameTestServer");
    }
    enum Invocation { TICK_THEN_DO_TICK, DO_TICK_ONLY }
    static Prepared prepare(ServerPlayer player, SurvivalLevelBinding binding) {
        return prepare(player,binding,Invocation.TICK_THEN_DO_TICK);
    }
    static Prepared prepare(ServerPlayer player, SurvivalLevelBinding binding,Invocation invocation) {
        java.util.Objects.requireNonNull(invocation);
        if (!enabled() || ACTIVE.get() != null) throw refused("Diagnostic is disabled or reentrant");
        requireProfile(player, binding);
        Bounds bounds = bounds(player.getBoundingBox());
        // Fail before entry, including when only the corresponding low-Y cube is resident.
        if (!binding.hasResidentBox(bounds.x1,bounds.y1,bounds.z1,bounds.x2,bounds.y2,bounds.z2))
            throw refused("Unknown/missing full-coordinate player footprint before native tick");
        Map<BlockPos,BlockState> cells = new LinkedHashMap<>();
        Map<CubePos,NativeSurvivalWorld.CubeStamp> stamps = new LinkedHashMap<>();
        for (BlockPos cursor : BlockPos.betweenClosed(bounds.x1,bounds.y1,bounds.z1,bounds.x2,bounds.y2,bounds.z2)) {
            BlockPos pos = cursor.immutable();
            BlockState state = binding.get(pos);
            requireDryBlock(state);
            cells.put(pos,state);
            CubePos cube = CubePos.fromBlock(pos.getX(),pos.getY(),pos.getZ());
            stamps.computeIfAbsent(cube,binding.world()::stamp);
        }
        BlockPos feet = player.blockPosition();
        if (!cells.get(feet.below()).is(Blocks.GRASS_BLOCK)
                || !cells.get(feet).isAir() || !cells.get(feet.above()).isAir())
            throw refused("Diagnostic needs genuine generated grass and clear standing body");
        requirePinned();
        requireCallbacks();
        if(FAILED.contains(player))throw refused("This diagnostic player has a retained native-entry failure; no retry is admitted");
        Prepared prepared=new Prepared(++nextEpoch,player,binding,bounds,Map.copyOf(cells),Map.copyOf(stamps),invocation);
        prepared.prepareMetadata();
        return prepared;
    }

    static final class Prepared {
        final long epoch;
        final Invocation invocation;
        final ServerPlayer player;
        final SurvivalLevelBinding binding;
        final NativeSurvivalWorld world;
        final Bounds bounds;
        final Map<BlockPos,BlockState> cells;
        final Map<CubePos,NativeSurvivalWorld.CubeStamp> stamps;
        final State admitted;
        final long commitSequence;
        final List<Read> reads = new ArrayList<>();
        String stage = "prepared";
        int fluidResidencyCalls;
        boolean used, live;
        Throwable firstRefusal;
        boolean preflighting;
        io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV1 environment;
        io.earthplatform.minecraft.survival.interaction.host.NativePlayerTickMetadata metadata;
        int preflightReads;
        Prepared(long epoch,ServerPlayer player,SurvivalLevelBinding binding,Bounds bounds,
                Map<BlockPos,BlockState> cells,Map<CubePos,NativeSurvivalWorld.CubeStamp> stamps,Invocation invocation) {
            this.invocation=invocation;
            this.epoch=epoch;this.player=player;this.binding=binding;this.world=binding.world();
            this.bounds=bounds;this.cells=cells;this.stamps=stamps;
            admitted=State.capture(player);commitSequence=world.commitSequence();
        }
        void prepareMetadata() {
            live=true;preflighting=true;ACTIVE.set(this);
            try {
                environment=new io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV1(binding,()->check(binding));
                metadata=io.earthplatform.minecraft.survival.interaction.host.NativePlayerTickMetadata.prepare(player,
                        invocation==Invocation.TICK_THEN_DO_TICK);
                failIfPoisoned();
                if(!admitted.equals(State.capture(player)))throw refused("Read-only metadata preflight changed player state");
                verifyStamps();
            } finally { ACTIVE.remove();live=false;preflighting=false; }
        }
        void runOnce() {
            if (used || ACTIVE.get()!=null) throw refused("Diagnostic capability is consumed or reentrant");
            used=true;
            requireProfile(player,binding);
            if (!admitted.equals(State.capture(player))) throw refused("Player changed after diagnostic admission");
            verifyStamps(); requireCallbacks();
            live=true;preflighting=true;ACTIVE.set(this);
            boolean nativeEntered=false;
            try {
                metadata.enter();
                if(!admitted.equals(State.capture(player)))throw refused("Final metadata preflight changed player state");
                verifyStamps();failIfPoisoned();preflighting=false;nativeEntered=true;
                if(invocation==Invocation.TICK_THEN_DO_TICK) {
                    stage="ServerPlayer.tick"; player.tick(); failIfPoisoned();
                }
                stage="ServerPlayer.doTick"; player.doTick(); failIfPoisoned();
                metadata.requireComplete();
                stage="post-native-owner-check"; check(binding); verifyStamps();
                if(world.hasUncheckpointedActions()||!world.dirtyPositions().isEmpty())
                    throw refused("Native diagnostic changed the cubic owner after its final read");
                failIfPoisoned();stage="native-returned";
            } catch(RuntimeException|Error failure) {
                poison(failure);
                if(nativeEntered) {
                    if(FAILED.size()>=64)throw new AssertionError("Diagnostic failed-player retention bound exhausted",failure);
                    FAILED.add(player);
                }
                throw failure;
            } finally {
                ACTIVE.remove();live=false;preflighting=false;
            }
        }
        void poison(Throwable failure) { if(firstRefusal==null)firstRefusal=failure; }
        void failIfPoisoned() {
            if(firstRefusal instanceof RuntimeException failure)throw failure;
            if(firstRefusal instanceof Error failure)throw failure;
            if(firstRefusal!=null)throw new AssertionError("Native diagnostic has a retained refusal",firstRefusal);
        }
        void check(SurvivalLevelBinding receiver) {
            try {
                failIfPoisoned();
                if (!live || ACTIVE.get()!=this || receiver!=binding || player.level()!=binding.level()
                        || binding.world()!=world || !player.server.isSameThread())
                    throw refused("Foreign/revoked diagnostic receiver, player, world or epoch "+epoch);
                binding.checkOpen();
                if (world.commitSequence()!=commitSequence || world.hasActiveTransaction() || io.earthplatform.minecraft.survival.interaction.NativeWholeAction.hasCurrentOrOpeningAction())
                    throw refused("Diagnostic world/action epoch changed during native tick");
            } catch(RuntimeException|Error failure) { poison(failure);throw failure; }
        }
        void verifyStamps() {
            for (var entry : stamps.entrySet()) {
                if (world.presence(entry.getKey())!=NativeSurvivalWorld.Presence.RESIDENT
                        || !world.stamp(entry.getKey()).equals(entry.getValue()))
                    throw refused("Diagnostic cubic residency/owner epoch changed before native tick");
            }
        }
        long readCount(String consumer) { return reads.stream().filter(read->read.consumer.equals(consumer)).count(); }
        String report(State before,State after,Throwable failure) {
            StringBuilder text = new StringBuilder("DIRECT_NATIVE_METHOD_DIAGNOSTIC\n")
                    .append("invocation=").append(invocation).append(" epoch=").append(epoch).append(" stage=").append(stage)
                    .append(" bounds=").append(bounds).append(" admittedCells=").append(cells.size())
                    .append(" fluidResidencyCalls=").append(fluidResidencyCalls).append('\n')
                    .append("before=").append(before).append('\n').append("after=").append(after).append('\n')
                    .append("worldCommitBefore=").append(commitSequence).append(" worldCommitAfter=").append(world.commitSequence()).append('\n')
                    .append("admittedCubeStamps=").append(stamps).append('\n')
                    .append("actualCubicReads=").append(reads.size()).append(" preflightCubicReads=").append(preflightReads).append('\n')
                    .append("environmentIdentity=").append(io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV1.IDENTITY).append('\n')
                    .append("environmentQuartReads=").append(environment==null?List.of():environment.quartReads()).append('\n')
                    .append("noGeneratedStructureQueries=").append(environment==null?0:environment.structureQueries()).append('\n')
                    .append("expectedNativeAwards=").append(metadata==null?List.of():metadata.expectedAwards()).append('\n')
                    .append("actualNativeAwards=").append(metadata==null?List.of():metadata.actualAwards()).append('\n');
            for (Read read:reads) text.append(read).append('\n');
            if(firstRefusal!=null) {
                var firstOutput=new java.io.StringWriter();firstRefusal.printStackTrace(new java.io.PrintWriter(firstOutput));
                text.append("FIRST_STICKY_REFUSAL\n").append(firstOutput);
            }
            if (failure!=null) {
                var output=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(output));
                text.append("FAILURE\n").append(output);
            }
            return text.toString();
        }
    }

    /** Called only by the redirect at Entity.updateFluidHeightAndDoFluidPushing()V. */
    public static boolean fluidResidency(Entity source) {
        Prepared plan=ACTIVE.get();
        if (plan==null) return source.touchingUnloadedChunk();
        try {
            plan.check(plan.binding);
            if (source!=plan.player || source.getClass()!=ServerPlayer.class)
                throw refused("Foreign entity tried to use player fluid diagnostic");
            Bounds request=bounds(source.getBoundingBox().inflate(1.0),0.0);
            plan.bounds.requireContains(request);
            if (!plan.binding.hasResidentBox(request.x1,request.y1,request.z1,request.x2,request.y2,request.z2))
                throw refused("Admitted player fluid footprint lost residency");
            plan.fluidResidencyCalls++;
            return false; // Only this actual 3D box is resident; no full column availability is claimed.
        } catch(RuntimeException|Error failure) { plan.poison(failure);throw failure; }
    }
    /** Observation guard at SurvivalLevelBinding.get HEAD; never substitutes a block result. */
    public static void beforeRead(SurvivalLevelBinding receiver,BlockPos pos) {
        Prepared plan=ACTIVE.get();if(plan==null)return;
        try {
            plan.check(receiver);
            if(!plan.cells.containsKey(pos))throw refused("Native tick read escaped its exact admitted cells: "+pos);
            CubePos cube=CubePos.fromBlock(pos.getX(),pos.getY(),pos.getZ());
            if(!plan.stamps.get(cube).equals(plan.world.stamp(cube)))throw refused("Cubic read owner epoch changed");
            if(plan.reads.size()>=MAX_READS||plan.preflightReads>=MAX_READS)throw refused("Native diagnostic read budget exceeded");
        } catch(RuntimeException|Error failure) { plan.poison(failure);throw failure; }
    }
    /** Records the actual result returned by the existing guarded cubic owner. */
    public static void afterRead(SurvivalLevelBinding receiver,BlockPos pos,BlockState result) {
        Prepared plan=ACTIVE.get();if(plan==null)return;
        beforeRead(receiver,pos);
        if(result!=plan.cells.get(pos))throw refused("Actual cubic result differs from prevalidated full-coordinate state");
        if(plan.preflighting) { plan.preflightReads++; return; }
        List<String> frames=StackWalker.getInstance().walk(stream->stream
                .filter(frame->frame.getClassName().startsWith("net.minecraft."))
                .limit(24).map(frame->frame.getClassName()+"#"+frame.getMethodName()+":"+frame.getLineNumber()).toList());
        String consumer=frames.stream().anyMatch(frame->frame.contains("Entity#updateFluidHeightAndDoFluidPushing:"))?"fluid":
                frames.stream().anyMatch(frame->frame.contains("Entity#checkInsideBlocks:"))?"inside-block":
                frames.stream().anyMatch(frame->frame.contains("BlockCollisions#"))?"collision":"other";
        plan.reads.add(new Read(pos.getX(),pos.getY(),pos.getZ(),result.toString(),consumer,frames));
    }
    /** Observe an existing native terrain refusal without cancelling/replacing the original guard. */
    public static void beforeTerrainRefusal(SurvivalLevelBinding receiver,String operation) {
        Prepared plan=ACTIVE.get();if(plan==null)return;
        plan.check(receiver);
        plan.poison(new SurvivalRefusal("Unsupported native terrain consumer: "+operation));
    }
    /** Diagnostic refusal only: never suppress a callback and continue the tick. */
    public static void rejectEffect(Entity source,String operation) {
        Prepared plan=ACTIVE.get();if(plan==null)return;
        plan.check(plan.binding);
        if(source!=plan.player)throw refused("Foreign native effect during exact-player diagnostic: "+operation);
        throw refused("Unsupported dry player-tick effect before mutation: "+operation);
    }
    public static void rejectAdvancement(net.minecraft.server.PlayerAdvancements owner,ServerPlayer source,
            net.minecraft.advancements.AdvancementHolder advancement,String criterion) {
        Prepared plan=ACTIVE.get();if(plan==null)return;
        plan.check(plan.binding);
        if(source!=plan.player||owner!=plan.player.getAdvancements())
            throw refused("Foreign player/advancement owner entered native tick diagnostic");
        try {
            if(plan.preflighting||plan.metadata==null)throw refused("Native advancement attempted mutation during read-only metadata preflight");
            plan.metadata.beforeAward(owner,source,advancement,criterion);
        } catch(RuntimeException|Error failure){plan.poison(failure);throw failure;}
    }
    public static net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> environmentBiome(
            net.minecraft.world.level.Level level,BlockPos pos) {
        Prepared plan=ACTIVE.get();if(plan==null)return null;
        try {
            plan.check(SurvivalLevelBinding.find(level));
            if(!plan.cells.containsKey(pos))throw refused("Biome query escaped exact admitted player cells");
            return plan.environment.biome(pos);
        }catch(RuntimeException|Error failure){plan.poison(failure);throw failure;}
    }
    public static net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> environmentNoiseBiome(
            net.minecraft.world.level.Level level,int x,int y,int z) {
        Prepared plan=ACTIVE.get();if(plan==null)return null;
        try {
            plan.check(SurvivalLevelBinding.find(level));
            long bx=(long)x*4,by=(long)y*4,bz=(long)z*4;
            if(bx<plan.bounds.x1-4L||bx>plan.bounds.x2+4L||by<plan.bounds.y1-4L||by>plan.bounds.y2+4L
                    ||bz<plan.bounds.z1-4L||bz>plan.bounds.z2+4L)throw refused("Quart biome query escaped exact bounded XYZ footprint");
            return plan.environment.noiseBiome(x,y,z);
        }catch(RuntimeException|Error failure){plan.poison(failure);throw failure;}
    }
    public static net.minecraft.world.level.levelgen.structure.StructureStart environmentStructure(
            net.minecraft.world.level.LevelAccessor level,BlockPos pos) {
        Prepared plan=ACTIVE.get();if(plan==null)return null;
        try {
            if(level!=plan.binding.level())throw refused("Foreign structure-manager environment receiver");
            plan.check(plan.binding);
            if(!plan.cells.containsKey(pos))throw refused("Structure query escaped exact admitted player cells");
            return plan.environment.noGeneratedStructure(pos);
        }catch(RuntimeException|Error failure){plan.poison(failure);throw failure;}
    }
    static boolean active() { return ACTIVE.get()!=null; }
    record Read(int x,int y,int z,String actualState,String consumer,List<String> nativeFrames) {}
    record State(float health,float absorption,int air,NativeFoodSnapshot food,NativeInventorySnapshot inventory,
            double x,double y,double z,double vx,double vy,double vz,float yaw,float pitch,Pose pose,
            boolean onGround,float fallDistance,int tickCount,int invulnerableTime,boolean noPhysics,
            float experienceProgress,int totalExperience,int experienceLevel) {
        static State capture(ServerPlayer player) {
            var v=player.getDeltaMovement();
            return new State(player.getHealth(),player.getAbsorptionAmount(),player.getAirSupply(),
                    NativeFoodSnapshot.capture(player.getFoodData()),NativeInventorySnapshot.capture(player,256*1024),
                    player.getX(),player.getY(),player.getZ(),v.x,v.y,v.z,player.getYRot(),player.getXRot(),
                    player.getPose(),player.onGround(),player.fallDistance,player.tickCount,player.invulnerableTime,player.noPhysics,
                    player.experienceProgress,player.totalExperience,player.experienceLevel);
        }
    }
    record Bounds(int x1,int y1,int z1,int x2,int y2,int z2) {
        Bounds {
            long nx=(long)x2-x1+1,ny=(long)y2-y1+1,nz=(long)z2-z1+1;
            if(nx<1||ny<1||nz<1||nx>MAX_CELLS||ny>MAX_CELLS/nx||nz>MAX_CELLS/(nx*ny))
                throw refused("Bounded player diagnostic footprint exceeded");
        }
        void requireContains(Bounds other) {
            if(other.x1<x1||other.y1<y1||other.z1<z1||other.x2>x2||other.y2>y2||other.z2>z2)
                throw refused("Native player residency probe escaped the admitted 3D footprint");
        }
    }
    static Bounds bounds(AABB box) { return bounds(box,3.0); }
    private static Bounds bounds(AABB box,double padding) {
        AABB b=box.inflate(padding);
        return new Bounds(floor(b.minX),floor(b.minY),floor(b.minZ),ceil(b.maxX),ceil(b.maxY),ceil(b.maxZ));
    }
    private static int floor(double n) {
        if(!Double.isFinite(n)||n<=Integer.MIN_VALUE||n>=Integer.MAX_VALUE)throw refused("Non-native coordinate");
        return (int)Math.floor(n);
    }
    private static int ceil(double n) {
        if(!Double.isFinite(n)||n<=Integer.MIN_VALUE||n>=Integer.MAX_VALUE)throw refused("Non-native coordinate");
        return (int)Math.ceil(n);
    }
    private static void requireProfile(ServerPlayer player,SurvivalLevelBinding binding) {
        if(!enabled()||player==null||binding==null||player.getClass()!=ServerPlayer.class
                ||!(player.server instanceof GameTestServer)||!player.server.isSameThread()
                ||player.level()!=binding.level()||SurvivalLevelBinding.find(player.level())!=binding)
            throw refused("Exact disposable GameTest player/receiver/binding required");
        binding.checkOpen();
        if(player.gameMode.getClass()!=ServerPlayerGameMode.class||player.gameMode.getGameModeForPlayer()!=GameType.SURVIVAL
                ||player.isSpectator()||player.isCreative()||player.noPhysics||player.isNoGravity()
                ||player.getAbilities().flying||player.getAbilities().mayfly||player.getAbilities().instabuild)
            throw refused("Fresh unmodified native Survival physics required");
        if(player.getX()!=MarinSurvivalDefinition.ANCHOR_X+0.5||player.getY()!=MarinSurvivalDefinition.GROUND_Y+1
                ||player.getZ()!=MarinSurvivalDefinition.ANCHOR_Z+0.5)
            throw refused("Diagnostic admits only genuine Marin ground134/feet135, never high-Y aliases");
        if(!player.isAlive()||player.isRemoved()||player.isPassenger()||player.isVehicle()||player.isSleeping()
                ||player.isChangingDimension()||player.portalProcess!=null||player.isUsingItem()
                ||!player.getActiveEffects().isEmpty()||player.getCamera()!=player||player.isFallFlying()
                ||player.isAutoSpinAttack()||player.isSwimming()||player.isSprinting()||player.isShiftKeyDown()
                ||player.getPose()!=Pose.STANDING||!player.getInventory().isEmpty()
                ||player.containerMenu!=player.inventoryMenu||!player.getDeltaMovement().equals(net.minecraft.world.phys.Vec3.ZERO)
                ||player.getHealth()!=player.getMaxHealth()||player.getAbsorptionAmount()!=0||player.fallDistance!=0
                ||player.isOnFire()||player.getTicksFrozen()!=0||player.tickCount!=0)
            throw refused("Only one fresh dry standing empty-inventory player tick is admitted");
        NativeInventorySnapshot.requireResolvedMenu(player);
        var food=NativeFoodSnapshot.capture(player.getFoodData());
        if(!food.equals(new NativeFoodSnapshot(20,0,5,0))||player.getAirSupply()!=player.getMaxAirSupply())
            throw refused("Fresh health, food and air required");
        if(binding.level().isRaining()||binding.level().isThundering())throw refused("Dry weather diagnostic only");
        if(!binding.level().getEntities(player,player.getBoundingBox().inflate(4)).isEmpty())
            throw refused("Nearby native entities are outside this diagnostic");
        var world=binding.world();
        if(world.hasActiveTransaction()||io.earthplatform.minecraft.survival.interaction.NativeWholeAction.hasCurrentOrOpeningAction()||world.hasUncheckpointedActions()
                ||!world.dirtyPositions().isEmpty())throw refused("Diagnostic requires idle clean cubic owner");
    }
    private static void requireDryBlock(BlockState state) {
        Block block=state.getBlock();
        if(!List.of(Blocks.AIR,Blocks.GRASS_BLOCK,Blocks.DIRT,Blocks.STONE,Blocks.OAK_LOG,Blocks.OAK_LEAVES).contains(block)
                ||!state.getFluidState().isEmpty()||state.hasBlockEntity())throw refused("Unadmitted dry native cell "+state);
    }
    private static void requireCallbacks() {
        var events=new PinnedNativeEventAdmission(NeoForge.EVENT_BUS);
        events.requireEmpty(net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre.class);
        events.requireEmpty(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post.class);
        events.requireEmpty(net.neoforged.neoforge.event.entity.living.LivingBreatheEvent.class);
        events.requireEmpty(net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent.class);
        events.requireEmpty(net.neoforged.neoforge.event.entity.EntityEvent.Size.class);
        events.requireEmpty(net.neoforged.neoforge.event.StatAwardEvent.class);
        events.requireEmpty(net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition.class);
        events.requireEmpty(net.neoforged.neoforge.event.VanillaGameEvent.class);
    }
    private static void requirePinned() {
        pin(net.minecraft.world.item.AirItem.class,"93b2794cec8378758a447fe8e24a149f138e33965b4ff479231094790d2d422b");
        pin(net.neoforged.neoforge.common.advancements.critereon.SnowBootsEntityPredicate.class,"af69a06b16c8ed12a7caf5f0c99f56cfe39fa82a30e80a21c9bd5103e05f55b3");
        pin(net.neoforged.neoforge.common.extensions.IItemExtension.class,"d65a413962ab6bf6cf8c33119b010dd16fcf482c114b8ae8abe5c59da951b088");
        pin(net.neoforged.neoforge.common.extensions.IItemStackExtension.class,"244993a9cd1a5e99507c88a0a8e1d65b6ef997d44bd73ab696a510ccf2d0397e");
        pin(net.minecraft.world.item.Item.class,"b3741b3f525b289465e9e5757df8e8c59c596e593e7ff4f7791195a05956a636");
        pin(net.minecraft.world.item.ItemStack.class,"5808e89755643aa4c614f0835a3eabff384f94343d9d30cd54541051f5701e12");
        pin(net.minecraft.core.HolderSet.Direct.class,"30e98fa0d07d887cc3eb07ca97865f73a9b21374aa85cc0a5d55da3c00092196");
        pin(net.minecraft.core.HolderSet.Named.class,"1d419477ef5e3bc14493b6d4d30a60a0879edc112b6e61aac6e7cd2e47cff0e6");
        pin(net.minecraft.core.HolderSet.ListBacked.class,"7e54d3caf55ed7f087a8be5bab2201ba3d3d2c7c123ad79f147f568012a7d423");
        pin(net.minecraft.core.Holder.Reference.class,"6fb2af8bbd11b72ffdc16e759b973b449272aa47747b8e3a5d4b548806eea05f");
        pin(net.minecraft.core.component.DataComponentPredicate.class,"112e9abb5f4d1a9bfd0e8754049f6c87b4d50679935a1e5bd6ce91d4291c59be");
        pin(net.minecraft.advancements.critereon.MinMaxBounds.Ints.class,"73d5c4d82119f9320c71749a6940dd61bd8cb3f8e71522321df9b0a890f58ce4");
        pin(net.minecraft.Util.class,"95f40c025e74321bda3e00f1f7f454e67439d11e6a6893a688a9427a42ea2353");
        pin(net.minecraft.advancements.critereon.ContextAwarePredicate.class,"d6a30f3e3657589f90c1b9984e09e538742db9bc18795cc88507be53e7852c06");
        pin(net.minecraft.advancements.critereon.EntityPredicate.class,"3888b8a47cce3e79c77f7d4aefe9db983c9789bbba12d27c6d0cf43e1a1ebf31");
        pin(net.minecraft.advancements.critereon.LocationPredicate.class,"9a77b5ce27bd69863da2c680a10db1e079e2eab26fb4cebde30a2d3c8c88266f");
        pin(net.minecraft.advancements.critereon.EntityEquipmentPredicate.class,"39f4c7ff4c261c7f5ed3948b8e213c7b45a92942ed3f816648b2b0796112e129");
        pin(net.minecraft.advancements.critereon.ItemPredicate.class,"40600bbf21a07ed0e5c739d81dddfeb5e3455f029924e931cd1da77ebacb548c");
        pin(net.minecraft.advancements.critereon.BlockPredicate.class,"b3e71016d10799dcd030b2ae68b8ecb9910b4b316fce0cdb59885855f5b4305a");
        pin(net.minecraft.advancements.critereon.PlayerTrigger.class,"0fe1b3009dc30cee5c13ddf6317b749691d5b46dcb18fccc54166347ab1924ae");
        pin(net.minecraft.advancements.critereon.SimpleCriterionTrigger.class,"e3c6eecbf8189c098dbd34994a4b7868b62cc20cc919fbd3882d296fe8ba90f2");
        pin(net.minecraft.advancements.critereon.RecipeUnlockedTrigger.class,"1eb73b63b1a0e8c36922e15356cbae79f4766793b9712d34bbea5ee6262b2bd4");
        pin(net.minecraft.advancements.AdvancementProgress.class,"b4ff8635d38b64a1b50c5e62159fd37cab5411fed6a017eb7f7f5cd7542b1922");
        pin(net.minecraft.advancements.AdvancementRequirements.class,"eff8e0d28e3c0d67300c3b0f1b452ae94a069f4325e27930c101ed9ee38ca3d3");
        pin(net.minecraft.advancements.AdvancementRewards.class,"dc16ff77c10ae1ba301521e73b7bfd89071f4a5cbdc1686c22d627dc43b0ce8f");
        pin(net.minecraft.world.level.storage.loot.predicates.LootItemEntityPropertyCondition.class,"7d78e78e5d700ab52ca50dae3ef4ca44484196331ffc9a8b51e40670ccd0a524");
        pin(net.minecraft.world.level.biome.BiomeManager.class,"f0e823411e8040e17962945be197ae2745ac35d8b4d87bd73b650d6c41ee7227");
        pin(net.minecraft.stats.ServerRecipeBook.class,"e754711ed7dd579365ee35dbe25e7bd2e0a01c47f685937e44b8bfcce68298aa");
        pin(Entity.class,"6a5a5b7db211b144fe88f3079a0e1d93a0c31db069acab4ac593dce8d144e8cb");
        pin(LivingEntity.class,"8ea049e9e714d91819947462b0700077ed1e2a2a1b71a758fe70c088540a5e31");
        pin(Player.class,"1e68a34a6c5a3513d52cf80b2d2a8e82f890b3cbd3943d7f2fe8940949890747");
        pin(ServerPlayer.class,"d48041f234ec0a2097d1947398b14eb66ef4fb17c9574517d818147ea7df74c2");
        pin(ServerPlayerGameMode.class,"b90be8fa9fedc2914a5b8f36082636f40901d7ade594b80c4d6a606838bc5c27");
        pin(net.minecraft.server.PlayerAdvancements.class,"f7ad66c62f67f61e6c658800917600e770e222ced64c62a551c7158644f4b13c");
    }
    private static void pin(Class<?> type,String expected) {
        try(var input=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
            if(input==null||!expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.readNBytes(1024*1024+1)))))throw refused("Pinned diagnostic binary changed: "+type.getName());
        }catch(IOException|java.security.NoSuchAlgorithmException failure){
            var refusal=refused("Cannot inspect diagnostic binary: "+type.getName());refusal.initCause(failure);throw refusal;
        }
    }
    private static SurvivalRefusal refused(String detail) {
        var failure=new SurvivalRefusal(detail);
        Prepared plan=ACTIVE.get();if(plan!=null)plan.poison(failure);
        return failure;
    }
}
