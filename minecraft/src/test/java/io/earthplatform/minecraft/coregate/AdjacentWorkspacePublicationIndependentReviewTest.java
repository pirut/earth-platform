package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeReplacementCapture;
import io.earthplatform.minecraft.enginebridge.ReplacementSetCertificate;
import io.earthplatform.minecraft.enginebridge.ReplacementWorkspaces;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.enginebridge.RollingEditPolicy;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent actor/wire/client adversaries for adjacent capture coherence and one native focus. */
class AdjacentWorkspacePublicationIndependentReviewTest {
    private static final CubePos A = new CubePos(0, 8, 0), B = new CubePos(1, 8, 0);
    private static final WorldIdentity WORLD = new WorldIdentity(1, "fixture", "v1", "source", "palette");
    private static final String STORE = "ebd24c87-9e0d-45ed-9e94-e17c177d77e3", INDEX = "a".repeat(64), PALETTE = "b".repeat(64);
    private static EnginePaletteContext palette;
    @TempDir Path temporary;
    static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
    }
    private record Content(ReplacementSetCertificate certificate, List<NativeReplacementCapture> captures) { }
    private static Content content(long generation, long aRevision, long bRevision, long firstLease) {
        var regions = new ArrayList<ReplacementSetCertificate.Region>();
        var captures = new ArrayList<NativeReplacementCapture>();
        for (var position : List.of(A, B)) {
            var capture = new NativeReplacementCapture(position, new byte[8000]);
            if (aRevision > 0) capture = capture.withReadMaterial(15, 128, 0,
                    aRevision == 1 ? NativeReplacementCapture.STONE : NativeReplacementCapture.DIORITE).compact();
            if (bRevision > 0) capture = capture.withReadMaterial(16, 128, 0,
                    bRevision == 1 ? NativeReplacementCapture.STONE : NativeReplacementCapture.DIORITE).compact();
            captures.add(capture);
            var versions = new ArrayList<CubeVersion>();
            for (var dependency : CubeTickets.box(position, 1, 1, 27).stream().sorted().toList())
                versions.add(new CubeVersion(dependency, firstLease++, dependency.equals(A) ? aRevision : dependency.equals(B) ? bRevision : 0));
            regions.add(new ReplacementSetCertificate.Region(position, versions, capture.contentIdentity()));
        }
        return new Content(new ReplacementSetCertificate(WORLD, 5, 1, STORE, INDEX, "provider", PALETTE,
                "material", "detail", generation, regions), List.copyOf(captures));
    }

    @Test void individuallyValidCaptureHashesCannotHideDisagreeingSharedReadVoxels() {
        var source = content(1, 0, 0, 1);
        var forgedCapture = source.captures().get(1).withReadMaterial(15, 143, 15, NativeReplacementCapture.DIORITE).compact();
        var old = source.certificate(); var regions = new ArrayList<>(old.regions()); var b = regions.get(1);
        regions.set(1, new ReplacementSetCertificate.Region(B, b.dependencies(), forgedCapture.contentIdentity()));
        var forged = new ReplacementSetCertificate(old.world(), old.epoch(), old.resourceGeneration(), old.storeIdentity(), old.indexIdentity(),
                old.providerIdentity(), old.paletteIdentity(), old.materialPolicy(), old.detailPolicy(), old.generation(), regions);
        var captures = List.of(source.captures().get(0), forgedCapture);
        assertThrows(IllegalArgumentException.class, () -> new StageReady(WORLD, 5, 1, A, List.of(), null, true, A, forged, captures));
        var request = new EditRequest(WORLD, 5, A, 55, 0, 15, 143, 15, 1);
        var changed = content(2, 1, 0, 101); var changedRegions = new ArrayList<>(changed.certificate().regions());
        var changedB = changedRegions.get(1);
        changedRegions.set(1, new ReplacementSetCertificate.Region(B, changedB.dependencies(), forgedCapture.contentIdentity()));
        var badAckSet = new ReplacementSetCertificate(WORLD, 5, 1, STORE, INDEX, "provider", PALETTE, "material", "detail", 2, changedRegions);
        assertThrows(IllegalArgumentException.class, () -> new EditAck(request, EditStatus.APPLIED, 1, "forged", null, badAckSet,
                List.of(changed.captures().get(0), forgedCapture)));
    }
    private static void assertSharedValues(List<NativeReplacementCapture> captures) {
        for (long x = 14; x < 18; x++) for (long y = 126; y < 146; y++) for (long z = -2; z < 18; z++)
            assertEquals(captures.get(0).materialAt(x,y,z), captures.get(1).materialAt(x,y,z), x + "/" + y + "/" + z);
    }
    private static void assertSharedRevisions(ReplacementSetCertificate set) {
        for (var a : set.region(A).dependencies()) for (var b : set.region(B).dependencies())
            if (a.position().equals(b.position())) assertEquals(a.revision(), b.revision(), a.position().toString());
    }
    private static Event next(RollingCubeActor actor, ArrayDeque<Event> events) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (events.isEmpty()) { events.addAll(actor.pollEvents(2)); if (events.isEmpty()) { assertTrue(System.nanoTime() < end, actor.diagnostics().toString()); Thread.sleep(1); } }
        var event = events.removeFirst(); if (event instanceof Failure failed) fail(failed.detail()); return event;
    }
}
