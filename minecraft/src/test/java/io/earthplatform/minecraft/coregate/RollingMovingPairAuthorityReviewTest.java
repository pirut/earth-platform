package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.enginebridge.NativeEarthCubeProvider;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import io.earthplatform.minecraft.lod.DhLodLayout;
import java.util.List;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent real policy/manifest/permit tests; no claim about a GL frame or live server. */
class RollingMovingPairAuthorityReviewTest {
    private static EnginePaletteContext palette;
    private static final long EPOCH = 37, RESOURCES = 9;
    private static final String NATIVE = "e".repeat(64);
    @BeforeAll static void registry() {
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(biomes.freeze());
    }
    private static RollingSessionDescriptor descriptor(String source, long x, long z) {
        var world = new WorldIdentity(173, NativeEarthCubeProvider.GENERATOR_ID,
                NativeEarthCubeProvider.GENERATOR_VERSION + "/" + RollingGateFixture.VERSION,
                source.repeat(64), NativeCubeCodec.paletteIdentity(palette));
        return RollingSessionDescriptor.earth(world, palette, x, 7506, z);
    }
    private static RollingCubeActor.StageReady ready(RollingSessionDescriptor profile,
            CubePos center, long epoch, long generation, long leaseOffset, long revision) {
        var versions = new ArrayList<RollingCubeActor.CubeVersion>();
        for (var p : RollingCubePlan.stationary(profile.pinCenter(), center).committedOwners())
            versions.add(new RollingCubeActor.CubeVersion(p, ++leaseOffset, revision));
        return new RollingCubeActor.StageReady(profile.world(), epoch, generation, center, versions);
    }
    private static void finish(RollingPublicationHandshake permit, long publication, String digest) {
        assertTrue(permit.prepared(EPOCH, publication, RESOURCES, digest));
        permit.grant(); assertTrue(permit.pairReady(EPOCH, publication, RESOURCES, digest)); permit.committed();
    }

    @Test void obsoleteNativeOnlyDigestCannotAuthorizeEitherHandshakeHalf() {
        var profile = descriptor("a", 9674936, -3112472);
        var ready = ready(profile, profile.initialCenter(), EPOCH, 1, 0, 0);
        String nativeOnly = RollingGateWire.manifestDigest(ready);
        String pair = RollingFarPublication.digest(profile, ready);
        assertNotEquals(nativeOnly, pair);
        var permit = new RollingPublicationHandshake(EPOCH); permit.begin(); permit.ready(pair);
        assertFalse(permit.prepared(EPOCH, 1, RESOURCES, nativeOnly));
        assertEquals(0, permit.resourceGeneration(), "Rejected legacy digest must not latch resources");
        assertEquals(RollingPublicationHandshake.State.READY, permit.state());
        assertTrue(permit.prepared(EPOCH, 1, RESOURCES, pair)); permit.grant();
        assertFalse(permit.pairReady(EPOCH, 1, RESOURCES, nativeOnly));
        assertEquals(RollingPublicationHandshake.State.GRANTED, permit.state());
        assertEquals(0, permit.committedPublication());
        assertTrue(permit.pairReady(EPOCH, 1, RESOURCES, pair)); permit.committed();
    }

    @Test void identicalNativeManifestWithWrongFarCenterCannotAuthorizePair() {
        var profile = descriptor("a", -8, -24);
        var a = RollingFarPublication.center(profile, profile.initialCenter());
        var b = RollingFarPublication.center(profile, profile.initialCenter().offset(4, 0, -4));
        assertNotEquals(a, b);
        var digestA = RollingFarPublication.digest(profile, NATIVE, a);
        var digestB = RollingFarPublication.digest(profile, NATIVE, b);
        assertNotEquals(digestA, digestB);
        var permit = new RollingPublicationHandshake(EPOCH); permit.begin(); permit.ready(digestA);
        assertFalse(permit.prepared(EPOCH, 1, RESOURCES, digestB));
        assertTrue(permit.prepared(EPOCH, 1, RESOURCES, digestA)); permit.grant();
        assertFalse(permit.pairReady(EPOCH, 1, RESOURCES, digestB));
        assertTrue(permit.pairReady(EPOCH, 1, RESOURCES, digestA)); permit.committed();
        assertEquals(digestA, permit.committedDigest());
    }

    @Test void sameCenterAndNativeDigestRemainBoundToSourceAndDescriptorPolicy() {
        var a = descriptor("a", -8, -24); var sourceChanged = descriptor("b", -8, -24);
        var anchorChangedInsideSameSnap = descriptor("a", -7, -23);
        var center = RollingFarPublication.center(a, a.initialCenter());
        assertEquals(center, RollingFarPublication.center(sourceChanged, sourceChanged.initialCenter()));
        assertEquals(center, RollingFarPublication.center(anchorChangedInsideSameSnap, anchorChangedInsideSameSnap.initialCenter()));
        var digest = RollingFarPublication.digest(a, NATIVE, center);
        assertNotEquals(digest, RollingFarPublication.digest(sourceChanged, NATIVE, center));
        assertNotEquals(digest, RollingFarPublication.digest(anchorChangedInsideSameSnap, NATIVE, center));
        var oldReady = ready(a, a.initialCenter(), EPOCH, 1, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> RollingFarPublication.digest(sourceChanged, oldReady));
    }

    @Test void returningToCenterACannotReviveOldPublicationOrOldLeaseAuthority() {
        var profile = descriptor("a", 9674936, -3112472);
        var a = profile.initialCenter(); var b = a.offset(4, 0, 0);
        var aReady = ready(profile, a, EPOCH, 1, 0, 0);
        var bReady = ready(profile, b, EPOCH, 2, 1000, 0);
        var returnReady = ready(profile, a, EPOCH, 3, 2000, 0);
        String first = RollingFarPublication.digest(profile, aReady), second = RollingFarPublication.digest(profile, bReady);
        String third = RollingFarPublication.digest(profile, returnReady);
        assertNotEquals(first, second); assertNotEquals(first, third);
        assertEquals(RollingFarPublication.center(profile, aReady.candidateCenter()),
                RollingFarPublication.center(profile, returnReady.candidateCenter()));
        var permit = new RollingPublicationHandshake(EPOCH); permit.begin(); permit.ready(first); finish(permit, 1, first);
        permit.begin(); permit.ready(second); finish(permit, 2, second);
        permit.begin(); permit.ready(third);
        assertFalse(permit.prepared(EPOCH, 1, RESOURCES, first));
        assertFalse(permit.prepared(EPOCH, 3, RESOURCES, first));
        assertFalse(permit.pairReady(EPOCH, 1, RESOURCES, first));
        finish(permit, 3, third);
        assertFalse(permit.acceptsMouse(EPOCH, 1, RESOURCES));
        assertTrue(permit.acceptsMouse(EPOCH, 3, RESOURCES));
    }

    @Test void everyNativeAuthorityDimensionChangesPairEvenAtSameSnappedCenter() {
        var profile = descriptor("a", -8, -24); var a = profile.initialCenter();
        var baseline = RollingFarPublication.digest(profile, ready(profile, a, EPOCH, 1, 0, 0));
        for (var changed : List.of(ready(profile, a, EPOCH + 1, 1, 0, 0),
                ready(profile, a, EPOCH, 2, 0, 0), ready(profile, a, EPOCH, 1, 1000, 0),
                ready(profile, a, EPOCH, 1, 0, 1), ready(profile, a.offset(0, 1, 0), EPOCH, 1, 0, 0)))
            assertNotEquals(baseline, RollingFarPublication.digest(profile, changed));
    }

    @Test void delayedExactReturnPacketsCannotReopenResourceFencedSession() {
        var profile = descriptor("a", -8, -24);
        var digest = RollingFarPublication.digest(profile, ready(profile, profile.initialCenter(), EPOCH, 1, 0, 0));
        for (boolean granted : new boolean[]{false, true}) {
            var old = new RollingPublicationHandshake(EPOCH); old.begin(); old.ready(digest);
            if (granted) { assertTrue(old.prepared(EPOCH, 1, RESOURCES, digest)); old.grant(); }
            old.close();
            assertFalse(old.prepared(EPOCH, 1, RESOURCES, digest));
            assertFalse(old.pairReady(EPOCH, 1, RESOURCES, digest));
            var replacement = new RollingPublicationHandshake(EPOCH + 1);
            replacement.begin(); replacement.ready(digest);
            assertFalse(replacement.prepared(EPOCH, 1, RESOURCES, digest));
            assertFalse(replacement.pairReady(EPOCH, 1, RESOURCES, digest));
            assertEquals(0, replacement.resourceGeneration());
        }
    }
    @Test void obsoleteFrozenV1PairDigestCannotAuthorizeV2EvenWhenEarthExtentAndNativeManifestMatch() throws Exception {
        var profile=descriptor("a",9674936,-3112472);var ready=ready(profile,profile.initialCenter(),EPOCH,1,0,0);
        String nativeDigest=RollingGateWire.manifestDigest(ready);var center=RollingFarPublication.center(profile,ready.candidateCenter());
        String oldDescriptor=profile.saveDirectory().substring("earthplatform-earth-player-gate-v1/".length());
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
            for(String value:List.of("rolling-far-publication-v1",oldDescriptor,nativeDigest)){byte[] encoded=value.getBytes(StandardCharsets.UTF_8);out.writeInt(encoded.length);out.write(encoded);}
            out.writeLong(center.x());out.writeLong(center.z());out.writeInt(512);out.writeInt(64);
        }
        String obsolete=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        String current=RollingFarPublication.digest(profile,ready);assertNotEquals(obsolete,current);
        var permit=new RollingPublicationHandshake(EPOCH);permit.begin();permit.ready(current);
        assertFalse(permit.prepared(EPOCH,1,RESOURCES,obsolete));assertEquals(0,permit.resourceGeneration());
        assertTrue(permit.prepared(EPOCH,1,RESOURCES,current));permit.grant();
        assertFalse(permit.pairReady(EPOCH,1,RESOURCES,obsolete));assertEquals(0,permit.committedPublication());
        assertTrue(permit.pairReady(EPOCH,1,RESOURCES,current));permit.committed();
    }

    @Test void allAnalyticProfilesUseLongLayoutAndEveryExactAuthorityChangeStillRejectsReplay() {
        for(var fixture:CoreGateProfile.values()) {
            var profile=RollingSessionDescriptor.analytic(fixture,palette);assertSame(DhLodLayout.LONG_4096,profile.lodLayout());
            var a=profile.initialCenter();var b=a.offset(16,0,-16);
            var oldReady=ready(profile,a,EPOCH,1,0,0);var newReady=ready(profile,b,EPOCH,2,1000,1);
            String old=RollingFarPublication.digest(profile,oldReady),current=RollingFarPublication.digest(profile,newReady);
            assertEquals(new RollingFarPublication.Center(256,-256),RollingFarPublication.center(profile,b));
            assertNotEquals(old,current);var permit=new RollingPublicationHandshake(EPOCH);permit.begin();permit.ready(old);finish(permit,1,old);
            permit.begin();permit.ready(current);assertFalse(permit.prepared(EPOCH,2,RESOURCES,old));
            assertTrue(permit.prepared(EPOCH,2,RESOURCES,current));permit.grant();assertFalse(permit.pairReady(EPOCH,2,RESOURCES,old));
            assertTrue(permit.pairReady(EPOCH,2,RESOURCES,current));permit.committed();
            assertFalse(permit.acceptsMouse(EPOCH,1,RESOURCES));assertTrue(permit.acceptsMouse(EPOCH,2,RESOURCES));
        }
    }

}
