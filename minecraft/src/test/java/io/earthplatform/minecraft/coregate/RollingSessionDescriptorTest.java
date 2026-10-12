package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.enginebridge.NativeEarthCubeProvider;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.enginebridge.RollingEditPolicy;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.lod.DhLodLayout;
import io.netty.buffer.Unpooled;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollingSessionDescriptorTest {
    private static EnginePaletteContext palette;
    private static WorldIdentity earthWorld;
    @BeforeAll static void registry() {
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(biomes.freeze());
        earthWorld = earthWorld("a".repeat(64), palette);
    }
    private static WorldIdentity earthWorld(String dataset, EnginePaletteContext p) {
        return new WorldIdentity(173, NativeEarthCubeProvider.GENERATOR_ID,
                NativeEarthCubeProvider.GENERATOR_VERSION + "/" + RollingGateFixture.VERSION,
                dataset, NativeCubeCodec.paletteIdentity(p));
    }
    private static RollingSessionDescriptor earth() { return RollingSessionDescriptor.earth(earthWorld, palette, 9674936, 7506, -3112472); }
    private static FriendlyByteBuf encoded(RollingSessionDescriptor descriptor) {
        var b = new FriendlyByteBuf(Unpooled.buffer()); descriptor.write(b); return b;
    }

    @Test void allAnalyticDescriptorsPreserveExactOriginalWorldStorePinEntryAndSafety() {
        var fingerprints = new HashSet<String>();
        for (var p : CoreGateProfile.values()) {
            var d = RollingSessionDescriptor.analytic(p, palette);
            assertEquals(RollingGateFixture.provider(palette, p).identity(), d.world());
            assertEquals(RollingGateFixture.directory(p), d.saveDirectory());
            assertEquals(p.center(), d.pinCenter()); assertEquals(p.center(), d.initialCenter());
            assertEquals(p.entry(), d.entry()); assertEquals(p.anchorY(), d.anchorY());
            assertEquals("analytic", d.profileKind()); assertFalse(d.naturalRelief());
            assertEquals(0, d.fixedFarCenterX()); assertEquals(0, d.fixedFarCenterZ());
            assertEquals(4096, d.fixedFarRadius()); assertSame(DhLodLayout.LONG_4096,d.lodLayout()); assertTrue(d.containsBody(p.walkEntryBody()));
            assertTrue(d.containsBody(new AABB(-512, -11008, -512, -511, -11006, -511)));
            assertTrue(d.containsBody(new AABB(511, 8862, 511, 512, 8864, 512)));
            for (var c : p.meshTargets()) assertEquals(p.distantMaskEligible(c), d.distantMaskEligible(c));
            fingerprints.add(d.fingerprint());
        }
        assertEquals(3, fingerprints.size());
    }

    @Test void earthUsesRealSignedLargeCoordinatesAndMeasuredGroundWithoutFiles() {
        var d = earth();
        assertEquals(earthWorld, d.world()); assertEquals(earthWorld, d.sourceIdentity());
        assertEquals(new CubePos(604683, 469, -194530), d.pinCenter());
        assertEquals(d.pinCenter(), d.initialCenter()); assertEquals(d.pinCenter(), d.center()); assertEquals(d.pinCenter(), d.target());
        assertEquals("earth", d.id()); assertEquals("earth", d.profileKind()); assertTrue(d.naturalRelief());
        assertEquals(9674936.5, d.entry().x); assertEquals(7509, d.entry().y); assertEquals(-3112471.5, d.entry().z);
        assertEquals(9674936, d.fixedFarCenterX()); assertEquals(-3112472, d.fixedFarCenterZ());
        assertSame(DhLodLayout.FIXED_512,d.lodLayout());assertEquals(512,d.fixedFarRadius());
        assertEquals(7506, d.anchorY()); assertTrue(d.containsBody(d.walkEntryBody()));
        assertEquals(64, d.sourceFingerprint().length()); assertEquals(64, d.fingerprint().length());
        assertTrue(d.saveDirectory().startsWith("earthplatform-earth-player-gate-v1/"));
        assertNotEquals(RollingGateFixture.directory(CoreGateProfile.BASE), d.saveDirectory());
    }

    @Test void entryCanBeInNextYCubeButInitialCenterStaysGroundPin() {
        var d = RollingSessionDescriptor.earth(earthWorld, palette, -8, 7502, -24);
        assertEquals(new CubePos(-1, 468, -2), d.pinCenter()); assertEquals(d.pinCenter(), d.initialCenter());
        assertEquals(469, CubePos.fromBlock(-8, (long)d.entry().y, -24).y());
        assertTrue(d.containsBody(d.walkEntryBody())); d.requireAllowedWindow(d.pinCenter().offset(0, 1, 0));
    }

    @Test void boundedWireRoundTripHasValueEqualityAndDoesNotConsumeOuterFields() {
        for (var d : new RollingSessionDescriptor[]{earth(), RollingSessionDescriptor.analytic(CoreGateProfile.BASE, palette),
                RollingSessionDescriptor.analytic(CoreGateProfile.ABYSS, palette), RollingSessionDescriptor.analytic(CoreGateProfile.SUMMIT, palette)}) {
            var b = encoded(d);
            try {
                assertTrue(b.readableBytes() < RollingSessionDescriptor.MAX_WIRE_BYTES);
                b.writeLong(99887766); var decoded = RollingSessionDescriptor.read(b, d.world(), palette);
                assertEquals(d, decoded); assertEquals(d.hashCode(), decoded.hashCode());
                assertEquals(d.fingerprint(), decoded.fingerprint()); assertEquals(99887766, b.readLong()); assertFalse(b.isReadable());
            } finally { b.release(); }
        }
    }

    @Test void fullEarthBodyAndEverySignedWindowStayInsideConservativeBounds() {
        var d = earth(); var body = d.bodyEnvelope(); var windows = d.windowEnvelope();
        assertEquals(-10976, body.minY()); assertEquals(8832, body.maxY());
        assertEquals(-686, windows.minY()); assertEquals(551, windows.maxY());
        assertTrue(d.containsBody(new AABB(body.minX(), body.minY(), body.minZ(), body.minX()+1, body.minY()+2, body.minZ()+1)));
        assertTrue(d.containsBody(new AABB(body.maxX()-1, body.maxY()-2, body.maxZ()-1, body.maxX(), body.maxY(), body.maxZ())));
        assertFalse(d.containsBody(new AABB(body.minX()-.01, 7500, body.minZ(), body.minX()+.59, 7501.8, body.minZ()+.6)));
        assertFalse(d.containsBody(new AABB(body.maxX()-.5, 7500, body.maxZ()-.6, body.maxX()+.1, 7501.8, body.maxZ())));
        assertFalse(d.containsBody(new AABB(body.minX(), body.minY()-.01, body.minZ(), body.minX()+.6, body.minY()+1.79, body.minZ()+.6)));
        assertFalse(d.containsBody(new AABB(body.minX(), body.maxY()-1.7, body.minZ(), body.minX()+.6, body.maxY()+.1, body.minZ()+.6)));
        for (int x : new int[]{windows.minX(), windows.maxX()}) for (int y : new int[]{windows.minY(), windows.maxY()})
            for (int z : new int[]{windows.minZ(), windows.maxZ()}) {
                var c = new CubePos(x, y, z); d.requireAllowedWindow(c);
                for (var owner : RollingCubePlan.stationary(d.pinCenter(), c).currentOwners()) {
                    assertTrue(owner.minBlockY() >= -11008); assertTrue(owner.minBlockY()+16 <= 8864);
                    assertTrue(owner.minBlockX() >= d.fixedFarCenterX()-512); assertTrue(owner.minBlockX()+16 <= d.fixedFarCenterX()+512);
                    assertTrue(owner.minBlockZ() >= d.fixedFarCenterZ()-512); assertTrue(owner.minBlockZ()+16 <= d.fixedFarCenterZ()+512);
                }
            }
        for (var c : new CubePos[]{new CubePos(windows.minX()-1, 469, windows.minZ()), new CubePos(windows.maxX()+1, 469, windows.minZ()),
                new CubePos(windows.minX(), -687, windows.minZ()), new CubePos(windows.minX(), 552, windows.minZ()),
                new CubePos(windows.minX(), 469, windows.minZ()-1), new CubePos(windows.minX(), 469, windows.maxZ()+1),
                new CubePos(Integer.MIN_VALUE, 469, 0), new CubePos(0, Integer.MAX_VALUE, 0), new CubePos(0, 469, Integer.MAX_VALUE)})
            assertThrows(IllegalArgumentException.class, () -> d.requireAllowedWindow(c));
    }

    @Test void transitionsRetainUnchanged213Source55Mesh125CollisionBounds() {
        var d = earth(); var a = d.pinCenter().offset(6, 6, 6); var b = a.offset(1, 1, 1);
        d.requireAllowedTransition(a, b); var plan = RollingCubePlan.create(d.pinCenter(), a, b);
        assertEquals(213, plan.stagedOwners().size()); assertEquals(125, plan.currentOwners().size());
        assertEquals(55, RollingCubePlan.MAX_STAGED_MESH_GENERATIONS);
        assertThrows(IllegalArgumentException.class, () -> d.requireAllowedTransition(a, a.offset(2, 0, 0)));
    }

    @Test void extremeCoordinatesOverflowAndInvalidHeightsFailBeforeArithmetic() {
        for (long value : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, -33554432L, 33554431L}) {
            assertThrows(IllegalArgumentException.class, () -> RollingSessionDescriptor.earth(earthWorld, palette, value, 7506, -8));
            assertThrows(IllegalArgumentException.class, () -> RollingSessionDescriptor.earth(earthWorld, palette, -8, 7506, value));
        }
        for (int ground : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, -11000, -10977, 8849, 8848, 8830})
            assertThrows(IllegalArgumentException.class, () -> RollingSessionDescriptor.earth(earthWorld, palette, -8, ground, -8));
        var low = RollingSessionDescriptor.earth(earthWorld, palette, -33553920L, -10976, -33553920L);
        var high = RollingSessionDescriptor.earth(earthWorld, palette, 33553920L, 8827, 33553920L);
        assertTrue(low.containsBody(low.walkEntryBody())); assertTrue(high.containsBody(high.walkEntryBody()));
    }

    @Test void missingMalformedOrUnwrappedEarthSourceAndWrongPaletteFailClosed() {
        var wrongPalette = new EnginePaletteContext(palette.biomes(), "b".repeat(64));
        var plain = new WorldIdentity(173, NativeEarthCubeProvider.GENERATOR_ID, NativeEarthCubeProvider.GENERATOR_VERSION,
                "a".repeat(64), NativeCubeCodec.paletteIdentity(palette));
        for (var w : new WorldIdentity[]{plain, earthWorld("unknown-source", palette),
                new WorldIdentity(173, "unknown:provider", earthWorld.generatorVersion(), earthWorld.datasetVersion(), earthWorld.blockPaletteVersion()),
                new WorldIdentity(173, "x".repeat(129), earthWorld.generatorVersion(), earthWorld.datasetVersion(), earthWorld.blockPaletteVersion())})
            assertThrows(IllegalArgumentException.class, () -> RollingSessionDescriptor.earth(w, palette, -8, 7506, -8));
        assertThrows(IllegalArgumentException.class, () -> RollingSessionDescriptor.earth(earthWorld, wrongPalette, -8, 7506, -8));
        var d = earth(); assertTrue(d.validatesPreparedWorld(earthWorld));
        var other = earthWorld("c".repeat(64), palette);
        assertFalse(d.validatesPreparedWorld(other)); assertThrows(IllegalArgumentException.class, () -> d.requirePreparedWorld(other));
        assertNotEquals(d.sourceFingerprint(), RollingSessionDescriptor.earth(other, palette, 9674936, 7506, -3112472).sourceFingerprint());
        assertNotEquals(d.saveDirectory(), RollingSessionDescriptor.earth(earthWorld, palette, 9674937, 7506, -3112472).saveDirectory());
    }

    @Test void crossWorldPaletteAndEveryTruncatedWireAreRejected() {
        var d = earth(); var b = encoded(d);
        try {
            byte[] data = new byte[b.readableBytes()]; b.getBytes(0, data);
            for (int n = 0; n < data.length; n++) {
                var cut = new FriendlyByteBuf(Unpooled.wrappedBuffer(java.util.Arrays.copyOf(data, n)));
                try { assertThrows(RuntimeException.class, () -> RollingSessionDescriptor.read(cut, d.world(), palette), "prefix="+n); }
                finally { cut.release(); }
            }
            assertThrows(IllegalArgumentException.class, () -> RollingSessionDescriptor.read(b, earthWorld("b".repeat(64), palette), palette));
            b.readerIndex(0);
            assertThrows(IllegalArgumentException.class, () -> RollingSessionDescriptor.read(b, d.world(), new EnginePaletteContext(palette.biomes(), "b".repeat(64))));
        } finally { b.release(); }
    }

    @Test void versionLengthUnknownKindSourceFingerprintAndPolicyForgeryAreRejected() {
        var d = earth();
        rejectMutation(d, b -> b.setByte(0, 1));
        rejectMutation(d, b -> b.setByte(0, 2));
        rejectMutation(d, b -> b.setByte(0, RollingSessionDescriptor.WIRE_VERSION + 1));
        rejectMutation(d, b -> { b.readVarInt(); b.readVarInt(); CoreGatePayload.world(b); int n=b.readVarInt(); assertEquals(5,n); b.setByte(b.readerIndex(),'x'); });
        rejectMutation(d, b -> b.setByte(b.writerIndex()-1, b.getByte(b.writerIndex()-1)=='0'?'1':'0'));
        rejectMutation(d, b -> { int end=b.writerIndex(); b.setByte(end-66, b.getByte(end-66)=='0'?'1':'0'); });
        rejectMutation(d, b -> {
            b.readVarInt(); b.readVarInt(); CoreGatePayload.world(b); b.readUtf(16); b.readUtf(16); b.readUtf(64);
            CoreGatePayload.position(b); CoreGatePayload.position(b);
            b.skipBytes(24+4+16); b.setByte(b.readerIndex(), 0);
        });
        rejectMutation(d, b -> {
            b.readVarInt(); b.readVarInt(); CoreGatePayload.world(b); b.readUtf(16); b.readUtf(16); b.readUtf(64);
            CoreGatePayload.position(b); CoreGatePayload.position(b);
            b.skipBytes(24+4+16); b.setByte(b.readerIndex(), 2);
        });
        var oversized = new FriendlyByteBuf(Unpooled.buffer());
        try { oversized.writeVarInt(RollingSessionDescriptor.WIRE_VERSION); oversized.writeVarInt(RollingSessionDescriptor.MAX_WIRE_BYTES+1); oversized.writeZero(RollingSessionDescriptor.MAX_WIRE_BYTES+1);
            assertThrows(IllegalArgumentException.class, () -> RollingSessionDescriptor.read(oversized,d.world(),palette)); }
        finally { oversized.release(); }
    }
    @Test void correctlyRecomputedFingerprintsCannotWidenOrRelabelCanonicalPolicy() throws Exception {
        var d=earth(); var e=d.bodyEnvelope(); var w=d.windowEnvelope();
        var constructor=java.util.Arrays.stream(RollingSessionDescriptor.class.getDeclaredConstructors()).filter(c->c.getParameterCount()==17).findFirst().orElseThrow(); constructor.setAccessible(true);
        for(int variant=0;variant<8;variant++) {
            var body=variant==0 ? new RollingSessionDescriptor.BodyEnvelope(e.minX()-512,e.maxX()+512,e.minY(),e.maxY(),e.minZ(),e.maxZ()) : e;
            var windows=variant==1 ? new RollingSessionDescriptor.WindowEnvelope(w.minX(),w.maxX(),w.minY()-1,w.maxY(),w.minZ(),w.maxZ()) : w;
            var pin=variant==2 ? d.pinCenter().offset(1,0,0) : d.pinCenter();
            var initial=variant==3 ? d.initialCenter().offset(0,1,0) : d.initialCenter();
            var entry=variant==4 ? d.entry().add(0,1,0) : d.entry();
            boolean relief=variant!=5;
            var layout=variant==7 ? DhLodLayout.LONG_4096 : d.lodLayout();
            String id=variant==6 ? "other" : d.profileId();
            // Build a wire attacker with valid hashes, so the canonical-policy comparison is exercised.
            var forged=(RollingSessionDescriptor)constructor.newInstance(d.world(),d.profileKind(),id,palette.fingerprint(),
                    pin,initial,entry,d.anchorY(),d.fixedFarCenterX(),d.fixedFarCenterZ(),relief,layout,RollingEditPolicy.INTERIOR_V1,body,windows,false,false);
            var b=encoded(forged);
            try { assertThrows(IllegalArgumentException.class,()->RollingSessionDescriptor.read(b,d.world(),palette),"variant="+variant); }
            finally { b.release(); }
        }
    }

    @Test void acceptedEarth003SourceAndDurableStoreIdentityArePreservedExactly() {
        // Frozen003 actual-run/reports/server-epoch-1-physics-0-status.json, captured before004.
        String acceptedPalette="190ffab17e9566b91a131a8096ea18056912a9a7b0012dde4538ed8326b519e4";
        var actualPalette=new EnginePaletteContext(palette.biomes(),acceptedPalette);
        var actualWorld=new WorldIdentity(20221009,NativeEarthCubeProvider.GENERATOR_ID,
                NativeEarthCubeProvider.GENERATOR_VERSION+"/"+RollingGateFixture.VERSION,
                "145860e78afd2e534edeaa279c90ba8e587f4267d40b95f40357b363032a402b",NativeCubeCodec.paletteIdentity(actualPalette));
        var d=RollingSessionDescriptor.earth(actualWorld,actualPalette,9674936,7506,-3112472);
        assertEquals("be1d771e82e7898f86f018facd95bd8e6f0acd736f27c836576731846e5f0f65",d.sourceFingerprint());
        String legacyFingerprint="1c1650a1c8aa566988f23d53c3e4a9c2b75d399f3df32f05934df1a0f1d4519a";
        assertEquals("earthplatform-earth-player-gate-v1/"+legacyFingerprint,d.saveDirectory());
        assertNotEquals(legacyFingerprint,d.fingerprint(),"v2 layout admission must not replay v1 policy fingerprints");
        assertSame(DhLodLayout.FIXED_512,d.lodLayout());
        var b=encoded(d);
        try {assertEquals(d,RollingSessionDescriptor.read(b,actualWorld,actualPalette));}
        finally {b.release();}
    }

    @Test void analyticCannotDowngradeLayoutEvenWithRecomputedHashesAndUnchangedSourceStore() throws Exception {
        var constructor=java.util.Arrays.stream(RollingSessionDescriptor.class.getDeclaredConstructors()).filter(c->c.getParameterCount()==17).findFirst().orElseThrow();constructor.setAccessible(true);
        for(var profile:CoreGateProfile.values()) {
            var d=RollingSessionDescriptor.analytic(profile,palette);
            var forged=(RollingSessionDescriptor)constructor.newInstance(d.world(),d.profileKind(),d.profileId(),palette.fingerprint(),
                    d.pinCenter(),d.initialCenter(),d.entry(),d.anchorY(),d.fixedFarCenterX(),d.fixedFarCenterZ(),d.naturalRelief(),
                    DhLodLayout.FIXED_512,RollingEditPolicy.INTERIOR_V1,d.bodyEnvelope(),d.windowEnvelope(),false,false);
            assertEquals(d.world(),forged.world());assertEquals(d.sourceFingerprint(),forged.sourceFingerprint());
            assertEquals(d.saveDirectory(),forged.saveDirectory());assertNotEquals(d.fingerprint(),forged.fingerprint());
            var b=encoded(forged);
            try {assertThrows(IllegalArgumentException.class,()->RollingSessionDescriptor.read(b,d.world(),palette));}
            finally {b.release();}
        }
    }

    @Test void layoutIdAndFullIdentityAreBoundAndUnknownAlterationsFailClosed() {
        for(var d:new RollingSessionDescriptor[]{earth(),RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette)}) {
            rejectMutation(d,b->{seekLayout(b);int count=b.readVarInt();assertTrue(count>0);b.setByte(b.readerIndex(),'x');});
            rejectMutation(d,b->{seekLayout(b);b.readUtf(32);int count=b.readVarInt();assertTrue(count>0);b.setByte(b.readerIndex(),'x');});
        }
    }
    private static void seekLayout(FriendlyByteBuf b) {
        b.readVarInt();b.readVarInt();CoreGatePayload.world(b);b.readUtf(16);b.readUtf(16);b.readUtf(64);
        CoreGatePayload.position(b);CoreGatePayload.position(b);b.skipBytes(24+4+16+1);
    }

    private static void rejectMutation(RollingSessionDescriptor d, Consumer<FriendlyByteBuf> mutation) {
        var b=encoded(d); try { mutation.accept(b); b.readerIndex(0); assertThrows(RuntimeException.class, () -> RollingSessionDescriptor.read(b,d.world(),palette)); }
        finally { b.release(); }
    }

    @Test void exactPlayerAndEpochSafetyCannotBeReusedOutsideEarthBodyEnvelope() {
        var d=earth(); UUID id=UUID.randomUUID(); var lease=new CorePlayerSafetyLease(id,31,d);
        assertTrue(lease.contains(id,31,d.walkEntryBody())); assertTrue(lease.contains(id,d.walkEntryBody()));
        assertFalse(lease.contains(id,30,d.walkEntryBody())); assertFalse(lease.contains(UUID.randomUUID(),31,d.walkEntryBody()));
        assertFalse(lease.contains(id,31,CoreGateProfile.ABYSS.walkEntryBody()));
        for(var invalid:new AABB[]{new AABB(Double.NaN,7509,0,1,7510,1),new AABB(0,0,0,Double.POSITIVE_INFINITY,1,1),
                new AABB(d.entry().x,7509,d.entry().z,d.entry().x+1.01,7510,d.entry().z+.6),
                new AABB(d.entry().x,7509,d.entry().z,d.entry().x+.6,7511.01,d.entry().z+.6),
                new AABB(d.entry().x,7509,d.entry().z,d.entry().x+.6,7510,d.entry().z+1.01),
                new AABB(d.entry().x,7509,d.entry().z,d.entry().x,7510,d.entry().z+.6)})
            assertFalse(lease.contains(id,31,invalid));
        assertFalse(lease.contains(id,31,null));
        assertThrows(NullPointerException.class, () -> new CorePlayerSafetyLease(id,31,null));
        assertThrows(NullPointerException.class, () -> new CorePlayerSafetyLease(null,31,d));
        assertThrows(IllegalArgumentException.class, () -> new CorePlayerSafetyLease(id,0,d));
        lease.close(); assertFalse(lease.contains(id,31,d.walkEntryBody()));
        var legacy = new CorePlayerSafetyLease(id,31); assertTrue(legacy.contains(id,CoreGateProfile.ABYSS.walkEntryBody()));
        assertTrue(legacy.contains(id,CoreGateProfile.SUMMIT.walkEntryBody())); assertFalse(legacy.contains(id,d.walkEntryBody()));
    }
}
