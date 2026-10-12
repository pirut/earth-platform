package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.enginebridge.NativeEarthCubeProvider;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.lod.DhLodLayout;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollingFarPublicationTest {
    private static EnginePaletteContext palette;
    @BeforeAll static void registry() {
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(biomes.freeze());
    }
    private static WorldIdentity world(String source) {
        return new WorldIdentity(173, NativeEarthCubeProvider.GENERATOR_ID,
                NativeEarthCubeProvider.GENERATOR_VERSION + "/" + RollingGateFixture.VERSION,
                source, NativeCubeCodec.paletteIdentity(palette));
    }
    private static RollingSessionDescriptor earth(long x,long z) {
        return RollingSessionDescriptor.earth(world("a".repeat(64)),palette,x,7506,z);
    }
    private static RollingCubeActor.StageReady ready(RollingSessionDescriptor descriptor,CubePos center) {
        var versions=new ArrayList<RollingCubeActor.CubeVersion>();long lease=0;
        for(var p:RollingCubePlan.stationary(descriptor.pinCenter(),center).committedOwners())
            versions.add(new RollingCubeActor.CubeVersion(p,++lease,0));
        return new RollingCubeActor.StageReady(descriptor.world(),7,2,center,versions);
    }

    @Test void signedSnappingUsesAlignedAnchorAndCandidateCubeMidpoint() {
        assertEquals(0,RollingFarPublication.snappedCoordinate(0,1,DhLodLayout.FIXED_512));
        assertEquals(64,RollingFarPublication.snappedCoordinate(0,2,DhLodLayout.FIXED_512));
        assertEquals(0,RollingFarPublication.snappedCoordinate(0,-2,DhLodLayout.FIXED_512));
        assertEquals(-64,RollingFarPublication.snappedCoordinate(0,-3,DhLodLayout.FIXED_512));
        assertEquals(-16,RollingFarPublication.snappedCoordinate(-1,0,DhLodLayout.FIXED_512));
        assertEquals(48,RollingFarPublication.snappedCoordinate(-1,1,DhLodLayout.FIXED_512));
        assertEquals(-16,RollingFarPublication.snappedCoordinate(-1,-3,DhLodLayout.FIXED_512));
        assertEquals(-80,RollingFarPublication.snappedCoordinate(-1,-4,DhLodLayout.FIXED_512));
        for(long anchor=-33;anchor<=33;anchor++)for(long candidate=-32;candidate<=32;candidate++) {
            long aligned=Math.floorDiv(anchor,16)*16;
            var a=BigInteger.valueOf(aligned);
            var shifted=BigInteger.valueOf(candidate).multiply(BigInteger.valueOf(16)).add(BigInteger.valueOf(8)).subtract(a).add(BigInteger.valueOf(32));
            var divided=shifted.divideAndRemainder(BigInteger.valueOf(64));
            var floor=shifted.signum()<0&&divided[1].signum()!=0?divided[0].subtract(BigInteger.ONE):divided[0];
            long expected=a.add(floor.multiply(BigInteger.valueOf(64))).longValueExact();
            assertEquals(expected,RollingFarPublication.snappedCoordinate(anchor,candidate,DhLodLayout.FIXED_512),"anchor="+anchor+" candidate="+candidate);
        }
    }

    @Test void arithmeticOverflowsFailInsteadOfWrappingOrClamping() {
        for(long candidate:new long[]{Long.MIN_VALUE,Long.MAX_VALUE,Long.MIN_VALUE/16-1,Long.MAX_VALUE/16+1,Long.MAX_VALUE/16})
            assertThrows(ArithmeticException.class,()->RollingFarPublication.snappedCoordinate(0,candidate,DhLodLayout.FIXED_512));
        assertThrows(ArithmeticException.class,()->RollingFarPublication.snappedCoordinate(Long.MIN_VALUE,0,DhLodLayout.FIXED_512));
        assertThrows(ArithmeticException.class,()->RollingFarPublication.snappedCoordinate(Long.MAX_VALUE,-2,DhLodLayout.FIXED_512));
        assertThrows(ArithmeticException.class,()->RollingFarPublication.snappedCoordinate(16,Long.MIN_VALUE/16,DhLodLayout.FIXED_512));
        assertEquals(Long.MIN_VALUE,RollingFarPublication.snappedCoordinate(Long.MIN_VALUE,Long.MIN_VALUE/16,DhLodLayout.FIXED_512));
        assertEquals(Long.MAX_VALUE-15,RollingFarPublication.snappedCoordinate(Long.MAX_VALUE,Long.MAX_VALUE/16,DhLodLayout.FIXED_512));
        assertEquals(34_359_738_368L,RollingFarPublication.snappedCoordinate(0,Integer.MAX_VALUE,DhLodLayout.FIXED_512));
        assertEquals(-34_359_738_368L,RollingFarPublication.snappedCoordinate(0,Integer.MIN_VALUE,DhLodLayout.FIXED_512));
    }

    @Test void earthUsesExactReadyCubeAndKeepsDescriptorIdentityAndBounds() {
        var d=earth(9674936,-3112472);String fingerprint=d.fingerprint(),store=d.saveDirectory();var world=d.world();
        var initial=RollingFarPublication.center(d,d.initialCenter());
        assertEquals(new RollingFarPublication.Center(9674928,-3112480),initial);
        assertEquals(initial,RollingFarPublication.center(d,d.initialCenter().offset(1,0,1)));
        assertEquals(new RollingFarPublication.Center(9674992,-3112416),RollingFarPublication.center(d,d.initialCenter().offset(2,0,2)));
        assertEquals(new RollingFarPublication.Center(9674864,-3112544),RollingFarPublication.center(d,d.initialCenter().offset(-3,0,-3)));
        assertEquals(initial,RollingFarPublication.center(d,new CubePos(d.initialCenter().x(),d.windowEnvelope().minY(),d.initialCenter().z())));
        assertEquals(initial,RollingFarPublication.center(d,new CubePos(d.initialCenter().x(),d.windowEnvelope().maxY(),d.initialCenter().z())));
        assertEquals(256,d.bodyEnvelope().maxX()-d.bodyEnvelope().minX());assertEquals(256,d.bodyEnvelope().maxZ()-d.bodyEnvelope().minZ());
        assertEquals(213,RollingCubePlan.MAX_STAGED_OWNERS);assertEquals(55,RollingCubePlan.MAX_STAGED_MESH_GENERATIONS);
        assertEquals(fingerprint,d.fingerprint());assertEquals(store,d.saveDirectory());assertEquals(world,d.world());
        assertEquals(5,RollingSessionDescriptor.WIRE_VERSION);assertEquals(3,RollingGateWire.WIRE_VERSION);
    }

    @Test void analyticBaseAbyssSummitShareSignedMovementWithoutChangingTheirEnvelopes() {
        for(var profile:CoreGateProfile.values()) {
            var d=RollingSessionDescriptor.analytic(profile,palette);var w=d.windowEnvelope();
            assertSame(DhLodLayout.LONG_4096,d.lodLayout());assertEquals(4096,d.fixedFarRadius());
            for(int y:new int[]{w.minY(),profile.center().y(),w.maxY()}) {
                assertEquals(new RollingFarPublication.Center(-512,-512),RollingFarPublication.center(d,new CubePos(w.minX(),y,w.minZ())));
                assertEquals(new RollingFarPublication.Center(512,512),RollingFarPublication.center(d,new CubePos(w.maxX(),y,w.maxZ())));
                assertEquals(new RollingFarPublication.Center(0,0),RollingFarPublication.center(d,new CubePos(-3,y,2)));
                assertEquals(new RollingFarPublication.Center(-256,256),RollingFarPublication.center(d,new CubePos(-9,y,8)));
            }
            assertEquals(-512,d.bodyEnvelope().minX());assertEquals(512,d.bodyEnvelope().maxX());
            assertEquals(RollingGateFixture.directory(profile),d.saveDirectory());
            assertThrows(IllegalArgumentException.class,()->RollingFarPublication.center(d,new CubePos(w.minX()-1,profile.center().y(),0)));
            assertThrows(IllegalArgumentException.class,()->RollingFarPublication.center(d,new CubePos(0,profile.center().y(),w.maxZ()+1)));
        }
    }

    @Test void nativeCoordinateEdgeHasNoSyntheticOrClampedFarWindow() {
        long min=(long)RollingCubePlan.NATIVE_XZ_MIN*16,max=((long)RollingCubePlan.NATIVE_XZ_MAX+1)*16;
        var low=earth(min+512,min+512);var high=earth(max-512,max-512);
        assertEquals(new RollingFarPublication.Center(min+512,min+512),RollingFarPublication.center(low,low.initialCenter()));
        assertEquals(new RollingFarPublication.Center(max-512,max-512),RollingFarPublication.center(high,high.initialCenter()));
        var outsideLow=low.initialCenter().offset(-3,0,0);low.requireAllowedWindow(outsideLow);
        var outsideHigh=high.initialCenter().offset(2,0,0);high.requireAllowedWindow(outsideHigh);
        assertThrows(IllegalArgumentException.class,()->RollingFarPublication.center(low,outsideLow));
        assertThrows(IllegalArgumentException.class,()->RollingFarPublication.center(high,outsideHigh));
        for(var extreme:new CubePos[]{new CubePos(Integer.MIN_VALUE,469,0),new CubePos(Integer.MAX_VALUE,469,0),new CubePos(0,Integer.MAX_VALUE,0)})
            assertThrows(IllegalArgumentException.class,()->RollingFarPublication.center(low,extreme));
    }

    @Test void pairDigestBindsExactDescriptorNativeManifestCenterAndCompleteLayoutPolicy() throws Exception {
        for(var d:new RollingSessionDescriptor[]{earth(9674936,-3112472),RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette)}) {
            var r=ready(d,d.initialCenter());var c=RollingFarPublication.center(d,r.candidateCenter());var layout=d.lodLayout();
            String nativeHash=RollingGateWire.manifestDigest(r),digest=RollingFarPublication.digest(d,r);
            assertEquals(64,digest.length());assertNotEquals(nativeHash,digest);
            assertEquals(digest,RollingFarPublication.digest(d,nativeHash,c));
            assertEquals(digest,oracle(RollingFarPublication.POLICY_VERSION,d.fingerprint(),nativeHash,c,layout.id(),layout.identity()));
            assertNotEquals(digest,oracle("rolling-far-publication-v1",d.fingerprint(),nativeHash,c,layout.id(),layout.identity()));
            assertNotEquals(digest,oracle(RollingFarPublication.POLICY_VERSION,d.fingerprint(),nativeHash,c,"other-layout",layout.identity()));
            for(String label:new String[]{"square-radius=", "center-align=", "snap=", "rings=", "fine=", "collar=", "fine-tile=", "native-y=", "full-xyz-clip=", "complete-footprint-required="}) {
                assertTrue(layout.identity().contains(label),label);
                assertNotEquals(digest,oracle(RollingFarPublication.POLICY_VERSION,d.fingerprint(),nativeHash,c,layout.id(),layout.identity().replace(label,label+"altered")),label);
            }
            assertNotEquals(digest,RollingFarPublication.digest(d,nativeHash,new RollingFarPublication.Center(c.x()+layout.snapBlocks(),c.z())));
            assertNotEquals(digest,RollingFarPublication.digest(d,nativeHash,new RollingFarPublication.Center(c.x(),c.z()-layout.snapBlocks())));
            assertNotEquals(digest,RollingFarPublication.digest(d,"b".repeat(64),c));
        }
        var d=earth(9674936,-3112472);var r=ready(d,d.initialCenter());var c=RollingFarPublication.center(d,r.candidateCenter());
        String nativeHash=RollingGateWire.manifestDigest(r),digest=RollingFarPublication.digest(d,r);
        var source=RollingSessionDescriptor.earth(world("c".repeat(64)),palette,9674936,7506,-3112472);
        assertNotEquals(digest,RollingFarPublication.digest(source,nativeHash,c));
        var policy=earth(9674937,-3112472);assertEquals(c,RollingFarPublication.center(policy,policy.initialCenter()));
        assertNotEquals(digest,RollingFarPublication.digest(policy,nativeHash,c));
        assertThrows(IllegalArgumentException.class,()->RollingFarPublication.digest(source,r));
    }

    @Test void allImmutableLatticesMatchIndependentBigIntegerOracleAcrossSignedBoundaries() {
        for(var layout:DhLodLayout.values()) for(long anchor:new long[]{-33550336,-513,-129,-128,-127,-1,0,1,15,16,127,128,129,33550336})
            for(long candidate=-512;candidate<=512;candidate++) assertSnappingOracle(anchor,candidate,layout);
        for(var layout:DhLodLayout.values()) {
            assertEquals(Long.MIN_VALUE,RollingFarPublication.snappedCoordinate(Long.MIN_VALUE,Long.MIN_VALUE/16,layout));
            assertSnappingOracle(Long.MAX_VALUE,Long.MAX_VALUE/16,layout);
            for(long candidate:new long[]{Long.MIN_VALUE,Long.MAX_VALUE,Long.MIN_VALUE/16-1,Long.MAX_VALUE/16+1,Long.MAX_VALUE/16})
                assertThrows(ArithmeticException.class,()->RollingFarPublication.snappedCoordinate(0,candidate,layout));
            assertThrows(ArithmeticException.class,()->RollingFarPublication.snappedCoordinate(Long.MIN_VALUE,0,layout));
            assertThrows(ArithmeticException.class,()->RollingFarPublication.snappedCoordinate(Long.MAX_VALUE,Integer.MIN_VALUE,layout));
            assertEquals(34_359_738_368L,RollingFarPublication.snappedCoordinate(0,Integer.MAX_VALUE,layout));
            assertEquals(-34_359_738_368L,RollingFarPublication.snappedCoordinate(0,Integer.MIN_VALUE,layout));
            // Exercise both native-width and signed-long boundaries for every immutable layout.
            for(long anchor:new long[]{Long.MIN_VALUE,Long.MIN_VALUE+1,Long.MIN_VALUE+127,-128,-1,0,1,128,Long.MAX_VALUE-127,Long.MAX_VALUE-1,Long.MAX_VALUE}) {
                for(long candidate:new long[]{Long.MIN_VALUE,Long.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,-9,-8,-1,0,1,7,8})
                    assertSnappingOracle(anchor,candidate,layout);
                for(int offset=-20;offset<=20;offset++) {
                    assertSnappingOracle(anchor,Long.MIN_VALUE/16+offset,layout);
                    assertSnappingOracle(anchor,Long.MAX_VALUE/16+offset,layout);
                }
            }
        }
        // Unlike 128-aligned/256-snap layouts, Alpine's upper half-cell rounds to +2^63.
        // That mathematical result must be refused, never clamped to the last aligned anchor.
        var alpine=DhLodLayout.EARTH_ALPINE_2048;
        assertEquals(Long.MAX_VALUE-127,RollingFarPublication.snappedCoordinate(Long.MAX_VALUE,Long.MAX_VALUE/16-4,alpine));
        for(int offset=0;offset<=3;offset++) {
            long candidate=Long.MAX_VALUE/16-offset;
            assertThrows(ArithmeticException.class,()->RollingFarPublication.snappedCoordinate(Long.MAX_VALUE,candidate,alpine));
        }
    }

    /** Unbounded integer oracle: floor ties toward positive infinity on the anchor-relative lattice.
     * Refusal also covers intermediate values outside signed long, as required by the checked contract. */
    private static void assertSnappingOracle(long fixed,long candidate,DhLodLayout layout) {
        var alignment=BigInteger.valueOf(layout.centerAlignmentBlocks());var snap=BigInteger.valueOf(layout.snapBlocks());
        var anchor=floorDivide(BigInteger.valueOf(fixed),alignment).multiply(alignment);
        var cubeOrigin=BigInteger.valueOf(candidate).multiply(BigInteger.valueOf(16));
        var midpoint=cubeOrigin.add(BigInteger.valueOf(8));var delta=midpoint.subtract(anchor);
        var biased=delta.add(snap.divide(BigInteger.TWO));var displacement=floorDivide(biased,snap).multiply(snap);
        var result=anchor.add(displacement);String context=layout+" anchor="+fixed+" candidate="+candidate;
        boolean representable=true;
        for(var value:new BigInteger[]{anchor,cubeOrigin,midpoint,delta,biased,displacement,result})
            representable &= value.compareTo(BigInteger.valueOf(Long.MIN_VALUE))>=0&&value.compareTo(BigInteger.valueOf(Long.MAX_VALUE))<=0;
        if(representable) {
            long actual=RollingFarPublication.snappedCoordinate(fixed,candidate,layout);
            assertEquals(result.longValueExact(),actual,context);
            assertEquals(BigInteger.ZERO,BigInteger.valueOf(actual).subtract(anchor).remainder(snap),context+" lattice alignment");
            var error=BigInteger.valueOf(actual).subtract(midpoint);var half=snap.divide(BigInteger.TWO);
            assertTrue(error.compareTo(half)<=0&&error.compareTo(half.negate())>0,context+" nearest-cell/tie direction");
        } else assertThrows(ArithmeticException.class,()->RollingFarPublication.snappedCoordinate(fixed,candidate,layout),context);
    }
    private static BigInteger floorDivide(BigInteger value,BigInteger divisor) {
        var parts=value.divideAndRemainder(divisor);
        return value.signum()<0&&parts[1].signum()!=0?parts[0].subtract(BigInteger.ONE):parts[0];
    }

    @Test void sameFarCenterStillBindsCandidateLeaseRevisionEpochAndActorGeneration() {
        var d=earth(9674936,-3112472);var r=ready(d,d.initialCenter());var digest=RollingFarPublication.digest(d,r);
        var neighbor=ready(d,d.initialCenter().offset(1,0,0));
        assertEquals(RollingFarPublication.center(d,r.candidateCenter()),RollingFarPublication.center(d,neighbor.candidateCenter()));
        assertNotEquals(digest,RollingFarPublication.digest(d,neighbor));
        assertNotEquals(digest,RollingFarPublication.digest(d,new RollingCubeActor.StageReady(d.world(),8,r.generation(),r.candidateCenter(),r.versions())));
        assertNotEquals(digest,RollingFarPublication.digest(d,new RollingCubeActor.StageReady(d.world(),r.epoch(),3,r.candidateCenter(),r.versions())));
        for(boolean revision:new boolean[]{true,false}) {
            var versions=new ArrayList<>(r.versions());var old=versions.getFirst();
            versions.set(0,new RollingCubeActor.CubeVersion(old.position(),old.leaseGeneration()+(revision?0:1000),old.revision()+(revision?1:0)));
            assertNotEquals(digest,RollingFarPublication.digest(d,new RollingCubeActor.StageReady(d.world(),r.epoch(),r.generation(),r.candidateCenter(),versions)));
        }
    }

    @Test void legacyV1PairCannotReplayAgainstV2EvenForUnexpandedEarth() throws Exception {
        var d=earth(9674936,-3112472);var r=ready(d,d.initialCenter());var c=RollingFarPublication.center(d,r.candidateCenter());
        String nativeHash=RollingGateWire.manifestDigest(r),v2=RollingFarPublication.digest(d,r);
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            String legacyDescriptor=d.saveDirectory().substring(d.saveDirectory().lastIndexOf('/')+1);
            for(String value:new String[]{"rolling-far-publication-v1",legacyDescriptor,nativeHash}) {
                byte[] encoded=value.getBytes(StandardCharsets.UTF_8);out.writeInt(encoded.length);out.write(encoded);
            }
            out.writeLong(c.x());out.writeLong(c.z());out.writeInt(512);out.writeInt(64);
        }
        String v1=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        assertNotEquals(v1,v2);
        var handshake=new RollingPublicationHandshake(r.epoch());long publication=handshake.begin();handshake.ready(v2);
        assertFalse(handshake.prepared(r.epoch(),publication,1,v1));assertEquals(0,handshake.resourceGeneration());
        assertTrue(handshake.prepared(r.epoch(),publication,1,v2));handshake.grant();
        assertFalse(handshake.pairReady(r.epoch(),publication,1,v1));assertEquals(0,handshake.committedPublication());
        assertTrue(handshake.pairReady(r.epoch(),publication,1,v2));handshake.committed();
        assertEquals(v2,handshake.committedDigest());
    }

    @Test void malformedNativeDigestsCannotBecomePublicationProofs() {
        var d=earth(9674936,-3112472);var c=RollingFarPublication.center(d,d.initialCenter());
        for(String bad:new String[]{null,"","a".repeat(63),"a".repeat(65),"A".repeat(64),"g".repeat(64)})
            assertThrows(IllegalArgumentException.class,()->RollingFarPublication.digest(d,bad,c));
    }

    private static String oracle(String version,String descriptor,String nativeHash,RollingFarPublication.Center center,String layoutId,String layoutIdentity) throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            for(String s:new String[]{version,descriptor,nativeHash}) {byte[] b=s.getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}
            out.writeLong(center.x());out.writeLong(center.z());
            for(String s:new String[]{layoutId,layoutIdentity}) {byte[] b=s.getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }
}
