package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.fixture.*;
import io.earthplatform.minecraft.network.*;
import io.earthplatform.minecraft.residency.*;
import io.earthplatform.streaming.CubeStreamAdmission;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Codec/admission/native-owner composition only. Actual ClientLevel is verified by the native probe. */
class NativeSparseBridgeTest {
    private static EnginePaletteContext palette;
    private static RegistryAccess registries;
    private static Holder<DimensionType> dimension;
    private static final WorldIdentity WORLD=new WorldIdentity(0,"fixture","v1","synthetic","test-palette");
    private String oldSparse,oldNative;
    @BeforeAll static void registries() {
        var lookup=VanillaRegistries.createLookup();
        var biomes=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(h->biomes.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));
        var damage=new MappedRegistry<net.minecraft.world.damagesource.DamageType>(Registries.DAMAGE_TYPE,Lifecycle.stable());
        lookup.lookupOrThrow(Registries.DAMAGE_TYPE).listElements().forEach(h->damage.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));
        registries=new RegistryAccess.ImmutableRegistryAccess(List.of(biomes.freeze(),damage.freeze())).freeze();
        palette=EnginePaletteContext.create(biomes);
        dimension=lookup.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);
    }
    @BeforeEach void enable() {
        oldSparse=System.getProperty(SparseCubeStorage.OPT_IN);oldNative=System.getProperty(SparseFixtureDimensions.OPT_IN);
        System.setProperty(SparseCubeStorage.OPT_IN,"true");System.setProperty(SparseFixtureDimensions.OPT_IN,"true");
    }
    @AfterEach void restore() { restore(SparseCubeStorage.OPT_IN,oldSparse);restore(SparseFixtureDimensions.OPT_IN,oldNative); }
    private static void restore(String key,String value) { if(value==null)System.clearProperty(key);else System.setProperty(key,value); }
    private static SparseCubeStorage storage() { return new SparseCubeStorage(palette,-11008,19872,2,2,Set.of()); }
    private static EngineCubeSnapshot snapshot(CubePos pos,long lease,long revision,net.minecraft.world.level.block.state.BlockState state) {
        return EngineCubeSnapshot.capture(pos,WORLD,1,lease,revision,EngineCubeSection.uniform(palette,state));
    }
    private static CubeStreamAdmission.Header header(EngineCubeSnapshot s) {
        return new CubeStreamAdmission.Header(s.world(),s.epoch(),s.position(),s.leaseGeneration(),s.revision());
    }
    private static void install(CubeStreamAdmission gate,SparseCubeStorage owner,EngineCubeSnapshot s) {
        var admission=gate.admit(header(s));assertTrue(admission.accepted(),admission.rejection().toString());
        assertEquals(CubeStreamAdmission.Rejection.NONE,gate.commit(admission.token(),()->owner.installAdmittedSnapshot(s)));
    }
    @Test void actualNativeOwnersAcceptOnlyCurrentLeaseAndKeepBothNativeYExtremes() {
        var owner=storage();var low=NativeSparseBinding.LOWER;var high=NativeSparseBinding.UPPER;
        var gate=new CubeStreamAdmission(WORLD,1,2);
        try {
            gate.replaceInterest(List.of(low,high));
            var a=snapshot(low,1,4,Blocks.GOLD_BLOCK.defaultBlockState());var b=snapshot(high,2,9,Blocks.DIAMOND_BLOCK.defaultBlockState());
            install(gate,owner,a);install(gate,owner,b);
            assertSame(Blocks.GOLD_BLOCK.defaultBlockState(),owner.get(new ChunkPos(0,0),NativeProbeChecks.LOWER_BLOCK));
            assertSame(Blocks.DIAMOND_BLOCK.defaultBlockState(),owner.get(new ChunkPos(0,0),NativeProbeChecks.UPPER_BLOCK));
            assertEquals(CubeStreamAdmission.Rejection.STALE_REVISION,gate.admit(header(a)).rejection());
            gate.replaceInterest(List.of(high));owner.retire(low);
            assertThrows(SparseFacadeException.class,()->owner.get(new ChunkPos(0,0),NativeProbeChecks.LOWER_BLOCK));
            gate.replaceInterest(List.of(low,high));
            assertEquals(CubeStreamAdmission.Rejection.RETIRED_LEASE,gate.admit(header(a)).rejection());
            install(gate,owner,snapshot(low,3,4,Blocks.GOLD_BLOCK.defaultBlockState()));
            assertEquals(2,owner.residentCount());assertEquals(0,owner.retiredCount());assertEquals(4,owner.revision(low));
        } finally { gate.close(); }
    }
    @Test void unsupportedSnapshotCannotPublishAndPoisonsAdmission() {
        var owner=storage();var low=NativeSparseBinding.LOWER;
        var gate=new CubeStreamAdmission(WORLD,1,2);
        try {
            gate.replaceInterest(List.of(low));
            var invalid=snapshot(low,1,1,Blocks.WATER.defaultBlockState());var token=gate.admit(header(invalid)).token();
            assertThrows(SparseFacadeException.class,()->gate.commit(token,()->owner.installAdmittedSnapshot(invalid)));
            assertEquals(0,owner.residentCount());assertEquals(SparseCubeStorage.Presence.UNKNOWN,owner.presence(low));
            assertFalse(gate.admit(header(snapshot(low,1,2,Blocks.STONE.defaultBlockState()))).accepted());
        } finally { gate.close(); }
    }
    @Test void retainedNewerEditsSurviveRejectedSnapshot() {
        var owner=storage();var low=NativeSparseBinding.LOWER;
        owner.installAdmittedSnapshot(snapshot(low,1,9,Blocks.GOLD_BLOCK.defaultBlockState()));owner.retire(low);
        assertThrows(SparseFacadeException.class,()->owner.installAdmittedSnapshot(snapshot(low,2,8,Blocks.AIR.defaultBlockState())));
        assertEquals(0,owner.residentCount());assertEquals(9,owner.revision(low));owner.reload(low);
        assertSame(Blocks.GOLD_BLOCK.defaultBlockState(),owner.get(new ChunkPos(0,0),NativeProbeChecks.LOWER_BLOCK));
    }
    @Test void payloadBoundsAndDefensiveCopies() {
        byte[] bytes={1,2,3};var p=new NativeProbePayload(1,bytes,bytes,bytes);bytes[0]=9;
        assertEquals(1,p.lower()[0]);var copy=p.upper();copy[0]=8;assertEquals(1,p.upper()[0]);
        assertThrows(IllegalArgumentException.class,()->new NativeProbePayload(0,bytes,bytes,bytes));
        assertThrows(IllegalArgumentException.class,()->new NativeProbePayload(1,new byte[0],bytes,bytes));
        assertThrows(IllegalArgumentException.class,()->new NativeProbePayload(1,new byte[NativeProbePayload.MAX_FRAME_BYTES+1],bytes,bytes));
    }
    private static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(),registries); }
    @Test void payloadCodecRejectsBombTruncationAndOuterTrailingBytes() {
        var payload=new NativeProbePayload(1,new byte[]{1},new byte[]{2},new byte[]{3});var wire=buffer();
        try {
            NativeProbePayload.CODEC.encode(wire,payload);byte[] encoded=new byte[wire.readableBytes()];wire.getBytes(0,encoded);
            var decoded=NativeProbePayload.CODEC.decode(wire);assertArrayEquals(payload.renewedLower(),decoded.renewedLower());
            for(int n=0;n<encoded.length;n++) {
                var truncated=buffer();try{truncated.writeBytes(encoded,0,n);assertThrows(RuntimeException.class,()->NativeProbePayload.CODEC.decode(truncated));}finally{truncated.release();}
            }
            var trailing=buffer();try{trailing.writeBytes(encoded).writeByte(0);assertThrows(io.netty.handler.codec.DecoderException.class,()->NativeProbePayload.CODEC.decode(trailing));}finally{trailing.release();}
            for(int size:new int[]{-1,Integer.MAX_VALUE,NativeProbePayload.MAX_FRAME_BYTES+1}) {
                var bomb=buffer();try{bomb.writeLong(1).writeVarInt(size);assertThrows(RuntimeException.class,()->NativeProbePayload.CODEC.decode(bomb));}finally{bomb.release();}
            }
        }finally{wire.release();}
    }
    @Test void constructorCapabilityRejectsFakeLevelsAndDoesNotLeakIntoOrdinaryWorlds() {
        try(var scope=SparseFixtureConstruction.open(SparseFixtureConstruction.Endpoint.SERVER,dimension.value())) {
            assertThrows(SparseFacadeException.class,()->SparseFixtureConstruction.open(SparseFixtureConstruction.Endpoint.SERVER,dimension.value()));
            assertThrows(SparseFacadeException.class,()->new FacadeTestLevel(registries,dimension,-64,384));
        }
        var ordinary=new FacadeTestLevel(registries,dimension,-64,384);
        assertNull(NativeSparseBinding.find(ordinary));assertNull(SparseFixtureConstruction.claim(ordinary));
        try(var scope=SparseFixtureConstruction.open(SparseFixtureConstruction.Endpoint.CLIENT,dimension.value())) {
            assertThrows(SparseFacadeException.class,()->scope.verify(ordinary));
        }
    }
    @Test void everyNativeHookAndShadowMatchesExactPinnedEngineDescriptors() throws Exception {
        Map<String,String> targets=Map.of(
                "NativeSparseLevelMixin","net/minecraft/world/level/Level",
                "NativeSparseServerCacheMixin","net/minecraft/server/level/ServerChunkCache",
                "NativeSparseServerLevelMixin","net/minecraft/server/level/ServerLevel",
                "NativeSparseClientCacheMixin","net/minecraft/client/multiplayer/ClientChunkCache",
                "NativeSparseClientLevelMixin","net/minecraft/client/multiplayer/ClientLevel");
        int hooks=0;
        try(var jar=new java.util.zip.ZipFile(System.getProperty("earthplatform.test.minecraftJar"))) {
            for(var entry:targets.entrySet()) {
                var target=new org.objectweb.asm.tree.ClassNode();
                try(var input=jar.getInputStream(jar.getEntry(entry.getValue()+".class"))) { new org.objectweb.asm.ClassReader(input).accept(target,0); }
                var mixin=new org.objectweb.asm.tree.ClassNode();
                try(var input=getClass().getResourceAsStream("/io/earthplatform/minecraft/mixin/"+entry.getKey()+".class")) { new org.objectweb.asm.ClassReader(input).accept(mixin,0); }
                for(var method:mixin.methods) {
                    if(method.visibleAnnotations==null)continue;
                    for(var annotation:method.visibleAnnotations) {
                        if(!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))continue;
                        for(int index=0;index<annotation.values.size();index+=2)if(annotation.values.get(index).equals("method")) {
                            for(Object raw:(List<?>)annotation.values.get(index+1)) {
                                String signature=(String)raw;
                                assertEquals(1,target.methods.stream().filter(m->(m.name+m.desc).equals(signature)).count(),entry.getKey()+" exact hook "+signature);
                                hooks++;
                            }
                        }
                    }
                }
                for(var field:mixin.fields) {
                    var annotations=field.visibleAnnotations;
                    if(annotations!=null && annotations.stream().anyMatch(a->a.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;")))
                        assertEquals(1,target.fields.stream().filter(f->f.name.equals(field.name)&&f.desc.equals(field.desc)).count(),entry.getKey()+" exact shadow "+field.name);
                }
            }
        }
        assertTrue(hooks>=25,"native hook annotations were inspected: "+hooks);
    }

}
