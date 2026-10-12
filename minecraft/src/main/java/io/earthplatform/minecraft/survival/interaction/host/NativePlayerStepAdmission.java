package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

/** Read-only source closure. No native simulation, hand-written motion or field reset. */
final class NativePlayerStepAdmission {
    static final int MAX_CELLS=4096;
    record Bounds(int x1,int y1,int z1,int x2,int y2,int z2) {
        Bounds {
            long nx=(long)x2-x1+1,ny=(long)y2-y1+1,nz=(long)z2-z1+1;
            if(nx<1||ny<1||nz<1||nx>MAX_CELLS||ny>MAX_CELLS/nx||nz>MAX_CELLS/(nx*ny))
                throw refused("Player step sealed XYZ footprint exceeds budget");
        }
        int cells(){return Math.toIntExact(((long)x2-x1+1)*((long)y2-y1+1)*((long)z2-z1+1));}
        boolean contains(int x,int y,int z){return x>=x1&&x<=x2&&y>=y1&&y<=y2&&z>=z1&&z<=z2;}
        boolean contains(BlockPos p){return contains(p.getX(),p.getY(),p.getZ());}
        void requireContains(Bounds b){if(!contains(b.x1,b.y1,b.z1)||!contains(b.x2,b.y2,b.z2))throw refused("Player step residency escaped sealed XYZ footprint");}
    }
    static Bounds footprint(AABB body,Vec3 velocity,Vec3 requested) {
        if(body.getXsize()<=0||body.getXsize()>1||body.getYsize()<=0||body.getYsize()>2||body.getZsize()<=0||body.getZsize()>1
                ||!bounded(velocity.x,1)||!bounded(velocity.y,1)||!bounded(velocity.z,1)
                ||!bounded(requested.x,1)||!bounded(requested.y,1)||!bounded(requested.z,1))
            throw refused("Native step body or swept displacement exceeds dry bound");
        // Native code computes motion once. This is only a conservative read envelope,
        // including native one-tick gravity, legacy support, fluid eye and collision margins.
        AABB box=body.expandTowards(velocity).expandTowards(0,-1,0).minmax(body.move(requested)).inflate(3);
        return box(box);
    }
    static Bounds box(AABB box){return new Bounds(NativeItemTickReadScope.floor(box.minX),NativeItemTickReadScope.floor(box.minY),
            NativeItemTickReadScope.floor(box.minZ),NativeItemTickReadScope.ceil(box.maxX),NativeItemTickReadScope.ceil(box.maxY),NativeItemTickReadScope.ceil(box.maxZ));}
    static Vec3 requireInput(ServerPlayer p,NativePlayerStepOperation operation,ServerboundMovePlayerPacket packet) {
        if(operation!=NativePlayerStepOperation.MOVE_PACKET||packet==null)throw refused("Exact typed native step input required");

        if(packet.getClass()!=ServerboundMovePlayerPacket.Pos.class&&packet.getClass()!=ServerboundMovePlayerPacket.PosRot.class
                &&packet.getClass()!=ServerboundMovePlayerPacket.Rot.class&&packet.getClass()!=ServerboundMovePlayerPacket.StatusOnly.class)
            throw refused("Custom native movement packet class");
        double x=packet.getX(p.getX()),y=packet.getY(p.getY()),z=packet.getZ(p.getZ());
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                ||!Float.isFinite(packet.getYRot(p.getYRot()))||!Float.isFinite(packet.getXRot(p.getXRot()))
                ||Math.abs(packet.getYRot(p.getYRot()))>360000||Math.abs(packet.getXRot(p.getXRot()))>90
                ||Math.abs(x)>29_999_984||Math.abs(y)>29_999_984||Math.abs(z)>29_999_984)
            throw refused("Native packet contains invalid or clamped coordinates");
        Vec3 delta=new Vec3(x-p.getX(),y-p.getY(),z-p.getZ());
        // Initial excavation fixture is a dry vertical drop, never a reconstructed client.
        if(delta.x!=0||delta.z!=0||delta.y>0||delta.y< -1||p.fallDistance-delta.y>1.000001)
            throw refused("First native move lane admits at most one block cumulative vertical descent");
        return delta;
    }
    static void requireProfile(ServerPlayer p,SurvivalLevelBinding binding,NativePlayerStepOperation operation,Bounds bounds,NativePlayerStepSnapshot before) {
        if(operation!=NativePlayerStepOperation.MOVE_PACKET)throw refused("Only MOVE_PACKET has an admitted native effect closure");
        if(p.getClass()!=ServerPlayer.class||!p.server.isSameThread()||p.level()!=p.serverLevel()
                ||SurvivalLevelBinding.find(p.level())!=binding||p.connection==null||p.connection.player!=p)
            throw refused("Player step identity/thread/receiver changed");
        if(!NativePlayerStepReadScope.permits(binding)) binding.checkOpen();
        NativeInventorySnapshot.requireResolvedMenu(p);
        if(p.gameMode.getClass()!=ServerPlayerGameMode.class||p.gameMode.getGameModeForPlayer()!=GameType.SURVIVAL
                ||p.wonGame||!p.isAlive()||p.isRemoved()||p.hasDisconnected()||p.noPhysics||p.isNoGravity()||p.isPassenger()||p.isVehicle()
                ||p.isSleeping()||p.isChangingDimension()||p.portalProcess!=null||p.isUsingItem()||!p.getActiveEffects().isEmpty()
                ||p.getCamera()!=p||p.isFallFlying()||p.isAutoSpinAttack()||p.isSwimming()||p.isSprinting()||p.isShiftKeyDown()
                ||p.getPose()!=Pose.STANDING||p.getHealth()!=p.getMaxHealth()||p.getAbsorptionAmount()!=0
                ||p.getAirSupply()!=p.getMaxAirSupply()||p.isOnFire()||p.getTicksFrozen()!=0||p.fallDistance<0||p.fallDistance>1.000001F
                ||p.getAbilities().flying||p.mayFly()||p.getAbilities().invulnerable||p.getAbilities().instabuild
                ||p.isInWater()||p.isInLava()||p.isInPowderSnow||!p.getShoulderEntityLeft().isEmpty()||!p.getShoulderEntityRight().isEmpty()
                ||p.getAttributeValue(Attributes.SAFE_FALL_DISTANCE)<3||p.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER)!=1
                ||p.getAttributeValue(Attributes.GRAVITY)!=0.08||p.tickCount<0||p.tickCount==Integer.MAX_VALUE)
            throw refused("Unadmitted native dry standing player state");
        if(!p.getDeltaMovement().equals(new Vec3(0,p.getDeltaMovement().y,0))||p.getDeltaMovement().y>0)
            throw refused("First native step lane has no horizontal or upward controller");
        if((Boolean)NativePlayerStepSnapshot.field(ServerPlayerGameMode.class,p.gameMode,"isDestroyingBlock")
                ||(Boolean)NativePlayerStepSnapshot.field(ServerPlayerGameMode.class,p.gameMode,"hasDelayedDestroy"))
            throw refused("Native game-mode delayed block mutation is outside this step receipt");
        if((Boolean)NativePlayerStepSnapshot.field(Player.class,p,"ignoreFallDamageFromCurrentImpulse")||p.currentImpulseImpactPos!=null
                ||p.currentExplosionCause!=null||NativePlayerStepSnapshot.field(ServerPlayer.class,p,"levitationStartPos")!=null
                ||NativePlayerStepSnapshot.field(ServerPlayer.class,p,"enteredLavaOnVehiclePosition")!=null
                ||(Boolean)NativePlayerStepSnapshot.field(ServerPlayer.class,p,"spawnExtraParticlesOnFall"))
            throw refused("Native impulse/levitation/lava/landing particle state is unowned");
        if(p.moveDist>(Float)NativePlayerStepSnapshot.field(Entity.class,p,"nextStep"))
            throw refused("Pending native walking sound/event is outside vertical-move output closure");
        if((Boolean)NativePlayerStepSnapshot.field(LivingEntity.class,p,"jumping")||p.xxa!=0||p.yya!=0||p.zza!=0)
            throw refused("Native controller input exceeds standing dry step");
        var c=before.connection();
        if(c.correction().isPresent()||c.received()<0||c.known()<0||c.received()==Integer.MAX_VALUE||c.tickCount()<0||c.tickCount()==Integer.MAX_VALUE
                ||c.received()-c.known()<0||c.received()-c.known()>=64||c.floatingTicks()>1||c.vehicleFloating())
            throw refused("Pending correction, stale packet burst, floating or native counter overflow");
        if(operation==NativePlayerStepOperation.MOVE_PACKET&&(c.lastX()!=p.getX()||c.lastY()!=p.getY()||c.lastZ()!=p.getZ()))
            throw refused("Movement receiver last-good position is stale");
        if(p.serverLevel().isRaining()||p.serverLevel().isThundering()||!p.serverLevel().tickRateManager().runsNormally())
            throw refused("First native step requires dry normal-rate world");
        if(!p.serverLevel().getWorldBorder().isWithinBounds(p.getBoundingBox().inflate(4)))throw refused("Native world border is outside the step profile");
        for(int slot=0;slot<p.getInventory().getContainerSize();slot++){
            ItemStack stack=p.getInventory().getItem(slot);if(stack.isEmpty())continue;
            PinnedNativeEventAdmission.requireOrdinaryItem(stack);
            if(slot>=36&&slot<=39&&!stack.isEmpty())throw refused("Equipped armor tick/effect closure is not admitted");
            requireItemTick(stack);
        }
        requireCallbacks();requirePinnedBinaries();
    }
    private static void requireItemTick(ItemStack stack){
        if(stack.isEmpty())return;
        for(var component:stack.getComponentsPatch().entrySet())
            if(component.getKey()!=net.minecraft.core.component.DataComponents.DAMAGE||component.getValue().isEmpty())
                throw refused("Native step has an unadmitted inventory component override");
        if(stack.getItem().isComplex())throw refused("Complex inventory update is outside native step closure");
        try{Method method=stack.getItem().getClass().getMethod("inventoryTick",ItemStack.class,Level.class,Entity.class,int.class,boolean.class);
            if(method.getDeclaringClass()!=Item.class)throw refused("Custom native inventoryTick callback");
        }catch(ReflectiveOperationException failure){throw refused("Cannot inspect exact inventory tick callback");}
    }
    static void requireBlock(BlockState state){NativeDryItemTickAdmission.requireBlock(state);}
    static void requireCallbacks(){
        var events=new PinnedNativeEventAdmission(NeoForge.EVENT_BUS);events.requireDeliveryCallbacks();
        events.requireEmpty(net.neoforged.neoforge.event.entity.living.LivingFallEvent.class);
        events.requireEmpty(net.neoforged.neoforge.event.StatAwardEvent.class);
        events.requireEmpty(net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition.class);
        events.requireEmpty(net.neoforged.neoforge.event.VanillaGameEvent.class);
    }
    static void requirePinnedBinaries(){
        NativeDryItemTickAdmission.requirePinnedBinaries();
        PinnedNativeEventAdmission.requireClassBytes(net.neoforged.neoforge.common.CommonHooks.class,"86c8a56c77fff474bf4fe90f0354ad32698f018b5e19dc52a2725ee5ff96c73e");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.ServerAdvancementManager.class,"6de1937f8b0f39a1fd50d611744dd7561fee048510d3bc9c1bc8c4f2b2f3c121");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.PlayerAdvancements.class,"f7ad66c62f67f61e6c658800917600e770e222ced64c62a551c7158644f4b13c");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.advancements.Advancement.class,"f85ec3ba17899234fa59d2e58a431255bf04b5da33cbfa9d7d03351ab0479f05");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.advancements.AdvancementHolder.class,"285659e918a865889c96b2189dbbff30855d3f0d0331023d8d0a66b99ae4b3fb");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.advancements.Criterion.class,"9042e4d8ef32927c95bf4cb7b4d83feb5ad24627490337f2326451ac7a807980");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.advancements.CriterionTrigger$Listener",net.minecraft.server.level.ServerPlayer.class),"0bc1c09598dcf9f9a9b01687af901b139e8f89fa1fafa1470d4609ed94a85534");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.advancements.critereon.SimpleCriterionTrigger.class,"e3c6eecbf8189c098dbd34994a4b7868b62cc20cc919fbd3882d296fe8ba90f2");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.advancements.critereon.EntityPredicate.class,"3888b8a47cce3e79c77f7d4aefe9db983c9789bbba12d27c6d0cf43e1a1ebf31");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.level.storage.loot.LootParams.class,"c2c6bfde94f657dba4a032b00c1510d4fad2094681713bc5f179bdc2bfda7eae");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.world.level.storage.loot.LootParams$Builder",net.minecraft.server.level.ServerPlayer.class),"0ab642544521ed269a8864c706e728da5d6405ed1c07170feb41c507e0a5acd8");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.level.storage.loot.LootContext.class,"40c994732a945a437b342769bfc63c96ff3f285d55ad10dfeca0d819ed23be8e");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.world.level.storage.loot.LootContext$Builder",net.minecraft.server.level.ServerPlayer.class),"61c22af4d635c2897f824326a33841e1974ba8f9baa601ce579ff2f2881fbe1c");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.level.storage.loot.parameters.LootContextParamSet.class,"c49988bacb950325959f23f32c7ebee16df0f9f8de421b9cd865e0eacff06454");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.class,"89db2a034a99c086f0d609ecd69693afa7b3253b8f15449fd2a2bc7e87c9a8d4");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.level.Level.class,"e6447eec7e478face83d0299535e967e87991c549f5ffac8652fb7658b1d75b1");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.ReloadableServerResources.class,"6ec0cbeaedee31db73c60448bf50502c9e30f7f1043478fc08ad0e0e8f9835c6");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.server.ReloadableServerRegistries$Holder",net.minecraft.server.level.ServerPlayer.class),"971ec06e1bf884662c91066529ad8d1293622da94e854afbf0d539cf600df3f1");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.core.HolderLookup$Provider",net.minecraft.server.level.ServerPlayer.class),"82cab1ad00a02b1618283dd5fa315ecebc68155a6daa2e66f72e94d51fe6fb0f");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.core.HolderLookup$Provider$1",net.minecraft.server.level.ServerPlayer.class),"3570dd0c8c32b9b68186957af13958e5fe95056158aed1af866fb8f4f4a26a53");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.advancements.critereon.LocationPredicate.class,"9a77b5ce27bd69863da2c680a10db1e079e2eab26fb4cebde30a2d3c8c88266f");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.advancements.critereon.LocationPredicate$PositionPredicate",net.minecraft.server.level.ServerPlayer.class),"18679a255c7e285f5c0e2a3edb9a0a655c11d753848911e4e5cc996af93d294e");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.advancements.critereon.MinMaxBounds$Doubles",net.minecraft.server.level.ServerPlayer.class),"3547e03088e98398a4dc0695e7eeac89cec492c223f091a700eed2090078c781");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.level.gameevent.GameEventDispatcher.class,"5847aae9374e3efc6f98778799816ba648d83a1cb22e254c807d143093e57b03");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.level.ChunkMap.class,"0a14ff180458556774f01dec21f2ceb4245917239239edd4e1cb6a0223028b62");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.server.level.ChunkMap$TrackedEntity",net.minecraft.server.level.ServerPlayer.class),"8466d909c84c3b7e8c584ba0f8b634f23a8b5bb3a77c6ab2d397a9826cd0d0d0");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.level.PlayerMap.class,"8356f1d25e61a59574c167f7bb142db499ca76239a0e2e671eb61e80c8bcdae6");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.level.ServerChunkCache.class,"8a9bd89991a95bcd3cd1923b4fd53dbd0c7b5e2ca92084435bb2d121f5f8e3e8");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.level.ChunkTrackingView.class,"c2011570d64131971541dbbc81ada66e4e1b6a1f1845df27f13a56dc0a2977a2");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.server.level.ChunkTrackingView$Positioned",net.minecraft.server.level.ServerPlayer.class),"2e8873ebb1888d4185b2b249f0bd56c95e4bfe31cf69698256ec22cb54208a72");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.server.level.ChunkTrackingView$1",net.minecraft.server.level.ServerPlayer.class),"9484aee535984099c6c49863d10c7840e73b5b507126294b1155f3f109e13198");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.network.PlayerChunkSender.class,"0a52201d0f6d90833e30b1d3abe880422386ce1a2f011b1a71ae9456e4b66b0f");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.players.PlayerList.class,"164f99fa12710bd1cf80901abc5e38bae32bfeb352bf67f88220e620b0ec6b24");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.gametest.framework.GameTestServer.class,"240a7a4968bc0134749d8251c030b16fa67ed3429f7d439d4b4b81102eff5690");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.gametest.framework.GameTestServer$1",net.minecraft.server.level.ServerPlayer.class),"ed099a0f4ac49a731dcf77369b8ac2013d4e65ffc03bd42c6a9c1743eace295c");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.MinecraftServer.class,"f42f6ff6f3ee1b2b678c222b192fccca995686b882eb99546284e29c683d09e6");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.network.ServerCommonPacketListenerImpl.class,"2626df5c94f13f877364d5c13aba9d587f2261589f039ccd89b52341eeb0a1e0");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.item.ItemStack.class,"5808e89755643aa4c614f0835a3eabff384f94343d9d30cd54541051f5701e12");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.item.Item.class,"b3741b3f525b289465e9e5757df8e8c59c596e593e7ff4f7791195a05956a636");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.core.component.PatchedDataComponentMap.class,"0f41f717d09f7083a0f20900db4f198984ed909e1263591a7deefd8821f3f139");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.core.component.DataComponentPatch.class,"1d11c8d2aad35b78061beebc0d216984f6a941bb5942d7f8a9f7d4cb25fb531c");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.core.component.DataComponentMap$Builder$SimpleMap",net.minecraft.server.level.ServerPlayer.class),"9dea24b79705077e254adf3628be44d44edbd3a0d8b99f2c5cd5798c93a359b6");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.core.NonNullList.class,"a82cbbcae24d51abeb544832e6dd0580dbd98f0b981fe1b6783ed1783fe815be");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.inventory.AbstractContainerMenu.class,"4435ed1beb3dc5bc259b52e64dd73fd5f2511b8f95a14b945ef574f8d4f665a8");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.inventory.InventoryMenu.class,"c56c8c836495ecb0417c891e8ea85b0e3cbea46dc1d4a7ab0fd7b29213cc0019");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.world.inventory.InventoryMenu$1",net.minecraft.server.level.ServerPlayer.class),"3204ed538cd9ce9c66d15f69fa89b06c4d2da18b72df187cb90b4ecb4567bf89");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.inventory.Slot.class,"622cf3c642593e383f9fc587539e445c1dc381cdf7765dbc53d9a8c65a68a755");
        PinnedNativeEventAdmission.requireClassBytes(NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.world.inventory.ArmorSlot",net.minecraft.server.level.ServerPlayer.class),"342ef6e0a81075db10f3ef58c2bd5f545aac6ef26cdf8419d10cfecf1f723e0f");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.inventory.ResultSlot.class,"5058213077f00f734f1ef2b5a9eb44ce09e05b4c918a4e4c8a0a02b3857b12d6");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.inventory.ResultContainer.class,"6977aab9aae68506eb4bdc281919e4ece67b8cfc98e9d8fea350adb696100e7e");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.inventory.TransientCraftingContainer.class,"7a8911c53e214c902737e741509dbefa70c3687c4d18aec9f987b777eb24fbe0");
        PinnedNativeEventAdmission.requireClassBytes(com.google.common.collect.Maps.class,"76b3557742fdd9a86c2e74f80d170f6bc8151ff6814d48362320a2f0cdbf3459");
        PinnedNativeEventAdmission.requireClassBytes(com.google.common.collect.Sets.class,"ded231b21a74dde58b00d0c0665da96c5e3e3e2faba1da79a8206817a3edeec1");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.entity.Entity.class,"6a5a5b7db211b144fe88f3079a0e1d93a0c31db069acab4ac593dce8d144e8cb");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.entity.LivingEntity.class,"8ea049e9e714d91819947462b0700077ed1e2a2a1b71a758fe70c088540a5e31");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.entity.player.Player.class,"1e68a34a6c5a3513d52cf80b2d2a8e82f890b3cbd3943d7f2fe8940949890747");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.level.ServerPlayer.class,"d48041f234ec0a2097d1947398b14eb66ef4fb17c9574517d818147ea7df74c2");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.level.ServerLevel.class,"e4e249f6a71830ab4eda69b25ea10cfe3a3e0af5d84a6926016cab18775c4c32");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.server.network.ServerGamePacketListenerImpl.class,"0602f43318d5bf25d5c2f0468728dd26a61cac8e899e58a86dd5a81343417138");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.food.FoodData.class,"1ee10f201b38d29d0703ee9566bdf984dd9d9c9f97e3f6f32617312ddc54fa4b");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.entity.player.Inventory.class,"26e98bc47374a2ee9b36edd274207c525589aebccafd2e1051c6201c901c5219");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.advancements.critereon.DistanceTrigger.class,"d44f3c3c07244b5b9f651af4abbc4889619e9990618f2bbe8c039bb35bd61015");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.advancements.critereon.DistanceTrigger.TriggerInstance.class,"101ee3899c84348bfea80e4655a48b02b3866f3cf41fd5af2847fa742fd1fbe8");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.advancements.critereon.EnterBlockTrigger.class,"3dfeeab2c57ef150face12dc4e3bf3b80a6e8c6d66ae3d1eece6f6918a3e91b7");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.advancements.critereon.EnterBlockTrigger.TriggerInstance.class,"a19da2c16ec1e7ab3a365b84c995a20cc91f347f048ad9a8ee93e374c804ed67");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.class,"3eecee225bc40e8201018de51a17e86b667954dbebf7d5d54be4b78389073fa8");
        PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.entity.monster.warden.WardenSpawnTracker.class,"57b2bd4945e477c3b751c1f4704b1de4949428947ba3b369e082e633b3ba3f7b");
    }
    private static boolean bounded(double n,double max){return Double.isFinite(n)&&Math.abs(n)<=max;}
    private static SurvivalRefusal refused(String message){return new SurvivalRefusal(message);}
}
