package io.earthplatform.minecraft.survival.host;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real FML-transformed pinned vanilla methods, not reimplemented stand-ins. The server object is
 * allocated without starting a server and only its documented read fixture fields are populated.
 * This proves routing/refusal/transport boundaries, not ordinary server ticks or gameplay.
 */
class SurvivalLevelHooksTest {
    @TempDir static java.nio.file.Path saveRoot;
    private static net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess saveAccess;
    private static SurvivalPalette palette;
    private static HolderLookup.Provider lookup;
    private static RegistryAccess.Frozen registries;
    private static Holder<DimensionType> dimension;
    private static Holder<Biome> biome;
    private static final BlockPos TARGET=new BlockPos(-13640057,134,-4213188);
    private static final UUID ACTOR=new UUID(39,1);
    @BeforeAll static void setup() throws Exception {
        saveAccess=net.minecraft.world.level.storage.LevelStorageSource.createDefault(saveRoot).createAccess("serializer-fixture");
        lookup=VanillaRegistries.createLookup();
        var biomes=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(h->biomes.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));
        biomes.freeze();
        registries=new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)).freeze();
        dimension=lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        biome=biomes.getHolderOrThrow(Biomes.PLAINS);
        palette=new SurvivalPalette(EnginePaletteContext.create(biomes));
    }
    @AfterAll static void closeStorage() throws Exception {
        if(saveAccess!=null)saveAccess.close();
    }
    private record Fixture(ServerLevel level,NativeSurvivalWorld world,NativeWholeAction.Owner actions,SurvivalLevelBinding binding) {
        void admit(BlockPos pos,BlockState state) {
            world.admitGenerated(cube(pos),ImmutableCube.uniform(palette.id(state)));
        }
        NativeWholeAction begin(long sequence) {
            var stamps=new java.util.HashMap<CubePos,NativeSurvivalWorld.CubeStamp>();
            for(var cube:world.ownedPositions()) if(world.presence(cube)==NativeSurvivalWorld.Presence.RESIDENT) stamps.put(cube,world.stamp(cube));
            return actions.begin(world.actionIdentity(ACTOR,sequence),stamps,inventory());
        }
    }
    private static Fixture fixture(SurvivalLevelBinding.MutationRouter router,NativeWholeAction.EffectHost host) throws Exception {
        return fixture(router,host,(b,c,s)->{throw new SurvivalRefusal("fixture tick policy is not admitted");});
    }
    private static Fixture fixture(SurvivalLevelBinding.MutationRouter router,NativeWholeAction.EffectHost host,
            SurvivalLevelBinding.CarrierTickPolicy ticks) throws Exception {
        var world=new NativeSurvivalWorld(new WorldIdentity(39,"actual-level-hooks","v1","test",palette.identity()),palette,
                new NativeSurvivalWorld.Bounds(new CubePos(-1000000,-688,-1000000),new CubePos(1000000,553,1000000)),
                new NativeSurvivalWorld.Limits(8,8,4,8,4096,32,32,65536,8,8));
        var unsafeClass=Class.forName("sun.misc.Unsafe"); Field field=unsafeClass.getDeclaredField("theUnsafe");field.setAccessible(true);
        var level=(ServerLevel)unsafeClass.getMethod("allocateInstance",Class.class).invoke(field.get(null),ServerLevel.class);
        set(level,"dimension",SurvivalLevelBinding.DIMENSION);set(level,"thread",Thread.currentThread());
        set(level,"registryAccess",registries);set(level,"dimensionTypeRegistration",dimension);
        var access=assertInstanceOf(SurvivalLevelAccess.class,level,"Production mixins must actually be applied by the FML bootstrap");
        var actions=new NativeWholeAction.Owner(world,lookup,host);
        var binding=new SurvivalLevelBinding(level,world,actions,router,ticks);
        access.earthplatform$bindSurvival(binding);
        return new Fixture(level,world,actions,binding);
    }
    private static Fixture fixture() throws Exception {
        return fixture((a,c,s,f,d)->{throw new SurvivalRefusal("fixture has no admitted mutation policy");},(a,e)->receipt->{});
    }
    private static void set(Level level,String name,Object value)throws Exception {
        Field field=Level.class.getDeclaredField(name);field.setAccessible(true);field.set(level,value);
    }
    private static CubePos cube(BlockPos pos){return CubePos.fromBlock(pos.getX(),pos.getY(),pos.getZ());}
    private static NativeWholeAction.InventoryView inventory(){return new NativeWholeAction.InventoryView(){
        public int size(){return 41;}public ItemStack getItem(int slot){return ItemStack.EMPTY;}public int selectedSlot(){return 0;}
    };}
    private static LevelChunk carrier(Fixture fixture,boolean empty) {
        ChunkPos position=new ChunkPos(TARGET);
        return empty?new EmptyLevelChunk(fixture.level,position,biome):new LevelChunk(fixture.level,position);
    }
    @Test void realLevelAndBothChunkClassesPreserveTrueYAndGlobalCoordinates()throws Exception {
        var f=fixture();var high=new BlockPos(TARGET.getX(),7314,TARGET.getZ());var alias=new BlockPos(TARGET.getX(),3218,TARGET.getZ());
        assertEquals(high.asLong(),alias.asLong());
        f.admit(high,Blocks.OAK_LOG.defaultBlockState());f.admit(alias,Blocks.CRAFTING_TABLE.defaultBlockState());
        assertSame(Blocks.OAK_LOG.defaultBlockState(),f.level.getBlockState(high));
        assertSame(Blocks.CRAFTING_TABLE.defaultBlockState(),f.level.getBlockState(alias));
        assertTrue(f.level.getFluidState(high).isEmpty());assertNull(f.level.getBlockEntity(high));
        assertEquals(-64,f.level.getMinBuildHeight(),"Vanilla carrier allocation envelope is not the native terrain envelope");
        for(boolean empty:new boolean[]{false,true}){
            var chunk=carrier(f,empty);
            assertSame(Blocks.OAK_LOG.defaultBlockState(),chunk.getBlockState(high));
            assertSame(Blocks.CRAFTING_TABLE.defaultBlockState(),chunk.getBlockState(alias));
            assertTrue(chunk.getFluidState(high).isEmpty());assertTrue(chunk.getFluidState(high.getX(),high.getY(),high.getZ()).isEmpty());
            assertNull(chunk.getBlockEntity(high));assertNull(chunk.getBlockEntity(high,LevelChunk.EntityCreationType.IMMEDIATE));
        }
        assertSame(f.binding.view(),f.level.getChunkForCollisions(TARGET.getX()>>4,TARGET.getZ()>>4));
        assertFalse(f.level.getBlockState(high).getCollisionShape(f.level.getChunkForCollisions(0,0),high).isEmpty());
    }
    @Test void unknownAndRetiredCellsRefuseBeforeAirFluidAndNullBlockEntityFallbacks()throws Exception {
        var f=fixture();var chunk=carrier(f,true);
        assertThrows(SurvivalRefusal.class,()->f.level.getBlockState(TARGET));
        assertThrows(SurvivalRefusal.class,()->f.level.getFluidState(TARGET));
        assertThrows(SurvivalRefusal.class,()->f.level.getBlockEntity(TARGET));
        assertThrows(SurvivalRefusal.class,()->chunk.getBlockEntity(TARGET));
        assertThrows(SurvivalRefusal.class,()->chunk.getFluidState(TARGET.getX(),TARGET.getY(),TARGET.getZ()));
        assertThrows(SurvivalRefusal.class,()->chunk.getLightEmission(TARGET));
        f.admit(TARGET,Blocks.STONE.defaultBlockState());f.world.retire(cube(TARGET));
        assertFalse(f.level.isLoaded(TARGET));assertFalse(f.level.hasChunkAt(TARGET));
        assertThrows(SurvivalRefusal.class,()->chunk.getBlockState(TARGET));
    }
    @Test void loadedDefaultsCheckEveryCubicCellRangeRatherThanOnlyXZ()throws Exception {
        var f=fixture();var high=new BlockPos(TARGET.getX(),7314,TARGET.getZ());f.admit(high,Blocks.STONE.defaultBlockState());
        assertTrue(f.level.isLoaded(high));assertTrue(f.level.hasChunkAt(high));
        assertTrue(f.level.hasChunksAt(high,high));
        assertFalse(f.level.hasChunkAt(new BlockPos(high.getX(),3218,high.getZ())));
        assertFalse(f.level.hasChunksAt(high,high.above(16)));
        assertFalse(f.level.hasChunkAt(high.getX(),high.getZ()),"A resident cube does not prove a full vertical column");
        assertFalse(f.level.hasChunksAt(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));
    }
    @Test void writesRefuseBeforeVanillaNotificationsDropsOrForeignTransactions()throws Exception {
        var invoked=new AtomicInteger();
        var f=fixture((a,c,s,flags,depth)->{invoked.incrementAndGet();throw new SurvivalRefusal("unsupported flags/callbacks");},(a,e)->r->{});
        f.admit(TARGET,Blocks.STONE.defaultBlockState());var chunk=carrier(f,false);
        assertThrows(SurvivalRefusal.class,()->f.level.setBlock(TARGET,Blocks.AIR.defaultBlockState(),3));
        assertThrows(SurvivalRefusal.class,()->f.level.removeBlock(TARGET,false));
        assertThrows(SurvivalRefusal.class,()->f.level.destroyBlock(TARGET,true,null,512));
        assertThrows(SurvivalRefusal.class,()->chunk.setBlockState(TARGET,Blocks.AIR.defaultBlockState(),false));
        assertEquals(0,invoked.get());
        try(var raw=f.world.begin(f.world.actionIdentity(ACTOR,1),Map.of(cube(TARGET),f.world.stamp(cube(TARGET))))){
            assertThrows(SurvivalRefusal.class,()->f.level.setBlock(TARGET,Blocks.AIR.defaultBlockState(),16));
            assertThrows(SurvivalRefusal.class,()->f.level.getBlockState(TARGET));
        }
        var foreign=new NativeWholeAction.Owner(f.world,lookup,(a,e)->r->{});
        try(var action=foreign.begin(f.world.actionIdentity(ACTOR,2),Map.of(cube(TARGET),f.world.stamp(cube(TARGET))),inventory())){
            assertThrows(SurvivalRefusal.class,()->f.level.setBlock(TARGET,Blocks.AIR.defaultBlockState(),16));
        }
        try(var action=f.begin(3)){
            assertThrows(SurvivalRefusal.class,()->f.level.setBlock(TARGET,Blocks.AIR.defaultBlockState(),3));
            assertEquals(1,invoked.get());assertSame(Blocks.STONE.defaultBlockState(),action.read(CubeCell.of(TARGET.getX(),TARGET.getY(),TARGET.getZ())));
            assertThrows(SurvivalRefusal.class,()->f.level.destroyBlock(TARGET,true,null,512));
        }
        assertSame(Blocks.STONE.defaultBlockState(),f.level.getBlockState(TARGET));assertEquals(0,f.world.commitSequence());
    }
    @Test void exactActionRouterSeesReadYourWritesAndOnlyStagesEffects()throws Exception {
        var deliveries=new AtomicInteger();var routed=new AtomicInteger();
        var f=fixture((action,cell,state,flags,depth)->{
            if(flags!=16)throw new SurvivalRefusal("fixture only admits its data-only stone/plank transition");
            if(state!=Blocks.OAK_PLANKS.defaultBlockState() || action.read(cell)!=Blocks.STONE.defaultBlockState())throw new SurvivalRefusal("fixture state transition unsupported");
            routed.incrementAndGet();var before=action.set(cell,state);
            action.stage(new NativeEffectCodec.GameEvent(ResourceLocation.withDefaultNamespace("block_change"),cell,ACTOR));return before;
        },(a,e)->{assertEquals(2,e.size());assertEquals(1,e.stream().filter(NativeEffectCodec.GameEvent.class::isInstance).count());return r->deliveries.incrementAndGet();});
        f.admit(TARGET,Blocks.STONE.defaultBlockState());
        try(var action=f.begin(1)){
            assertTrue(f.level.setBlock(TARGET,Blocks.OAK_PLANKS.defaultBlockState(),16,0));
            assertSame(Blocks.OAK_PLANKS.defaultBlockState(),f.level.getBlockState(TARGET));assertEquals(0,deliveries.get());
        }
        assertSame(Blocks.STONE.defaultBlockState(),f.level.getBlockState(TARGET));
        try(var action=f.begin(2)){f.level.setBlock(TARGET,Blocks.OAK_PLANKS.defaultBlockState(),16,0);action.commit();}
        assertEquals(2,routed.get());assertEquals(1,deliveries.get());assertSame(Blocks.OAK_PLANKS.defaultBlockState(),f.level.getBlockState(TARGET));
    }
    @Test void terrainSectionHeightBiomeLightAndTickConsumersCannotReadCarrierAsTerrain()throws Exception {
        var f=fixture();f.admit(TARGET,Blocks.STONE.defaultBlockState());
        assertThrows(SurvivalRefusal.class,()->f.level.getHeight(Heightmap.Types.MOTION_BLOCKING,TARGET.getX(),TARGET.getZ()));
        assertThrows(SurvivalRefusal.class,()->f.level.getBiome(TARGET));assertThrows(SurvivalRefusal.class,()->f.level.getUncachedNoiseBiome(0,0,0));assertThrows(SurvivalRefusal.class,()->f.level.getLightEngine());
        for(boolean empty:new boolean[]{false,true}){
            var chunk=carrier(f,empty);
            assertThrows(SurvivalRefusal.class,chunk::getSections);assertThrows(SurvivalRefusal.class,()->chunk.getSection(0));
            assertThrows(SurvivalRefusal.class,chunk::isEmpty);assertThrows(SurvivalRefusal.class,()->chunk.isYSpaceEmpty(128,143));
            assertThrows(SurvivalRefusal.class,()->chunk.isSectionEmpty(8));assertThrows(SurvivalRefusal.class,chunk::getHeightmaps);
            assertThrows(SurvivalRefusal.class,()->chunk.getHeight(Heightmap.Types.MOTION_BLOCKING,0,0));
            assertThrows(SurvivalRefusal.class,()->chunk.getNoiseBiome(0,0,0));assertThrows(SurvivalRefusal.class,chunk::getSkyLightSources);
            assertThrows(SurvivalRefusal.class,chunk::getBlockTicks);assertThrows(SurvivalRefusal.class,chunk::getFluidTicks);
        }
    }
    @Test void exactEmptyCarrierPacketSerializationWorksWithoutTerrainGetterEscape()throws Exception {
        var f=fixture();f.admit(TARGET,Blocks.STONE.defaultBlockState());var chunk=carrier(f,false);
        assertThrows(SurvivalRefusal.class,()->new ClientboundLevelChunkPacketData(chunk));
        f.binding.admitCarrier(chunk);
        var packet=new ClientboundLevelChunkPacketData(chunk);
        assertTrue(packet.getReadBuffer().readableBytes()>0);assertFalse(packet.getHeightmaps().isEmpty());
        assertThrows(SurvivalRefusal.class,chunk::getSections);
        assertSame(Blocks.STONE.defaultBlockState(),chunk.getBlockState(TARGET));
        var raw=(SurvivalCarrierChunkAccess)chunk;raw.earthplatform$carrierSections()[0].setBlockState(0,0,0,Blocks.STONE.defaultBlockState());
        assertThrows(SurvivalRefusal.class,()->new ClientboundLevelChunkPacketData(chunk),"Carrier mutation cannot silently become serialized terrain");
        assertSame(Blocks.STONE.defaultBlockState(),chunk.getBlockState(TARGET));
    }
    @Test void admittedEmptyCarrierCanSerializeDuringClosingAndAfterDetachUntilReleased()throws Exception {
        var f=fixture();var chunk=carrier(f,false);f.binding.admitCarrier(chunk);f.binding.beginClose();
        assertThrows(SurvivalRefusal.class,()->f.level.getBlockState(TARGET));
        assertDoesNotThrow(()->new ClientboundLevelChunkPacketData(chunk));
        assertThrows(SurvivalRefusal.class,()->f.binding.admitCarrier(carrier(f,false)));
        ((SurvivalLevelAccess)f.level).earthplatform$unbindSurvival(f.binding);
        assertDoesNotThrow(()->new ClientboundLevelChunkPacketData(chunk));
        assertThrows(SurvivalRefusal.class,chunk::getSections);
        f.binding.releaseCarrier(chunk);
        assertThrows(SurvivalRefusal.class,()->new ClientboundLevelChunkPacketData(chunk));
    }
    @Test void vanillaCarrierTerrainTickRequiresAnExplicitPolicyBeforeSectionScan()throws Exception {
        var f=fixture();var chunk=carrier(f,false);f.binding.admitCarrier(chunk);
        assertThrows(SurvivalRefusal.class,()->f.level.tickChunk(chunk,3));
        var called=new AtomicInteger();
        var admitted=fixture((a,c,s,flags,d)->{throw new SurvivalRefusal("no fixture writes");},(a,e)->r->{},
                (binding,carrier,speed)->{assertEquals(3,speed);assertSame(binding.level(),carrier.getLevel());called.incrementAndGet();});
        var transport=carrier(admitted,false);admitted.binding.admitCarrier(transport);
        assertDoesNotThrow(()->admitted.level.tickChunk(transport,3));assertEquals(1,called.get());
        assertThrows(SurvivalRefusal.class,()->admitted.level.tickChunk(transport,-1));
    }
    @Test void levelBindingEnforcesIdentityThreadAndPermanentDetachFence()throws Exception {
        var f=fixture();f.admit(TARGET,Blocks.STONE.defaultBlockState());var other=fixture();
        assertThrows(SurvivalRefusal.class,()->((SurvivalLevelAccess)other.level).earthplatform$bindSurvival(f.binding));
        assertThrows(SurvivalRefusal.class,()->((SurvivalLevelAccess)f.level).earthplatform$unbindSurvival(other.binding));
        var failure=new AtomicReference<Throwable>();var thread=new Thread(()->{try{f.level.getBlockState(TARGET);}catch(Throwable t){failure.set(t);}});thread.start();thread.join();
        assertInstanceOf(SurvivalRefusal.class,failure.get());
        f.binding.beginClose();((SurvivalLevelAccess)f.level).earthplatform$unbindSurvival(f.binding);assertTrue(f.binding.isDetached());
        assertThrows(SurvivalRefusal.class,()->f.level.getBlockState(TARGET));
        assertThrows(SurvivalRefusal.class,()->((SurvivalLevelAccess)f.level).earthplatform$bindSurvival(other.binding));
    }
    @Test void closingRetainsDirtyCheckpointPinAndEffectOwners()throws Exception {
        var f=fixture();f.admit(TARGET,Blocks.STONE.defaultBlockState());
        try(var action=f.begin(1)){action.set(CubeCell.of(TARGET.getX(),TARGET.getY(),TARGET.getZ()),Blocks.AIR.defaultBlockState());action.commit();}
        var pin=f.world.pin(cube(TARGET));f.binding.beginClose();
        var access=(SurvivalLevelAccess)f.level;assertThrows(SurvivalRefusal.class,()->access.earthplatform$unbindSurvival(f.binding));
        var ticket=f.world.freezeCheckpoint(f.world.dirtyPositions());
        assertThrows(SurvivalRefusal.class,()->access.earthplatform$unbindSurvival(f.binding));
        f.world.acknowledgeCheckpoint(ticket);assertThrows(SurvivalRefusal.class,()->access.earthplatform$unbindSurvival(f.binding));
        pin.close();access.earthplatform$unbindSurvival(f.binding);assertTrue(f.binding.isDetached());
        var effects=fixture((a,c,s,flags,d)->a.set(c,s),(a,e)->r->{throw new IllegalStateException("delivery failed");});effects.admit(TARGET,Blocks.STONE.defaultBlockState());
        try(var action=effects.begin(1)){action.stage(new NativeEffectCodec.GameEvent(ResourceLocation.withDefaultNamespace("block_change"),CubeCell.of(TARGET.getX(),TARGET.getY(),TARGET.getZ()),ACTOR));assertThrows(SurvivalRefusal.class,action::commit);}
        assertThrows(SurvivalRefusal.class,()->effects.level.getBlockState(TARGET));
        assertThrows(SurvivalRefusal.class,effects.binding::view);
        effects.world.acknowledgeCheckpoint(effects.world.freezeCheckpoint(java.util.Set.of()));effects.binding.beginClose();
        assertThrows(SurvivalRefusal.class,()->((SurvivalLevelAccess)effects.level).earthplatform$unbindSurvival(effects.binding));
    }
    @Test void evenAnEmptyPendingCheckpointAndActiveScopePreventUnbind()throws Exception {
        var f=fixture();var ticket=f.world.freezeCheckpoint(java.util.Set.of());f.binding.beginClose();
        assertThrows(SurvivalRefusal.class,()->((SurvivalLevelAccess)f.level).earthplatform$unbindSurvival(f.binding));
        f.world.acknowledgeCheckpoint(ticket);((SurvivalLevelAccess)f.level).earthplatform$unbindSurvival(f.binding);
        var active=fixture();active.admit(TARGET,Blocks.STONE.defaultBlockState());
        try(var action=active.begin(1)){active.binding.beginClose();assertThrows(SurvivalRefusal.class,()->((SurvivalLevelAccess)active.level).earthplatform$unbindSurvival(active.binding));}
        ((SurvivalLevelAccess)active.level).earthplatform$unbindSurvival(active.binding);
    }
    private static void requireRetainedGetterFenced(SurvivalBlockView view) {
        assertThrows(SurvivalRefusal.class,()->view.getBlockState(TARGET));
        assertThrows(SurvivalRefusal.class,()->view.getFluidState(TARGET));
        assertThrows(SurvivalRefusal.class,()->view.getBlockEntity(TARGET));
        assertThrows(SurvivalRefusal.class,()->view.isResident(TARGET));
        assertThrows(SurvivalRefusal.class,()->view.hasResidentBox(TARGET.getX(),TARGET.getY(),TARGET.getZ(),TARGET.getX(),TARGET.getY(),TARGET.getZ()));
        assertThrows(SurvivalRefusal.class,view::getMinBuildHeight);
        assertThrows(SurvivalRefusal.class,view::getHeight);
        assertThrows(SurvivalRefusal.class,view::world);
    }
    @Test void retainedCollisionViewRevokesDuringUnpermittedApplyingAndRestoresAfterSuccess()throws Exception {
        var retained=new AtomicReference<SurvivalBlockView>();
        var f=fixture((a,c,s,flags,d)->a.set(c,s),(a,e)->r->requireRetainedGetterFenced(retained.get()));
        f.admit(TARGET,Blocks.STONE.defaultBlockState());retained.set(f.binding.view());
        assertSame(retained.get(),f.level.getChunkForCollisions(TARGET.getX()>>4,TARGET.getZ()>>4));
        try(var action=f.begin(1)){action.commit();}
        assertSame(Blocks.STONE.defaultBlockState(),retained.get().getBlockState(TARGET));
        assertTrue(retained.get().isResident(TARGET));
    }
    @Test void retainedCollisionViewCannotEscapeFailedDeliveryOrLaterClosing()throws Exception {
        var retained=new AtomicReference<SurvivalBlockView>();
        var f=fixture((a,c,s,flags,d)->a.set(c,s),(a,e)->r->{
            requireRetainedGetterFenced(retained.get());throw new IllegalStateException("injected retained owner failure");
        });
        f.admit(TARGET,Blocks.STONE.defaultBlockState());retained.set(f.binding.view());
        try(var action=f.begin(1)){assertThrows(SurvivalRefusal.class,action::commit);}
        requireRetainedGetterFenced(retained.get());f.binding.beginClose();requireRetainedGetterFenced(retained.get());
        assertTrue(f.actions.pendingDelivery().hasRetainedPreparedBatch());
    }
    @Test void retainedCollisionViewRevokesAtBeginCloseAndAfterPhysicalDetach()throws Exception {
        var f=fixture();f.admit(TARGET,Blocks.STONE.defaultBlockState());var retained=f.binding.view();
        f.binding.beginClose();requireRetainedGetterFenced(retained);
        ((SurvivalLevelAccess)f.level).earthplatform$unbindSurvival(f.binding);
        requireRetainedGetterFenced(retained);
    }

    /** Actual transformed classes, with read-only native ownership fields populated off-server. */
    private record SaveFixture(Fixture fixture, net.minecraft.server.level.ChunkMap map,
            net.minecraft.server.level.ChunkHolder holder, net.minecraft.world.level.chunk.ChunkAccess chunk,
            java.util.concurrent.atomic.AtomicReferenceArray<java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>>> futures) {
        void admit() {
            var pos=chunk.getPos();
            fixture.binding.admitCarrierSerialization(pos.x,pos.z,new SurvivalCarrierSerializationRoster.PhysicalAdmission(
                    1,chunk instanceof LevelChunk || chunk instanceof net.minecraft.world.level.chunk.ImposterProtoChunk?1:0,
                    SurvivalCarrierSerializationRoster.chargedMetadataBytes(1)));
        }
    }
    private static Object allocate(Class<?> type)throws Exception {
        var unsafe=Class.forName("sun.misc.Unsafe");var field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type);
    }
    private static void raw(Class<?> owner,Object target,String name,Object value)throws Exception {
        var field=owner.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static SaveFixture saveFixture(boolean full,boolean wrapper)throws Exception {
        var f=fixture();
        var structures=new MappedRegistry<net.minecraft.world.level.levelgen.structure.Structure>(Registries.STRUCTURE,Lifecycle.stable());structures.freeze();
        var saveRegistries=new RegistryAccess.ImmutableRegistryAccess(List.of(registries.registryOrThrow(Registries.BIOME),structures)).freeze();
        set(f.level,"registryAccess",saveRegistries);
        var data=java.lang.reflect.Proxy.newProxyInstance(SurvivalLevelHooksTest.class.getClassLoader(),
                new Class<?>[]{net.minecraft.world.level.storage.WritableLevelData.class},(proxy,method,args)-> {
                    if(method.getReturnType()==long.class)return 0L;
                    if(method.getReturnType()==boolean.class)return false;
                    if(method.getReturnType()==int.class)return 0;
                    if(method.getReturnType()==float.class)return 0F;
                    return null;
                });
        set(f.level,"levelData",data);
        var server=(net.minecraft.gametest.framework.GameTestServer)allocate(net.minecraft.gametest.framework.GameTestServer.class);
        raw(net.minecraft.server.MinecraftServer.class,server,"storageSource",saveAccess);
        raw(net.minecraft.server.MinecraftServer.class,server,"registries",new net.minecraft.core.LayeredRegistryAccess<>(List.of("save-test")).replaceFrom("save-test",saveRegistries));
        raw(net.minecraft.server.MinecraftServer.class,server,"resources",new net.minecraft.server.MinecraftServer.ReloadableResources(null,null));
        raw(ServerLevel.class,f.level,"server",server);
        var light=(net.minecraft.server.level.ThreadedLevelLightEngine)allocate(net.minecraft.server.level.ThreadedLevelLightEngine.class);
        raw(net.minecraft.world.level.lighting.LevelLightEngine.class,light,"levelHeightAccessor",f.level);
        var map=(net.minecraft.server.level.ChunkMap)allocate(net.minecraft.server.level.ChunkMap.class);
        for(String name:List.of("updatingChunkMap","visibleChunkMap","pendingUnloads"))
            raw(net.minecraft.server.level.ChunkMap.class,map,name,new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<net.minecraft.server.level.ChunkHolder>());
        var source=(net.minecraft.server.level.ServerChunkCache)allocate(net.minecraft.server.level.ServerChunkCache.class);
        raw(net.minecraft.server.level.ServerChunkCache.class,source,"level",f.level);
        raw(net.minecraft.server.level.ServerChunkCache.class,source,"chunkMap",map);
        raw(net.minecraft.server.level.ServerChunkCache.class,source,"lightEngine",light);
        raw(ServerLevel.class,f.level,"chunkSource",source);
        net.minecraft.world.level.chunk.ChunkAccess chunk;
        if(full){var carrier=carrier(f,false);f.binding.admitCarrier(carrier);chunk=wrapper?new net.minecraft.world.level.chunk.ImposterProtoChunk(carrier,false):carrier;}
        else {
            var proto=new net.minecraft.world.level.chunk.ProtoChunk(new ChunkPos(TARGET),net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                    f.level,saveRegistries.registryOrThrow(Registries.BIOME),null);
            proto.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS);chunk=proto;
        }
        var holder=(net.minecraft.server.level.ChunkHolder)allocate(net.minecraft.server.level.ChunkHolder.class);
        var status=chunk.getPersistedStatus();
        var futures=new java.util.concurrent.atomic.AtomicReferenceArray<java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>>>(net.minecraft.world.level.chunk.status.ChunkStatus.getStatusList().size());
        futures.set(status.getIndex(),java.util.concurrent.CompletableFuture.completedFuture(net.minecraft.server.level.ChunkResult.of(chunk)));
        raw(net.minecraft.server.level.GenerationChunkHolder.class,holder,"pos",chunk.getPos());
        raw(net.minecraft.server.level.GenerationChunkHolder.class,holder,"startedWork",new AtomicReference<>(status));
        raw(net.minecraft.server.level.GenerationChunkHolder.class,holder,"futures",futures);
        raw(net.minecraft.server.level.GenerationChunkHolder.class,holder,"generationRefCount",new AtomicInteger());
        raw(net.minecraft.server.level.ChunkHolder.class,holder,"saveSync",java.util.concurrent.CompletableFuture.completedFuture(null));
        var access=(io.earthplatform.minecraft.mixin.SurvivalCarrierChunkMapAccess)map;
        access.earthplatform$carrierUpdating().put(chunk.getPos().toLong(),holder);
        access.earthplatform$carrierVisible().put(chunk.getPos().toLong(),holder);
        assertInstanceOf(SurvivalCarrierGenerationLineageAccess.class,holder);
        return new SaveFixture(f,map,holder,chunk,futures);
    }
    @Test void actualProtoSerializerPreservesNativeStatusLightAndEmptyPayloadThroughTombstone()throws Exception {
        var f=saveFixture(false,false);f.chunk.setLightCorrect(true);f.admit();
        for(int phase=0;phase<3;phase++) {
            var tag=net.minecraft.world.level.chunk.storage.ChunkSerializer.write(f.fixture.level,f.chunk);
            assertEquals("minecraft:structure_starts",tag.getString("Status"));assertTrue(tag.getBoolean("isLightOn"));
            assertEquals(24,tag.getList("sections",10).size());assertTrue(tag.getList("entities",10).isEmpty());
            assertTrue(tag.getList("block_ticks",10).isEmpty());assertTrue(tag.getList("fluid_ticks",10).isEmpty());
            assertThrows(SurvivalRefusal.class,f.chunk::getSections);assertThrows(SurvivalRefusal.class,f.chunk::getHeightmaps);
            if(phase==0)f.fixture.binding.beginClose();
            if(phase==1)((SurvivalLevelAccess)f.fixture.level).earthplatform$unbindSurvival(f.fixture.binding);
        }
        assertThrows(SurvivalRefusal.class,f::admit);
    }
    @Test void serializerHeadRefusesUnknownSamePositionBeforeNativeWriteAccess()throws Exception {
        var f=saveFixture(false,false);f.admit();
        var unknown=new net.minecraft.world.level.chunk.ProtoChunk(f.chunk.getPos(),net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                f.fixture.level,registries.registryOrThrow(Registries.BIOME),null);
        set(f.fixture.level,"levelData",null); // A missing HEAD guard would fail natively with NPE.
        assertThrows(SurvivalRefusal.class,()->net.minecraft.world.level.chunk.storage.ChunkSerializer.write(f.fixture.level,unknown));
    }
    @Test void holderReplacementAndAttemptedAbaRemainFenced()throws Exception {
        var f=saveFixture(false,false);f.admit();var replacement=saveFixture(false,false);
        var map=(io.earthplatform.minecraft.mixin.SurvivalCarrierChunkMapAccess)f.map;
        map.earthplatform$carrierUpdating().put(f.chunk.getPos().toLong(),replacement.holder);
        assertThrows(SurvivalRefusal.class,()->SurvivalCarrierTransport.validateWrite(f.fixture.level,f.chunk));
        map.earthplatform$carrierUpdating().put(f.chunk.getPos().toLong(),f.holder);
        assertThrows(SurvivalRefusal.class,()->SurvivalCarrierTransport.validateWrite(f.fixture.level,f.chunk));
    }
    @Test void currentReadyReplacementAndSameChunkNewFutureCannotBorrowLineage()throws Exception {
        for(boolean replaceChunk:new boolean[]{false,true}) {
            var f=saveFixture(false,false);f.admit();int index=f.chunk.getPersistedStatus().getIndex();var prior=f.futures.get(index);
            var replacement=replaceChunk?saveFixture(false,false).chunk:f.chunk;
            f.futures.set(index,java.util.concurrent.CompletableFuture.completedFuture(net.minecraft.server.level.ChunkResult.of(replacement)));
            assertThrows(SurvivalRefusal.class,()->SurvivalCarrierTransport.validateWrite(f.fixture.level,replacement));
            f.futures.set(index,prior);
            assertThrows(SurvivalRefusal.class,()->SurvivalCarrierTransport.validateWrite(f.fixture.level,f.chunk));
        }
    }
    @Test void exactPinnedWrapperAndFullChunkWorkButNewWrapperCannotBorrowAdmission()throws Exception {
        var f=saveFixture(true,true);f.admit();var wrapper=(net.minecraft.world.level.chunk.ImposterProtoChunk)f.chunk;
        for(int phase=0;phase<3;phase++) {
            var wrapperTag=net.minecraft.world.level.chunk.storage.ChunkSerializer.write(f.fixture.level,wrapper);
            var fullTag=net.minecraft.world.level.chunk.storage.ChunkSerializer.write(f.fixture.level,wrapper.getWrapped());
            assertTrue(wrapperTag.getCompound("Heightmaps").isEmpty(),"Native wrapper owns its own empty heightmaps");
            assertFalse(fullTag.getCompound("Heightmaps").isEmpty(),"Native full owner retains its heightmap metadata");
            if(phase==0)f.fixture.binding.beginClose();
            if(phase==1)((SurvivalLevelAccess)f.fixture.level).earthplatform$unbindSurvival(f.fixture.binding);
        }
        assertThrows(SurvivalRefusal.class,()->SurvivalCarrierTransport.validateWrite(f.fixture.level,
                new net.minecraft.world.level.chunk.ImposterProtoChunk(wrapper.getWrapped(),false)));
    }
    @Test void serializerScopeCannotBecomeForeignLevelReceiverOrPacketAuthority()throws Exception {
        var f=saveFixture(false,false);f.admit();var other=saveFixture(false,false);
        assertThrows(SurvivalRefusal.class,()->SurvivalCarrierTransport.serializationSections(f.fixture.level,other.chunk,f.chunk));
        assertThrows(SurvivalRefusal.class,()->SurvivalCarrierTransport.validateWrite(other.fixture.level,f.chunk));
        assertThrows(SurvivalRefusal.class,()->SurvivalCarrierTransport.sections(f.chunk));
        var unadmittedFull=carrier(f.fixture,false);
        assertThrows(SurvivalRefusal.class,()->new ClientboundLevelChunkPacketData(unadmittedFull));
    }
    @Test void nativeUnloadCanUseRetainedExactHolderAfterMapRemoval()throws Exception {
        var f=saveFixture(false,false);f.admit();var maps=(io.earthplatform.minecraft.mixin.SurvivalCarrierChunkMapAccess)f.map;
        maps.earthplatform$carrierUpdating().clear();maps.earthplatform$carrierVisible().clear();
        f.fixture.binding.beginClose();((SurvivalLevelAccess)f.fixture.level).earthplatform$unbindSurvival(f.fixture.binding);
        assertDoesNotThrow(()->net.minecraft.world.level.chunk.storage.ChunkSerializer.write(f.fixture.level,f.chunk));
    }
    @Test void malformedProtoPayloadNeverSerializes()throws Exception {
        for(int payload=0;payload<10;payload++) {
            var f=saveFixture(false,false);f.admit();var proto=(net.minecraft.world.level.chunk.ProtoChunk)f.chunk;
            switch(payload) {
                case 0 -> ((SurvivalCarrierChunkAccess)proto).earthplatform$carrierSections()[0].setBlockState(0,0,0,Blocks.STONE.defaultBlockState());
                case 1 -> proto.addEntity(new net.minecraft.nbt.CompoundTag());
                case 2 -> proto.getBlockTicks().schedule(new net.minecraft.world.ticks.ScheduledTick<>(Blocks.STONE,new BlockPos(TARGET),1L,1L));
                case 3 -> proto.getFluidTicks().schedule(new net.minecraft.world.ticks.ScheduledTick<>(net.minecraft.world.level.material.Fluids.WATER,new BlockPos(TARGET),1L,1L));
                case 4 -> proto.setBlockEntityNbt(new net.minecraft.nbt.CompoundTag());
                case 5 -> proto.setCarvingMask(net.minecraft.world.level.levelgen.GenerationStep.Carving.AIR,new net.minecraft.world.level.chunk.CarvingMask(384,-64));
                case 6 -> {
                    var field=net.minecraft.world.level.chunk.ChunkAccess.class.getDeclaredField("postProcessing");field.setAccessible(true);
                    ((it.unimi.dsi.fastutil.shorts.ShortList[])field.get(proto))[0]=new it.unimi.dsi.fastutil.shorts.ShortArrayList(new short[]{1});
                }
                case 7 -> {
                    var heightmap=new Heightmap(proto,Heightmap.Types.WORLD_SURFACE_WG);heightmap.getRawData()[0]=1;
                    ((SurvivalCarrierChunkAccess)proto).earthplatform$carrierHeightmaps().put(Heightmap.Types.WORLD_SURFACE_WG,heightmap);
                }
                case 8 -> proto.setStartForStructure(null,net.minecraft.world.level.levelgen.structure.StructureStart.INVALID_START);
                case 9 -> proto.addReferenceForStructure(null,1L);
                default -> throw new AssertionError();
            }
            assertThrows(SurvivalRefusal.class,()->net.minecraft.world.level.chunk.storage.ChunkSerializer.write(f.fixture.level,proto),"payload="+payload);
        }
    }
    @Test void serializationBudgetAndPhysicalCensusAreIndependentOfCubicOwnership()throws Exception {
        assertThrows(SurvivalRefusal.class,()->new SurvivalCarrierSerializationRoster.PhysicalAdmission(1090,1,1));
        assertThrows(SurvivalRefusal.class,()->new SurvivalCarrierSerializationRoster.PhysicalAdmission(1,65,1));
        assertThrows(SurvivalRefusal.class,()->new SurvivalCarrierSerializationRoster.PhysicalAdmission(1,0,0));
        var f=saveFixture(false,false);int cubes=f.fixture.world.ownedPositions().size();
        assertThrows(SurvivalRefusal.class,()->f.fixture.binding.admitCarrierSerialization(f.chunk.getPos().x,f.chunk.getPos().z,
                new SurvivalCarrierSerializationRoster.PhysicalAdmission(2,0,SurvivalCarrierSerializationRoster.chargedMetadataBytes(2))));
        f.admit();assertEquals(cubes,f.fixture.world.ownedPositions().size());
        var usage=f.fixture.binding.carrierSerializationUsage();assertEquals(1,usage.nativeHolderOwners());assertEquals(0,usage.nativeFullOwners());
        assertTrue(usage.chargedMetadataBytes()<=usage.maximumMetadataBytes());
    }

    @Test void auxiliaryLightAndPendingPostLoadCannotAcquireSaveAuthority()throws Exception {
        for(boolean pending:new boolean[]{false,true}) {
            var f=saveFixture(true,false);f.admit();var full=(LevelChunk)f.chunk;
            if(pending)raw(LevelChunk.class,full,"postLoad",(LevelChunk.PostLoadProcessor)ignored->{throw new AssertionError("Must not run postLoad");});
            else ((io.earthplatform.minecraft.mixin.SurvivalCarrierAuxLightAccess)(Object)
                    ((SurvivalCarrierLevelAccess)full).earthplatform$carrierAuxLight()).earthplatform$carrierAuxLights().put(TARGET,(byte)1);
            assertThrows(SurvivalRefusal.class,()->net.minecraft.world.level.chunk.storage.ChunkSerializer.write(f.fixture.level,full));
        }
    }
    @Test void ordinaryLevelCannotBorrowBoundChunkAndOrdinarySerializerStaysNative()throws Exception {
        var bound=saveFixture(false,false);bound.admit();
        var ordinary=(ServerLevel)allocate(ServerLevel.class);set(ordinary,"thread",Thread.currentThread());
        set(ordinary,"dimension",Level.OVERWORLD);
        raw(ServerLevel.class,ordinary,"server",bound.fixture.level.getServer());
        assertNull(SurvivalLevelBinding.find(ordinary));
        assertThrows(SurvivalRefusal.class,()->net.minecraft.world.level.chunk.storage.ChunkSerializer.write(ordinary,bound.chunk));
        // An ordinary chunk whose raw owner is ordinary remains on the native path, with no roster.
        set(ordinary,"dimensionTypeRegistration",dimension);set(ordinary,"registryAccess",registries);
        var proto=new net.minecraft.world.level.chunk.ProtoChunk(new ChunkPos(TARGET),net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                ordinary,registries.registryOrThrow(Registries.BIOME),null);
        assertDoesNotThrow(()->SurvivalCarrierTransport.validateWrite(ordinary,proto));
        assertSame(proto.getSections(),SurvivalCarrierTransport.serializationSections(ordinary,proto,proto));
    }

    @Test void pendingExceptionalAndPromotedLineagesFailClosed()throws Exception {
        for(int changed=0;changed<3;changed++) {
            var f=saveFixture(false,false);f.admit();int index=f.chunk.getPersistedStatus().getIndex();
            if(changed==0)f.futures.set(index,new java.util.concurrent.CompletableFuture<>());
            else if(changed==1)f.futures.set(index,java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("native generation failed")));
            else ((net.minecraft.world.level.chunk.ProtoChunk)f.chunk).setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_REFERENCES);
            assertThrows(RuntimeException.class,()->SurvivalCarrierTransport.validateWrite(f.fixture.level,f.chunk));
        }
    }
    @Test void outOfHaloAndConflictingPhysicalHolderViewsAreRefusedBeforeAdmission()throws Exception {
        var f=saveFixture(false,false);
        assertThrows(SurvivalRefusal.class,()->f.fixture.binding.admitCarrierSerialization(f.chunk.getPos().x+17,f.chunk.getPos().z,
                new SurvivalCarrierSerializationRoster.PhysicalAdmission(1,0,SurvivalCarrierSerializationRoster.chargedMetadataBytes(1))));
        var other=saveFixture(false,false);
        ((io.earthplatform.minecraft.mixin.SurvivalCarrierChunkMapAccess)f.map).earthplatform$carrierPendingUnloads().put(f.chunk.getPos().toLong(),other.holder);
        assertThrows(SurvivalRefusal.class,f::admit);
    }

    @Test void samePositionForeignRawLevelCannotPassPhysicalPreAdmission()throws Exception {
        var f=saveFixture(false,false);var foreign=saveFixture(false,false);
        assertEquals(f.chunk.getPos(),foreign.chunk.getPos());
        f.futures.set(f.chunk.getPersistedStatus().getIndex(),java.util.concurrent.CompletableFuture.completedFuture(
                net.minecraft.server.level.ChunkResult.of(foreign.chunk)));
        assertThrows(SurvivalRefusal.class,f::admit);
    }

    @Test void nonzeroWrapperOwnedHeightmapIsRefusedBeforeNativeWrite()throws Exception {
        var f=saveFixture(true,true);f.admit();
        var map=new Heightmap(f.chunk,Heightmap.Types.WORLD_SURFACE);map.getRawData()[0]=1;
        ((SurvivalCarrierChunkAccess)f.chunk).earthplatform$carrierHeightmaps().put(Heightmap.Types.WORLD_SURFACE,map);
        assertThrows(SurvivalRefusal.class,()->net.minecraft.world.level.chunk.storage.ChunkSerializer.write(f.fixture.level,f.chunk));
    }
}
