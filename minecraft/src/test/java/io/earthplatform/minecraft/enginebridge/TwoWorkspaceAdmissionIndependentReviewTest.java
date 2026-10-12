package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.EditSnapshot;
import io.earthplatform.core.FileEditStore;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.lod.DhAnalyticFlatPolicy;
import io.earthplatform.minecraft.lod.DhAnalyticSetAdmission;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import io.earthplatform.minecraft.lod.DhReplacementSetComposition;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Independent adversarial checks. No production path is replaced by a client admission boolean. */
class TwoWorkspaceAdmissionIndependentReviewTest {
    private static final CubePos ORIGINAL = new CubePos(0, 8, 0);
    private static final WorldIdentity WORLD = new WorldIdentity(7, "analytic", "v1", "fixture", "palette");
    private static final String STORE = "98e296d8-68d8-44cd-8fd4-d314bca95c72";
    @TempDir Path temporary;

    @Test void focusEligibilityEqualsActualContainmentOfAll27Dependencies() {
        var workspaces = ReplacementWorkspaces.two(ORIGINAL);
        for (CubePos workspace : workspaces.positions()) {
            for (int x = -4; x <= 4; x++) for (int y = -4; y <= 4; y++) for (int z = -4; z <= 4; z++) {
                CubePos window = workspace.offset(x, y, z);
                var owners = new HashSet<>(CubeTickets.box(window, 2, 2, 125));
                assertEquals(owners.containsAll(workspaces.dependencies(workspace)),
                        workspaces.insideWindow(workspace, window), workspace + " / " + window);
            }
        }
        assertFalse(workspaces.insideWindow(ORIGINAL.offset(1, 0, 0), ORIGINAL));
        assertThrows(IllegalArgumentException.class, () -> workspaces.dependencies(ORIGINAL.offset(1, 0, 0)));
    }

    @Test void canonicalPairCannotAliasPermutationDuplicateOrExtraEntry() {
        var pair = ReplacementWorkspaces.two(ORIGINAL);
        List<List<CubePos>> invalid = List.of(List.of(), List.of(ORIGINAL),
                List.of(ORIGINAL, ORIGINAL), List.of(pair.second(), ORIGINAL),
                List.of(ORIGINAL, ORIGINAL.offset(3, 0, 0)),
                List.of(ORIGINAL, pair.second(), ORIGINAL.offset(8, 0, 0)));
        for (var value : invalid)
            assertThrows(IllegalArgumentException.class, () -> ReplacementWorkspaces.canonical(value), value.toString());
        var mutable = new ArrayList<>(pair.positions());
        var canonical = ReplacementWorkspaces.canonical(mutable);
        mutable.set(1, ORIGINAL);
        assertEquals(pair.positions(), canonical.positions());
        assertEquals(pair.identity(), canonical.identity());
        assertThrows(UnsupportedOperationException.class, () -> canonical.positions().set(0, pair.second()));
        assertThrows(UnsupportedOperationException.class, () -> canonical.dependencies(ORIGINAL).clear());
    }

    @Test void formatDowngradeCannotCreateLegacyIndexBesideTwoWorkspaceStore() throws Exception {
        var pair = ReplacementWorkspaces.two(ORIGINAL);
        var identity = ReplacementStoreIdentity.openTwo(temporary, pair);
        Path modern = temporary.resolve(ReplacementStoreIdentity.TWO_FILE);
        byte[] before = Files.readAllBytes(modern);
        assertThrows(IOException.class, () -> ReplacementStoreIdentity.open(temporary, ORIGINAL));
        assertFalse(Files.exists(temporary.resolve(ReplacementStoreIdentity.FILE)));
        assertArrayEquals(before, Files.readAllBytes(modern));
        assertEquals(identity, ReplacementStoreIdentity.openTwo(temporary, pair));
    }

    @Test void missingSetIndexCannotAdoptExistingPrimaryBackupOrUnknownCubeData() throws Exception {
        var pair = ReplacementWorkspaces.two(ORIGINAL);
        for (String existing : List.of("0_8_0.cube", "4_8_0.cube.bak", "unexpected.cube")) {
            Path directory = Files.createDirectory(temporary.resolve(existing.replace('.', '_')));
            Path cubes = Files.createDirectory(directory.resolve("cubes"));
            Path saved = cubes.resolve(existing);
            byte[] evidence = new byte[]{1, 2, 3, 4};
            Files.write(saved, evidence);
            assertThrows(IOException.class, () -> ReplacementStoreIdentity.openTwo(directory, pair));
            assertFalse(Files.exists(directory.resolve(ReplacementStoreIdentity.TWO_FILE)));
            assertFalse(Files.exists(directory.resolve(ReplacementStoreIdentity.FILE)));
            assertArrayEquals(evidence, Files.readAllBytes(saved));
        }
    }

    @Test void malformedSetIndexIsNeverRewrittenOrPartiallyRepaired() throws Exception {
        var pair = ReplacementWorkspaces.two(ORIGINAL);
        ReplacementStoreIdentity.openTwo(temporary, pair);
        Path index = temporary.resolve(ReplacementStoreIdentity.TWO_FILE);
        String valid = Files.readString(index);
        List<String> invalid = List.of(valid + "trailing\n", valid.replace("0 8 0\n4 8 0\n", "4 8 0\n0 8 0\n"),
                valid.replace(pair.identity(), "f".repeat(64)), valid.replace("4 8 0\n", "4 8 0\n8 8 0\n"),
                valid.substring(0, valid.length() - 1), "x".repeat(513));
        for (String malformed : invalid) {
            assertNotEquals(valid, malformed);
            Files.writeString(index, malformed);
            byte[] before = Files.readAllBytes(index);
            assertThrows(IOException.class, () -> ReplacementStoreIdentity.openTwo(temporary, pair));
            assertArrayEquals(before, Files.readAllBytes(index));
            try (var entries = Files.list(temporary)) {
                assertEquals(List.of(ReplacementStoreIdentity.TWO_FILE), entries.map(p -> p.getFileName().toString()).sorted().toList());
            }
        }
    }

    @Test void fixedPairCannotIncreaseRollingOwnerOrMeshCapsAtNativeEndpoints() {
        for (int y : List.of(RollingCubePlan.MIN_WINDOW_CENTER_Y, 8, RollingCubePlan.MAX_WINDOW_CENTER_Y)) {
            var pair = ReplacementWorkspaces.two(new CubePos(0, y, 0));
            assertEquals(54, pair.positions().stream().flatMap(p -> pair.dependencies(p).stream()).distinct().count());
            for (CubePos focus : pair.positions()) {
                CubePos current = new CubePos(20, y, 20);
                CubePos candidate = current.offset(1, y == RollingCubePlan.MAX_WINDOW_CENTER_Y ? -1 : 1, 1);
                var plan = RollingCubePlan.create(focus, current, candidate, true, true);
                assertEquals(213, plan.stagedOwners().size());
                assertTrue(plan.stagedTargets().size() <= 47);
            }
        }
        assertEquals(213, RollingCubeActor.MAX_RESIDENT_CUBES);
        assertEquals(55, RollingCubePlan.MAX_STAGED_MESH_GENERATIONS);
        assertEquals(3, RollingCubeActor.MAX_TICKETS);
    }

    @Test void compactCaptureOwnsItsBytesAndProspectiveEditCannotModifyPriorContent() {
        byte[] input = new byte[NativeReplacementCapture.MAX_CELLS];
        var capture = new NativeReplacementCapture(ORIGINAL, input);
        String identity = capture.contentIdentity();
        input[0] = NativeReplacementCapture.DIORITE;
        assertEquals(NativeReplacementCapture.AIR, capture.materialAt(-2, 126, -2));
        byte[] exported = capture.clonedBytes();
        exported[0] = NativeReplacementCapture.STONE;
        assertEquals(identity, capture.contentIdentity());
        assertEquals(NativeReplacementCapture.AIR, capture.materialAt(-2, 126, -2));
        var prospective = capture.withMaterial(15, 143, 15, NativeReplacementCapture.DIORITE);
        assertEquals(0xffdedede, prospective.argbAt(15, 143, 15));
        assertEquals(0, capture.argbAt(15, 143, 15));
        assertEquals(identity, capture.contentIdentity());
        assertNotEquals(identity, prospective.contentIdentity());
        assertEquals(8000, capture.declaredBytes());
        assertThrows(IllegalArgumentException.class, () -> capture.withMaterial(16, 143, 15, NativeReplacementCapture.STONE));
        assertThrows(IllegalArgumentException.class, () -> capture.withMaterial(15, 144, 15, NativeReplacementCapture.STONE));
        assertThrows(IllegalArgumentException.class, () -> capture.withMaterial(Long.MAX_VALUE, 128, 0, NativeReplacementCapture.STONE));
        assertThrows(IllegalArgumentException.class, () -> capture.withMaterial(0, 128, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> new NativeReplacementCapture(ORIGINAL, new byte[7999]));
        byte[] unsupported = new byte[8000]; unsupported[7999] = -1;
        assertThrows(IllegalArgumentException.class, () -> new NativeReplacementCapture(ORIGINAL, unsupported));
    }

    @Test void endpointCapturesKeepExactKnownPaddingWithoutAddingGameplayVolume() {
        for (int y : List.of(RollingCubePlan.MIN_WINDOW_CENTER_Y, RollingCubePlan.MAX_WINDOW_CENTER_Y)) {
            CubePos region = new CubePos(0, y, 0);
            var capture = new NativeReplacementCapture(region, new byte[20 * 19 * 20]);
            assertEquals(7600, capture.cellCount());
            assertEquals(19, capture.maxYExclusive() - capture.minY());
            assertTrue(capture.minY() >= RollingCubePlan.GAMEPLAY_MIN_Y - 1);
            assertTrue(capture.maxYExclusive() <= RollingCubePlan.GAMEPLAY_MAX_Y_EXCLUSIVE + 1);
            assertDoesNotThrow(() -> capture.materialAt(capture.minX(), capture.minY(), capture.minZ()));
            assertThrows(IndexOutOfBoundsException.class, () -> capture.materialAt(capture.minX(), capture.minY() - 1, capture.minZ()));
            assertThrows(IndexOutOfBoundsException.class, () -> capture.materialAt(capture.minX(), capture.maxYExclusive(), capture.minZ()));
            assertThrows(IllegalArgumentException.class, () -> new NativeReplacementCapture(region, new byte[8000]));
        }
    }

    private static ReplacementSetCertificate.Region region(CubePos position, long leaseOffset, long revision, String content) {
        var halo = CubeTickets.box(position, 1, 1, 27).stream().sorted().toList();
        var versions = new ArrayList<RollingCubeActor.CubeVersion>();
        for (int i = 0; i < halo.size(); i++) versions.add(new RollingCubeActor.CubeVersion(halo.get(i), leaseOffset + i + 1,
                halo.get(i).equals(position) ? revision : 0));
        return new ReplacementSetCertificate.Region(position, versions, content);
    }

    private static ReplacementSetCertificate set(List<ReplacementSetCertificate.Region> regions) {
        return new ReplacementSetCertificate(WORLD, 10, 2, STORE, "a".repeat(64), "analytic-provider-v1",
                "b".repeat(64), "air-stone-diorite", "exact-unit", 100, regions);
    }

    @Test void completeSetCannotForgetUnfocusedRegionOrAliasItsRevisionAndCapture() {
        var a = region(ORIGINAL, 0, 1, "c".repeat(64));
        var b = region(ORIGINAL.offset(4, 0, 0), 27, 0, "d".repeat(64));
        var original = set(List.of(a, b));
        assertEquals(2, original.regions().size());
        assertEquals(1, original.editedRegions().size());
        assertEquals(0, original.region(b.position()).revision());
        assertThrows(IllegalArgumentException.class, () -> original.asCertificate(b.position()));
        for (List<ReplacementSetCertificate.Region> malformed : List.of(List.of(a), List.of(b, a), List.of(a, a), List.of(a, b, b)))
            assertThrows(IllegalArgumentException.class, () -> set(malformed));
        assertThrows(IllegalArgumentException.class, () -> set(List.of(a, region(b.position(), 0, 0, b.captureIdentity()))));
        var changedRevision = set(List.of(a, region(b.position(), 27, 1, b.captureIdentity())));
        var changedCapture = set(List.of(a, region(b.position(), 27, 0, "e".repeat(64))));
        for (var changed : List.of(changedRevision, changedCapture)) {
            assertNotEquals(original.identity(), changed.identity());
            assertNotEquals(original.durableIdentity(), changed.durableIdentity());
        }
        var aba = set(List.of(region(a.position(), 100, 1, a.captureIdentity()), region(b.position(), 127, 0, b.captureIdentity())));
        assertNotEquals(original.identity(), aba.identity());
        assertEquals(original.durableIdentity(), aba.durableIdentity());
        assertThrows(UnsupportedOperationException.class, () -> original.regions().clear());
    }

    @Test void setPublicationIdentityFencesWorldEpochResourcesStoreIndexAndPolicy() {
        var source = set(List.of(region(ORIGINAL, 0, 1, "c".repeat(64)), region(ORIGINAL.offset(4, 0, 0), 27, 2, "d".repeat(64))));
        for (int changed = 0; changed < 10; changed++) {
            var altered = new ReplacementSetCertificate(
                    changed == 0 ? new WorldIdentity(8, "analytic", "v1", "fixture", "palette") : source.world(),
                    changed == 1 ? 11 : source.epoch(), changed == 2 ? 3 : source.resourceGeneration(),
                    changed == 3 ? "2f72457a-5797-4e93-90ef-b3d3e655c1c0" : source.storeIdentity(),
                    changed == 4 ? "e".repeat(64) : source.indexIdentity(),
                    changed == 5 ? "provider-v2" : source.providerIdentity(),
                    changed == 6 ? "f".repeat(64) : source.paletteIdentity(),
                    changed == 7 ? "material-v2" : source.materialPolicy(),
                    changed == 8 ? "detail-v2" : source.detailPolicy(),
                    changed == 9 ? 101 : source.generation(), source.regions());
            assertNotEquals(source.identity(), altered.identity(), "changed binding " + changed);
            if (changed == 1 || changed == 2 || changed == 9)
                assertEquals(source.durableIdentity(), altered.durableIdentity());
            else assertNotEquals(source.durableIdentity(), altered.durableIdentity());
        }
    }

    @Test void restartAtEveryDurableWriteCutRequiresConfirmedExactRevision() throws Exception {
        var pair = ReplacementWorkspaces.two(ORIGINAL);
        // 0: before intent; 1: intent only; 2: cube saved; 3: ledger confirmed, ACK not delivered.
        for (int cut = 0; cut <= 3; cut++) {
            Path directory = temporary.resolve("cut-" + cut);
            String storeIdentity;
            try (var store = new FileEditStore(directory, WORLD)) {
                var ledger = ReplacementRevisionLedger.open(directory, pair);
                storeIdentity = ledger.identity().storeIdentity();
                if (cut >= 1) ledger.begin(ORIGINAL, 0);
                if (cut >= 2) store.save(new EditSnapshot(ORIGINAL, 1, Map.of(0, 1)));
                if (cut >= 3) ledger.confirm(ORIGINAL, 1);
            }
            byte[] before = Files.readAllBytes(directory.resolve(ReplacementStoreIdentity.TWO_FILE));
            try (var store = new FileEditStore(directory, WORLD)) {
                if (cut == 1 || cut == 2) {
                    assertThrows(IOException.class, () -> ReplacementRevisionLedger.open(directory, pair));
                } else {
                    var restarted = ReplacementRevisionLedger.open(directory, pair);
                    assertEquals(storeIdentity, restarted.identity().storeIdentity());
                    assertEquals(cut == 3 ? 1 : 0, restarted.expectedRevision(ORIGINAL));
                    restarted.verifyObserved(ORIGINAL, store.load(ORIGINAL).snapshot().revision());
                    restarted.verifyObserved(pair.second(), store.load(pair.second()).snapshot().revision());
                }
            }
            assertArrayEquals(before, Files.readAllBytes(directory.resolve(ReplacementStoreIdentity.TWO_FILE)), "cut " + cut);
        }
    }

    @Test void acknowledgedEditedRegionCannotBecomeCleanWhenItsPrimaryAndBackupDisappear() throws Exception {
        var pair = ReplacementWorkspaces.two(ORIGINAL);
        try (var store = new FileEditStore(temporary, WORLD)) {
            var ledger = ReplacementRevisionLedger.open(temporary, pair);
            for (CubePos position : pair.positions()) {
                for (long revision = 1; revision <= 2; revision++) {
                    ledger.begin(position, revision - 1);
                    store.save(new EditSnapshot(position, revision, Map.of(0, (int) revision)));
                    ledger.confirm(position, revision);
                }
            }
        }
        for (CubePos missing : pair.positions()) {
            try (var store = new FileEditStore(temporary, WORLD)) {
                var ledger = ReplacementRevisionLedger.open(temporary, pair);
                Files.delete(store.primaryPath(missing));
                Files.delete(store.backupPath(missing));
                long observed = store.load(missing).snapshot().revision();
                assertEquals(0, observed, "The underlying store explicitly reports EMPTY after both files disappear");
                assertEquals(2, ledger.expectedRevision(missing));
                assertThrows(IOException.class, () -> ledger.verifyObserved(missing, observed));
                assertThrows(IOException.class, () -> ledger.verifyObserved(missing, 1), "A stale valid backup cannot match the ledger either");
            }
        }
    }

    @Test void pendingLedgerSerializesAllRegionsAndRejectsStaleSkippedOrWrongConfirmations() throws Exception {
        var pair = ReplacementWorkspaces.two(ORIGINAL);
        var ledger = ReplacementRevisionLedger.open(temporary, pair);
        String initial = ledger.durableIdentity();
        var scope = ledger.identity();
        byte[] clean = Files.readAllBytes(temporary.resolve(ReplacementStoreIdentity.TWO_FILE));
        assertThrows(IOException.class, () -> ledger.begin(ORIGINAL, 1));
        assertThrows(IOException.class, () -> ledger.confirm(ORIGINAL, 1));
        assertThrows(IllegalArgumentException.class, () -> ledger.begin(ORIGINAL.offset(1, 0, 0), 0));
        assertArrayEquals(clean, Files.readAllBytes(temporary.resolve(ReplacementStoreIdentity.TWO_FILE)));
        ledger.begin(ORIGINAL, 0);
        String pending = ledger.durableIdentity();
        assertNotEquals(initial, pending);
        byte[] intent = Files.readAllBytes(temporary.resolve(ReplacementStoreIdentity.TWO_FILE));
        assertThrows(IOException.class, () -> ledger.begin(pair.second(), 0));
        assertThrows(IOException.class, () -> ledger.verifyObserved(ORIGINAL, 0));
        assertThrows(IOException.class, () -> ledger.confirm(pair.second(), 1));
        assertThrows(IOException.class, () -> ledger.confirm(ORIGINAL, 2));
        assertArrayEquals(intent, Files.readAllBytes(temporary.resolve(ReplacementStoreIdentity.TWO_FILE)));
        ledger.confirm(ORIGINAL, 1);
        assertNotEquals(initial, ledger.durableIdentity());
        assertNotEquals(pending, ledger.durableIdentity());
        assertEquals(scope, ledger.identity());
        assertEquals(1, ledger.expectedRevision(ORIGINAL));
        assertEquals(0, ledger.expectedRevision(pair.second()));
        assertThrows(IOException.class, () -> ledger.begin(ORIGINAL, 0));
        assertThrows(IOException.class, () -> ledger.confirm(ORIGINAL, 1));
        assertThrows(IOException.class, () -> ledger.verifyObserved(ORIGINAL.offset(1, 0, 0), 1));
    }

    @Test void failedIntentOrConfirmationPermanentlyPoisonsItsInMemoryLedger() throws Exception {
        var pair = ReplacementWorkspaces.two(ORIGINAL);
        for (boolean failConfirmation : List.of(false, true)) {
            Path directory = Files.createDirectory(temporary.resolve("failed-" + failConfirmation));
            var ledger = ReplacementRevisionLedger.open(directory, pair);
            if (failConfirmation) ledger.begin(ORIGINAL, 0);
            Path index = directory.resolve(ReplacementStoreIdentity.TWO_FILE);
            Files.move(index, directory.resolve("preserved-index"));
            Files.createDirectory(index);
            Files.writeString(index.resolve("prevent-replacement"), "retained failure evidence");
            if (failConfirmation) assertThrows(IOException.class, () -> ledger.confirm(ORIGINAL, 1));
            else assertThrows(IOException.class, () -> ledger.begin(ORIGINAL, 0));
            assertThrows(IllegalStateException.class, () -> ledger.expectedRevision(ORIGINAL));
            assertThrows(IllegalStateException.class, ledger::durableIdentity);
            assertThrows(IllegalStateException.class, () -> ledger.verifyObserved(ORIGINAL, 0));
            assertThrows(IllegalStateException.class, () -> ledger.begin(pair.second(), 0));
            assertThrows(IllegalStateException.class, () -> ledger.confirm(ORIGINAL, 1));
            try (var entries = Files.list(directory)) {
                assertTrue(entries.noneMatch(path -> path.getFileName().toString().startsWith(".replacement-set-index-")));
            }
        }
    }

    private static NativeReplacementSetAuthority authority(ReplacementRevisionLedger ledger, long epoch) {
        return new NativeReplacementSetAuthority(WORLD, epoch, ReplacementWorkspaces.two(ORIGINAL),
                ledger.identity().storeIdentity(), ledger.identity().indexIdentity(), "b".repeat(64),
                new RollingCubeActor.ReplacementPolicy("analytic-provider-v1", "air-stone-diorite", "exact-unit", 2));
    }

    private static void observeCapture(NativeReplacementSetAuthority authority, ReplacementSetCertificate.Region region) {
        region.dependencies().forEach(authority::observed);
        assertTrue(authority.captured(region));
    }

    @Test void serialBootstrapCanRetireFirst27OnlyAfterCompleteDurableSeal() throws Exception {
        var pair = ReplacementWorkspaces.two(ORIGINAL);
        var ledger = ReplacementRevisionLedger.open(temporary, pair);
        var authority = authority(ledger, 10);
        var a = region(ORIGINAL, 0, 0, "c".repeat(64));
        var b = region(pair.second(), 27, 0, "d".repeat(64));
        assertNull(authority.prepare());
        observeCapture(authority, a);
        a.dependencies().forEach(version -> authority.retire(version.position()));
        assertEquals(0, authority.observedDependencyCount());
        assertEquals(1, authority.retainedRegionCount());
        assertThrows(IllegalStateException.class, () -> authority.sealBootstrap(ledger));
        assertNull(authority.prepare());
        observeCapture(authority, b);
        b.dependencies().forEach(version -> authority.retire(version.position()));
        assertEquals(0, authority.observedDependencyCount());
        assertEquals(2, authority.retainedRegionCount());
        assertNull(authority.prepare(), "Two metadata vectors with retired capture leases are not an implicit bootstrap seal");
        authority.sealBootstrap(ledger);
        var certificate = authority.prepare();
        assertNotNull(certificate);
        assertFalse(authority.current(certificate.identity()));
        assertTrue(authority.commit(certificate.identity()));
        assertTrue(authority.current(certificate.identity()));
        assertThrows(IllegalStateException.class, () -> authority.sealBootstrap(ledger));
        assertSame(certificate, authority.prepare());
    }

    @Test void writeRevocationAndLeaseAbaCannotReuseOldSetOrPreparedPermit() throws Exception {
        var pair = ReplacementWorkspaces.two(ORIGINAL);
        var ledger = ReplacementRevisionLedger.open(temporary, pair);
        var authority = authority(ledger, 10);
        var original = region(ORIGINAL, 0, 0, "c".repeat(64));
        observeCapture(authority, original);
        observeCapture(authority, region(pair.second(), 27, 0, "d".repeat(64)));
        authority.sealBootstrap(ledger);
        var previous = authority.prepare();
        assertTrue(authority.commit(previous.identity()));
        authority.beforeEdit(ORIGINAL);
        assertFalse(authority.current(previous.identity()));
        assertFalse(authority.canCommit(previous.identity()));
        original.dependencies().forEach(authority::observed);
        assertFalse(authority.captured(original), "An unchanged READY vector cannot repair an uncertain save");
        assertNull(authority.prepare());
        var edited = region(ORIGINAL, 0, 1, "e".repeat(64));
        authority.durableEdit(edited.dependencies().stream().filter(v -> v.position().equals(ORIGINAL)).findFirst().orElseThrow());
        assertTrue(authority.captured(edited));
        var prepared = authority.prepare();
        assertNotNull(prepared);
        var aba = region(ORIGINAL, 100, 1, "e".repeat(64));
        observeCapture(authority, aba);
        assertFalse(authority.canCommit(prepared.identity()));
        var refreshed = authority.prepare();
        assertNotNull(refreshed);
        assertNotEquals(prepared.identity(), refreshed.identity());
        assertEquals(prepared.durableIdentity(), refreshed.durableIdentity());
        assertFalse(authority.canCommit(previous.identity()));
        assertTrue(authority.commit(refreshed.identity()));
    }

    @Test void unfocusedDependencyOrSameRevisionContentChangeRevokesEntireCommittedSet() throws Exception {
        var pair = ReplacementWorkspaces.two(ORIGINAL);
        for (boolean changeContentOnly : List.of(false, true)) {
            Path directory = Files.createDirectory(temporary.resolve("unfocused-" + changeContentOnly));
            var ledger = ReplacementRevisionLedger.open(directory, pair);
            var authority = authority(ledger, 10);
            observeCapture(authority, region(ORIGINAL, 0, 0, "c".repeat(64)));
            var b = region(pair.second(), 27, 0, "d".repeat(64));
            observeCapture(authority, b);
            authority.sealBootstrap(ledger);
            var committed = authority.prepare();
            assertTrue(authority.commit(committed.identity()));
            if (changeContentOnly) {
                var changed = new ReplacementSetCertificate.Region(b.position(), b.dependencies(), "e".repeat(64));
                assertThrows(IllegalStateException.class, () -> authority.captured(changed));
            } else {
                var dependency = b.dependencies().getFirst();
                assertThrows(IllegalStateException.class, () -> authority.observed(new RollingCubeActor.CubeVersion(
                        dependency.position(), dependency.leaseGeneration() + 100, dependency.revision() + 1)));
            }
            assertFalse(authority.current(committed.identity()));
            assertFalse(authority.canCommit(committed.identity()));
            assertNull(authority.prepare());
        }
    }

    @Test void bootstrapFactoryStartsAfterRetired54AndNeverRebasesAReenteredLease() {
        var legacy = new CubeStreamAdmission(WORLD, 10, 213);
        assertEquals(1, legacy.replaceInterest(List.of(ORIGINAL)).requested().getFirst().generation());
        var seeded = CubeStreamAdmission.afterVerifiedBootstrap(WORLD, 10, 213, 54);
        assertEquals(0, seeded.view().metrics().leasesCreated());
        var initial = seeded.replaceInterest(List.of(ORIGINAL)).requested().getFirst();
        assertEquals(55, initial.generation());
        seeded.replaceInterest(List.of());
        var reentered = seeded.replaceInterest(List.of(ORIGINAL)).requested().getFirst();
        assertEquals(56, reentered.generation());
        assertEquals(CubeStreamAdmission.Rejection.RETIRED_LEASE,
                seeded.admit(new CubeStreamAdmission.Header(WORLD, 10, ORIGINAL, initial.generation(), 0)).rejection());
        assertEquals(CubeStreamAdmission.Rejection.WRONG_EPOCH,
                seeded.admit(new CubeStreamAdmission.Header(WORLD, 11, ORIGINAL, reentered.generation(), 0)).rejection());
        for (long invalid : List.of(-1L, 0L, 65537L, Long.MAX_VALUE))
            assertThrows(IllegalArgumentException.class, () -> CubeStreamAdmission.afterVerifiedBootstrap(WORLD, 10, 213, invalid));
        assertEquals(56, seeded.view().leases().getFirst().lease().generation());
    }

    private static int cell(int x, int y, int z) { return ((y + 2) * 20 + z + 2) * 20 + x + 2; }
    private static byte[] emptiedWorkspace() {
        byte[] values = new byte[8000];
        for (int y = -2; y < 18; y++) for (int z = -2; z < 18; z++) for (int x = -2; x < 18; x++)
            if (y <= 0 && !(x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16))
                values[cell(x, y, z)] = NativeReplacementCapture.STONE;
        return values;
    }
    private static int ownedFaces(byte[] values) {
        int count = 0;
        for (int y = -1; y < 17; y++) for (int z = -1; z < 17; z++) for (int x = -1; x < 17; x++) {
            int i = cell(x, y, z);
            if (values[i] == 0) continue;
            for (int offset : new int[]{-1, 1, -20, 20, -400, 400}) if (values[i + offset] == 0) count++;
        }
        return count;
    }
    private static RollingCubeActor.Event next(RollingCubeActor actor, ArrayDeque<RollingCubeActor.Event> queue) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (queue.isEmpty()) {
            queue.addAll(actor.pollEvents(2));
            if (queue.isEmpty()) {
                if (System.nanoTime() >= deadline) fail("Two-workspace actor timed out: " + actor.diagnostics());
                Thread.sleep(1);
            }
        }
        var event = queue.removeFirst();
        if (event instanceof RollingCubeActor.Failure failure) fail(failure.detail());
        return event;
    }

    @Test void actorRefusesProspectivePooledOverflowAndUnsupportedStateBeforeAnyDurableWrite() throws Exception {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, com.mojang.serialization.Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        var palette = EnginePaletteContext.create(registry.freeze());
        var provider = new AnalyticNativeCubeProvider(0, AnalyticNativeCubeProvider.Config.boundedCollision(128), palette);
        var nativeWorld = provider.identity();
        var world = new WorldIdentity(nativeWorld.seed(), nativeWorld.generatorId(), nativeWorld.generatorVersion() + "/rolling-interior-v1",
                nativeWorld.datasetVersion(), nativeWorld.blockPaletteVersion());
        var flat = new DhAnalyticFlatPolicy(world.datasetVersion(), world, 128, 0xff808080);
        var pair = ReplacementWorkspaces.two(ORIGINAL);
        byte[][] values = {emptiedWorkspace(), emptiedWorkspace()};
        int faces = ownedFaces(values[0]) + ownedFaces(values[1]);
        int[] refusedCell = null;
        // Fill the remote B first, leaving the final capacity-increasing cell in active A.
        outer: for (int slot : new int[]{1, 0}) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if ((x + y + z) % 2 != 0) continue;
            int i = cell(x, y, z), adjacent = 0;
            for (int offset : new int[]{-1, 1, -20, 20, -400, 400}) if (values[slot][i + offset] != 0) adjacent++;
            int increase = 6 - 2 * adjacent;
            if (DhReplacementSetComposition.MAX_ANALYTIC_BASE_FACES + faces + increase > 32768) {
                assertEquals(0, slot, "A must contain the next capacity-increasing edit");
                refusedCell = new int[]{x, y, z}; break outer;
            }
            values[slot][i] = NativeReplacementCapture.STONE; faces += increase;
        }
        assertNotNull(refusedCell);
        assertEquals(faces, ownedFaces(values[0]) + ownedFaces(values[1]));
        var captureA = new NativeReplacementCapture(ORIGINAL, values[0]);
        var captureB = new NativeReplacementCapture(pair.second(), values[1]);
        var currentProof = DhAnalyticSetAdmission.assess(flat, pair, List.of(captureA, captureB), () -> {});
        assertTrue(currentProof.admitted(), currentProof.reason());
        long x = refusedCell[0], y = 128 + refusedCell[1], z = refusedCell[2];
        var prospective = captureA.withMaterial(x, y, z, NativeReplacementCapture.STONE);
        var refusedProof = DhAnalyticSetAdmission.assess(flat, pair, List.of(prospective, captureB), () -> {});
        assertFalse(refusedProof.admitted());
        assertNotNull(refusedProof.census());
        assertTrue(refusedProof.census().faceUpperBound() > 32768);
        int air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        int stone = Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        try (var store = new FileEditStore(temporary, world)) {
            var ledger = ReplacementRevisionLedger.open(temporary, pair);
            for (int slot = 0; slot < 2; slot++) {
                var overlay = new java.util.TreeMap<Integer, Integer>();
                for (int by = 0; by < 16; by++) for (int bz = 0; bz < 16; bz++) for (int bx = 0; bx < 16; bx++)
                    overlay.put(CubePos.index(bx, by, bz), values[slot][cell(bx, by, bz)] == 0 ? air : stone);
                CubePos position = pair.positions().get(slot);
                ledger.begin(position, 0); store.save(new EditSnapshot(position, 1, overlay)); ledger.confirm(position, 1);
            }
        }
        Path index = temporary.resolve(ReplacementStoreIdentity.TWO_FILE);
        Path aFile = temporary.resolve("cubes/0_8_0.cube"), bFile = temporary.resolve("cubes/4_8_0.cube");
        byte[] beforeIndex = Files.readAllBytes(index), beforeA = Files.readAllBytes(aFile), beforeB = Files.readAllBytes(bFile);
        var policy = new RollingCubeActor.ReplacementPolicy("analytic-provider-v1", DhReplacementInput.ANALYTIC_MATERIAL_POLICY,
                "exact-owned18-read20-unit-faces-v1", 1, RollingEditPolicy.FULL_CUBE_V1);
        var actor = RollingCubeActor.open(temporary, world, provider, palette, 10, ORIGINAL, policy, flat);
        var queue = new ArrayDeque<RollingCubeActor.Event>();
        try {
            var metadata = assertInstanceOf(RollingCubeActor.StageMetadata.class, next(actor, queue));
            assertEquals(55, metadata.candidateLeases().getFirst().leaseGeneration());
            RollingCubeActor.Event event;
            do { event = next(actor, queue); } while (event instanceof RollingCubeActor.Snapshot);
            var ready = assertInstanceOf(RollingCubeActor.StageReady.class, event);
            String identity = ready.replacementSet().identity();
            assertEquals(RollingCubeActor.Admission.QUEUED, actor.commitPrepared(ready.generation(), identity));
            assertInstanceOf(RollingCubeActor.Committed.class, next(actor, queue));
            var version = ready.versions().stream().filter(v -> v.position().equals(ORIGINAL)).findFirst().orElseThrow();
            for (int state : new int[]{stone, Block.BLOCK_STATE_REGISTRY.getId(Blocks.GLASS.defaultBlockState())}) {
                var request = new RollingCubeActor.EditRequest(world, 10, ORIGINAL, version.leaseGeneration(), 1, x, y, z, state);
                assertEquals(RollingCubeActor.Admission.QUEUED, actor.tryEdit(request));
                var refused = assertInstanceOf(RollingCubeActor.EditAck.class, next(actor, queue), "A refused edit emits no changed snapshot");
                assertEquals(state == stone ? RollingCubeActor.EditStatus.REPLACEMENT_BUDGET : RollingCubeActor.EditStatus.UNSUPPORTED_REPLACEMENT,
                        refused.status());
                assertFalse(refused.applied());
                assertEquals(1, refused.revision());
                assertTrue(actor.replacementCurrent(identity), "Pure refusal preserves the previously committed complete set");
                assertEquals(0, actor.diagnostics().admittedEdits());
                assertArrayEquals(beforeIndex, Files.readAllBytes(index));
                assertArrayEquals(beforeA, Files.readAllBytes(aFile));
                assertArrayEquals(beforeB, Files.readAllBytes(bFile));
                assertFalse(Files.exists(temporary.resolve("cubes/0_8_0.cube.bak")));
                assertFalse(Files.exists(temporary.resolve("cubes/4_8_0.cube.bak")));
            }
        } finally {
            actor.close();
            assertTrue(actor.closed().get(20, TimeUnit.SECONDS).successful());
        }
        try (var store = new FileEditStore(temporary, world)) {
            var ledger = ReplacementRevisionLedger.open(temporary, pair);
            for (CubePos position : pair.positions()) {
                assertEquals(1, store.load(position).snapshot().revision());
                assertEquals(1, ledger.expectedRevision(position));
            }
        }
    }

    private static RollingCubeActor.StageReady boundedReady(RollingCubeActor actor, ArrayDeque<RollingCubeActor.Event> queue) throws Exception {
        var metadata = assertInstanceOf(RollingCubeActor.StageMetadata.class, next(actor, queue));
        assertEquals(125, metadata.candidateLeases().size(), "Two-workspace mode never adds a disjoint native pin");
        assertEquals(27, metadata.renderTargets().size());
        assertTrue(!metadata.candidatePinned() || RollingCubePlan.adjacent(metadata.pinCenter(), metadata.candidateCenter()));
        RollingCubeActor.Event event;
        do { event = next(actor, queue); } while (event instanceof RollingCubeActor.Snapshot);
        var ready = assertInstanceOf(RollingCubeActor.StageReady.class, event);
        assertEquals(125, ready.versions().size());
        return ready;
    }
    private static void commitWithoutRetirement(RollingCubeActor actor, ArrayDeque<RollingCubeActor.Event> queue,
            RollingCubeActor.StageReady ready) throws Exception {
        assertEquals(RollingCubeActor.Admission.QUEUED, actor.commitPrepared(ready.generation(), ready.replacementSet().identity()));
        assertInstanceOf(RollingCubeActor.Committed.class, next(actor, queue));
    }
    private static void assertNoStagedEvent(RollingCubeActor actor, ArrayDeque<RollingCubeActor.Event> queue) throws Exception {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150);
        while (System.nanoTime() < until) {
            queue.addAll(actor.pollEvents(2));
            assertTrue(queue.isEmpty(), "Uncertified disjoint-pin movement must remain deferred");
            assertEquals(RollingCubeActor.Lifecycle.OPEN, actor.diagnostics().lifecycle());
            Thread.sleep(1);
        }
    }

    @Test void queuedLongGoalAndFarPinRestoreCannotCreateUnprovedTwentyEighthNativeTarget() throws Exception {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, com.mojang.serialization.Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        var palette = EnginePaletteContext.create(registry.freeze());
        var provider = new AnalyticNativeCubeProvider(0, AnalyticNativeCubeProvider.Config.boundedCollision(128), palette);
        var nativeWorld = provider.identity();
        var world = new WorldIdentity(nativeWorld.seed(), nativeWorld.generatorId(), nativeWorld.generatorVersion() + "/rolling-interior-v1",
                nativeWorld.datasetVersion(), nativeWorld.blockPaletteVersion());
        var flat = new DhAnalyticFlatPolicy(world.datasetVersion(), world, 128, 0xff808080);
        var policy = new RollingCubeActor.ReplacementPolicy("analytic-provider-v1", DhReplacementInput.ANALYTIC_MATERIAL_POLICY,
                "exact-owned18-read20-unit-faces-v1", 1, RollingEditPolicy.FULL_CUBE_V1);
        var actor = RollingCubeActor.open(temporary, world, provider, palette, 10, ORIGINAL, policy, flat);
        var queue = new ArrayDeque<RollingCubeActor.Event>();
        try {
            var first = boundedReady(actor, queue); commitWithoutRetirement(actor, queue, first);
            assertEquals(RollingCubeActor.Admission.QUEUED, actor.requestCenter(ORIGINAL.offset(5, 0, 0)));
            var adjacent = boundedReady(actor, queue);
            assertEquals(ORIGINAL.offset(1, 0, 0), adjacent.candidateCenter());
            assertTrue(adjacent.pinned());
            commitWithoutRetirement(actor, queue, adjacent);
            assertNoStagedEvent(actor, queue);
            assertEquals(1, actor.diagnostics().queuedGoals(), "The latest goal is retained, not discarded or silently authorized");
            assertEquals(ORIGINAL.offset(1, 0, 0), actor.diagnostics().currentCenter());
            assertEquals(RollingCubeActor.Admission.QUEUED,
                    actor.authorizePinRetirement(adjacent.generation(), adjacent.replacementSet().identity()));
            for (int x = 2; x <= 5; x++) {
                var moving = boundedReady(actor, queue);
                assertEquals(ORIGINAL.offset(x, 0, 0), moving.candidateCenter());
                assertFalse(moving.pinned());
                if (x == 2) assertEquals(RollingCubeActor.Admission.NOT_READY, actor.requestPinRestore(),
                        "A still-near current center cannot authorize repinning while its far candidate is in flight");
                commitWithoutRetirement(actor, queue, moving);
            }
            assertEquals(RollingCubeActor.Admission.NOT_READY, actor.requestPinRestore());
            assertNoStagedEvent(actor, queue);
            assertFalse(actor.diagnostics().currentPinned());
            assertEquals(125, actor.diagnostics().residentCubes());
            assertEquals(RollingCubeActor.Admission.QUEUED, actor.requestCenter(ORIGINAL.offset(1, 0, 0)));
            for (int x = 4; x >= 1; x--) {
                var returning = boundedReady(actor, queue);
                assertEquals(ORIGINAL.offset(x, 0, 0), returning.candidateCenter());
                assertEquals(x == 1, returning.pinned());
                commitWithoutRetirement(actor, queue, returning);
            }
            assertEquals(RollingCubeActor.Admission.QUEUED, actor.requestPinRestore());
            assertNoStagedEvent(actor, queue);
            assertTrue(actor.diagnostics().currentPinned());
            assertEquals(125, actor.diagnostics().residentCubes());
        } finally {
            actor.close(); assertTrue(actor.closed().get(20, TimeUnit.SECONDS).successful());
        }
    }
}
