package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.enginebridge.NativeEarthCubeProvider;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.enginebridge.RollingEditPolicy;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent admission/store/wire/publication review. Does not call production canonical hashing. */
class RollingBoundaryPolicyIndependentReviewTest {
    static EnginePaletteContext palette;
    @BeforeAll static void registry() {
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(biomes.freeze());
    }
    private static RollingSessionDescriptor descriptor(CoreGateProfile profile, RollingEditPolicy policy) {
        return RollingSessionDescriptor.analytic(profile, palette, policy);
    }
    @Test void everyAnalyticPolicyChangesSessionAndPairButPreservesLegacySourceAndPortableStore() throws Exception {
        for (var profile : CoreGateProfile.values()) {
            var legacy = RollingSessionDescriptor.analytic(profile, palette);
            var full = descriptor(profile, RollingEditPolicy.FULL_CUBE_V1);
            assertSame(RollingEditPolicy.INTERIOR_V1, legacy.editPolicy());
            assertEquals(legacy.world(), full.world()); assertEquals(legacy.sourceIdentity(), full.sourceIdentity());
            assertEquals(legacy.sourceFingerprint(), full.sourceFingerprint());
            assertEquals(RollingGateFixture.provider(palette, profile).identity(), full.world());
            assertEquals(RollingGateFixture.directory(profile), full.saveDirectory());
            assertEquals(legacy.saveDirectory(), full.saveDirectory());
            assertEquals(RollingLegacyIdentityIndependentReviewTest.frozenV1StoreFingerprint(legacy,palette.fingerprint()),
                    RollingLegacyIdentityIndependentReviewTest.frozenV1StoreFingerprint(full,palette.fingerprint()));
            assertNotEquals(legacy.fingerprint(), full.fingerprint()); assertNotEquals(legacy, full);
            var versions = new ArrayList<RollingCubeActor.CubeVersion>(); long lease = 0;
            for (var p : RollingCubePlan.stationary(full.pinCenter(),full.pinCenter()).committedOwners())
                versions.add(new RollingCubeActor.CubeVersion(p,++lease,0));
            var ready = new RollingCubeActor.StageReady(full.world(),7,1,full.pinCenter(),versions);
            String interiorPair = RollingFarPublication.digest(legacy,ready), fullPair = RollingFarPublication.digest(full,ready);
            assertNotEquals(interiorPair, fullPair);
            assertEquals(RollingFarPublication.center(legacy,ready.candidateCenter()),RollingFarPublication.center(full,ready.candidateCenter()));
            var handshake = new RollingPublicationHandshake(7); assertEquals(1,handshake.begin()); handshake.ready(fullPair);
            assertFalse(handshake.prepared(7,1,3,interiorPair),"Policy substitution must fail before ALLOW");
            assertTrue(handshake.prepared(7,1,3,fullPair)); handshake.grant();
            assertFalse(handshake.pairReady(7,1,3,interiorPair),"Old policy digest cannot acknowledge granted pair");
            assertFalse(handshake.pairReady(7,1,4,fullPair));
            assertTrue(handshake.pairReady(7,1,3,fullPair)); handshake.committed();
            assertEquals(fullPair,handshake.committedDigest());
            assertEquals(213,RollingCubePlan.MAX_STAGED_OWNERS);assertEquals(55,RollingCubePlan.MAX_STAGED_MESH_GENERATIONS);
        }
    }
    @Test void explicitPolicyRoundTripsWhileUnknownOrSubstitutedPolicyBytesFailClosed() {
        for (var profile : CoreGateProfile.values()) for (var policy : RollingEditPolicy.values()) {
            var d = descriptor(profile,policy); var encoded = Unpooled.buffer(); var wire = new FriendlyByteBuf(encoded);
            try {
                d.write(wire); wire.writeLong(0x12345678L);
                var decoded = RollingSessionDescriptor.read(wire,d.world(),palette);
                assertEquals(d,decoded); assertSame(policy,decoded.editPolicy()); assertEquals(0x12345678L,wire.readLong());
                assertFalse(wire.isReadable());
            } finally { wire.release(); }
            for (String changed : List.of("unknown-v1", "FULL-CUBE-V1", "full-cube-v2",
                    policy == RollingEditPolicy.INTERIOR_V1 ? "full-cube-v1" : "interior-v1")) {
                var forged = policyWire(d,changed);
                try { assertThrows(IllegalArgumentException.class,()->RollingSessionDescriptor.read(forged,d.world(),palette),changed); }
                finally { forged.release(); }
            }
        }
    }
    @Test void earthPolicyCannotBeWidenedAndAnalyticOriginalCubeRemainsExact() {
        var world = new WorldIdentity(173,NativeEarthCubeProvider.GENERATOR_ID,
                NativeEarthCubeProvider.GENERATOR_VERSION+"/"+RollingGateFixture.VERSION,"a".repeat(64),NativeCubeCodec.paletteIdentity(palette));
        var earth = RollingSessionDescriptor.earth(world,palette,-8,7506,-24);
        assertSame(RollingEditPolicy.INTERIOR_V1,earth.editPolicy());
        var forged = policyWire(earth,"full-cube-v1");
        try { assertThrows(IllegalArgumentException.class,()->RollingSessionDescriptor.read(forged,world,palette)); }
        finally { forged.release(); }
        for (var profile : CoreGateProfile.values()) {
            var d = descriptor(profile,RollingEditPolicy.FULL_CUBE_V1); var p = d.pinCenter();
            int count = 0;
            for (int x=-1;x<=16;x++) for (int y=-1;y<=16;y++) for (int z=-1;z<=16;z++) {
                boolean expected = x>=0&&x<16&&y>=0&&y<16&&z>=0&&z<16;
                assertEquals(expected,d.permitsEdit(p.minBlockX()+x,p.minBlockY()+y,p.minBlockZ()+z));
                if (expected) count++;
            }
            assertEquals(4096,count);
            for (long value : new long[]{Long.MIN_VALUE,Long.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE}) {
                assertFalse(d.permitsEdit(value,p.minBlockY(),p.minBlockZ()));
                assertFalse(d.permitsEdit(p.minBlockX(),value,p.minBlockZ()));
                assertFalse(d.permitsEdit(p.minBlockX(),p.minBlockY(),value));
            }
        }
    }
    /** Re-encodes the observed wire schema with the original digests; never recomputes authority. */
    private static FriendlyByteBuf policyWire(RollingSessionDescriptor d,String policy) {
        var fields = new FriendlyByteBuf(Unpooled.buffer()); var wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CoreGatePayload.world(fields,d.world()); fields.writeUtf(d.profileKind()); fields.writeUtf(d.profileId()); fields.writeUtf(palette.fingerprint());
            CoreGatePayload.position(fields,d.pinCenter());CoreGatePayload.position(fields,d.initialCenter());
            fields.writeDouble(d.entry().x);fields.writeDouble(d.entry().y);fields.writeDouble(d.entry().z);
            fields.writeInt(d.anchorY());fields.writeLong(d.fixedFarCenterX());fields.writeLong(d.fixedFarCenterZ());fields.writeBoolean(d.naturalRelief());
            fields.writeUtf(d.lodLayout().id());fields.writeUtf(d.lodLayout().identity());fields.writeUtf(policy);
            var b=d.bodyEnvelope();fields.writeLong(b.minX());fields.writeLong(b.maxX());fields.writeInt(b.minY());fields.writeInt(b.maxY());fields.writeLong(b.minZ());fields.writeLong(b.maxZ());
            var w=d.windowEnvelope();fields.writeInt(w.minX());fields.writeInt(w.maxX());fields.writeInt(w.minY());fields.writeInt(w.maxY());fields.writeInt(w.minZ());fields.writeInt(w.maxZ());
            fields.writeUtf(d.sourceFingerprint());fields.writeUtf(d.fingerprint());
            wire.writeVarInt(RollingSessionDescriptor.WIRE_VERSION);wire.writeVarInt(fields.readableBytes());wire.writeBytes(fields);return wire;
        } finally { fields.release(); }
    }
    @Test void compiledServerAndClientBothConsultDescriptorAndActorReceivesItsExactPolicy() throws Exception {
        String base="io/earthplatform/minecraft/",desc=base+"coregate/RollingSessionDescriptor",actor=base+"enginebridge/RollingCubeActor";
        var server=methods(base+"coregate/RollingGateCommands"); var client=methods(base+"coregate/RollingGateClient$Session");
        assertTrue(server.stream().anyMatch(c->c.name.equals("pumpRay")&&c.calls.contains(desc+".permitsEdit")),"Server ray admission must use session policy");
        var begin=client.stream().filter(c->c.name.equals("beginEdit")).findFirst().orElseThrow();
        assertTrue(begin.calls.contains(desc+".permitsEdit"));assertFalse(begin.calls.contains(base+"enginebridge/RollingCubePlan.isEditableInterior"));
        var opening=server.stream().filter(c->c.calls.stream().anyMatch(x->x.startsWith(actor+"$ReplacementPolicy.<init>"))).findFirst().orElseThrow();
        assertTrue(opening.calls.contains(desc+".editPolicy"));
        assertTrue(opening.calls.stream().anyMatch(c->c.equals(actor+"$ReplacementPolicy.<init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JL"+base+"enginebridge/RollingEditPolicy;)V")));
        var enter=server.stream().filter(c->c.name.equals("enter")).findFirst().orElseThrow();
        assertTrue(enter.fields.contains(base+"enginebridge/RollingEditPolicy.FULL_CUBE_V1"));
        assertTrue(enter.fields.contains(base+"enginebridge/RollingEditPolicy.INTERIOR_V1"));
        assertTrue(enter.strings.contains(RollingGateFixture.BOUNDARY_OPT_IN));assertTrue(enter.strings.contains(RollingGateFixture.REPLACEMENT_OPT_IN));
    }
    private record Code(String name,List<String> calls,List<String> fields,List<String> strings){}
    private static List<Code> methods(String name)throws Exception {
        var result=new ArrayList<Code>();
        try(var in=RollingBoundaryPolicyIndependentReviewTest.class.getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(in);new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9){
                public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] exceptions){
                    var c=new Code(name,new ArrayList<>(),new ArrayList<>(),new ArrayList<>());result.add(c);
                    return new MethodVisitor(Opcodes.ASM9){
                        public void visitMethodInsn(int op,String owner,String name,String desc,boolean iface){c.calls.add(owner+"."+name);c.calls.add(owner+"."+name+desc);}
                        public void visitFieldInsn(int op,String owner,String name,String desc){c.fields.add(owner+"."+name);}
                        public void visitLdcInsn(Object value){if(value instanceof String s)c.strings.add(s);}
                    };}
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }return result;
    }
}
