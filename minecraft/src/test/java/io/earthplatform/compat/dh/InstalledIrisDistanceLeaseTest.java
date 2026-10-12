package io.earthplatform.compat.dh;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Setting-driven admission is additive; none of these tests supplies a caller radius. */
class InstalledIrisDistanceLeaseTest {
    private static final class Fake implements InstalledIrisRuntime.Catalogue, InstalledIrisRuntime.Access {
        int selected = 12, effective = 12, serverLimit = 0;
        boolean singleplayer = true, renderThread = true;
        int hashes, snapshots, passes;
        final Object manager = new Object(), pipeline = new Object(), pack = new Object(), dh = new Object(), options = new Object();
        Map<String, String> strings = InstalledIrisRuntime.STRINGS;
        Map<String, Boolean> booleans = InstalledIrisRuntime.BOOLEANS;
        Runnable onHash = () -> {}, onSnapshot = () -> {}, onPass = () -> {};
        InstalledIrisRuntime.Snapshot substitute;

        @Override public Optional<InstalledIrisRuntime.InstalledMod> installed(String id) {
            String version = switch (id) {
                case "iris" -> InstalledIrisRuntime.IRIS_VERSION;
                case "sodium" -> InstalledIrisRuntime.SODIUM_VERSION;
                default -> null;
            };
            return version == null ? Optional.empty() : Optional.of(new InstalledIrisRuntime.InstalledMod(
                    id, version, Path.of("/installed/" + id + ".jar"), getClass().getClassLoader(), id,
                    URI.create("file:/installed/" + id + ".jar")));
        }
        @Override public int selectedRenderDistance() { return selected; }
        @Override public int effectiveRenderDistance() { return effective; }
        @Override public int serverRenderDistanceLimit() { return serverLimit; }
        @Override public boolean singleplayer() { return singleplayer; }
        @Override public void requireRenderThread() {
            if (!renderThread) throw new IllegalStateException("off render thread");
        }
        InstalledIrisRuntime.Snapshot state() {
            return new InstalledIrisRuntime.Snapshot(manager, pipeline, pack, dh, options, FileSystems.getDefault(),
                    0, InstalledIrisRuntime.PHOTON_NAME, Path.of("/shaderpacks"), effective, strings, booleans,
                    selected, serverLimit, singleplayer);
        }
        @Override public InstalledIrisRuntime.Snapshot snapshot() {
            requireRenderThread(); snapshots++;
            var state = substitute == null ? state() : substitute;
            onSnapshot.run();
            return state;
        }
        @Override public InstalledIrisRuntime.BorrowedPass pass(InstalledIrisRuntime.Snapshot snapshot, boolean shadow, boolean opaque) {
            requireRenderThread(); passes++; onPass.run();
            return new InstalledIrisRuntime.BorrowedPass(new Object(), 44, 8, 77, shadow, opaque, 10, 11);
        }
        InstalledIrisRuntime runtime() {
            return InstalledIrisRuntime.resolve(this, mod -> {}, (name, loader) -> String.class,
                    (mod, type) -> {}, (classes, catalogue) -> this, snapshot -> { hashes++; onHash.run(); });
        }
        void choose(int value) { selected = effective = value; serverLimit = 0; }
    }

    @Test void everySelectedDistanceTwoThroughThirtyTwoIsAdmittedWithoutCallerRadiusOrSettingWrites() {
        for (int distance = 2; distance <= 32; distance++) {
            Fake f = new Fake(); f.choose(distance); var runtime = f.runtime(); var lease = runtime.leaseDistance();
            assertTrue(lease.distanceMode()); assertEquals(distance, lease.selectedRenderDistance());
            assertEquals(distance, lease.effectiveRenderDistance()); assertEquals(0, lease.serverRenderDistanceLimit());
            for (int i = 0; i < 3; i++) { assertSame(lease, runtime.leaseDistance()); assertTrue(lease.current()); }
            assertTrue(lease.dhPass(false, true).opaque());
            assertEquals(1, f.hashes); assertEquals(1, f.passes);
            assertEquals(distance, f.selected); assertEquals(distance, f.effective); assertEquals(0, f.serverLimit);
        }
    }

    @Test void legacyLeaseStillRequiresExactlyEffectiveTwoAndMustNotReturnDistanceModeLease() {
        for (int distance = 2; distance <= 32; distance++) {
            Fake f = new Fake(); f.choose(distance); var runtime = f.runtime();
            var distanceLease = runtime.leaseDistance();
            if (distance == 2) {
                var legacy = runtime.lease(); assertFalse(legacy.distanceMode());
                assertNotSame(legacy, distanceLease); assertFalse(distanceLease.current());
                var nextDistance = runtime.leaseDistance(); assertTrue(nextDistance.distanceMode()); assertFalse(legacy.current());
            } else {
                assertTrue(assertThrows(IllegalStateException.class, runtime::lease).getMessage().contains("must be 2 chunks"));
                assertFalse(distanceLease.current());
            }
        }
        Fake f = new Fake(); f.selected = 24; f.effective = f.serverLimit = 2; f.singleplayer = false;
        var legacy = f.runtime().lease();
        assertFalse(legacy.distanceMode()); assertEquals(24, legacy.selectedRenderDistance());
        assertEquals(2, legacy.effectiveRenderDistance()); assertEquals(2, legacy.serverRenderDistanceLimit());
    }

    @Test void allPinnedServerClampCombinationsAreValidatedAndPreserved() {
        for (int selected = 2; selected <= 32; selected++) {
            for (int server : new int[]{-9, 0, 1, 2, 3, 8, 12, 17, 24, 31, 32, 64}) {
                Fake f = new Fake(); f.singleplayer = false; f.selected = selected; f.serverLimit = server;
                f.effective = server > 0 ? Math.min(selected, server) : selected;
                var runtime = f.runtime();
                if (f.effective < 2) { assertThrows(IllegalStateException.class, runtime::leaseDistance); assertEquals(0, f.hashes); }
                else {
                    var lease = runtime.leaseDistance(); assertEquals(selected, lease.selectedRenderDistance());
                    assertEquals(f.effective, lease.effectiveRenderDistance()); assertEquals(server, lease.serverRenderDistanceLimit());
                }
                assertEquals(selected, f.selected); assertEquals(server, f.serverLimit);
            }
        }
    }

    @Test void selectedTwelveToTwentyFourToEightToTwelveNeverResurrectsOldLease() {
        Fake f = new Fake(); var runtime = f.runtime(); var leases = new ArrayList<InstalledIrisRuntime.Lease>();
        for (int value : new int[]{12, 24, 8, 12}) {
            f.choose(value);
            for (var prior : leases) {
                assertThrows(IllegalStateException.class, () -> prior.dhPass(false, true)); assertFalse(prior.current());
            }
            var next = runtime.leaseDistance(); leases.add(next); assertTrue(next.current());
            assertEquals(value, next.selectedRenderDistance()); assertEquals(value, next.effectiveRenderDistance());
        }
        assertEquals(0, f.passes); assertEquals(4, f.hashes);
        for (int i = 0; i < 3; i++) assertFalse(leases.get(i).current());
    }

    @Test void selectedOnlyEffectiveOnlyServerOnlyAndServerModeChangesRevokeBeforeBorrowing() {
        for (int field = 0; field < 4; field++) {
            Fake f = new Fake(); f.singleplayer = false; f.effective = f.serverLimit = 8;
            var runtime = f.runtime(); var old = runtime.leaseDistance();
            switch (field) {
                case 0 -> f.selected = 24; // Effective remains 8; selected change must still revoke.
                case 1 -> f.effective = 7;
                case 2 -> f.serverLimit = 9;
                case 3 -> f.singleplayer = true;
            }
            assertThrows(IllegalStateException.class, () -> old.dhPass(false, true)); assertFalse(old.current());
            f.selected = 12; f.effective = f.serverLimit = 8; f.singleplayer = false;
            assertFalse(old.current()); assertEquals(0, f.passes);
            assertNotSame(old, runtime.leaseDistance());
        }
    }

    @Test void nonclampingServerLimitChangesStillRevokeLeasePermanently() {
        Fake f = new Fake(); f.serverLimit = 24; var runtime = f.runtime(); var old = runtime.leaseDistance();
        f.serverLimit = 32;
        assertThrows(IllegalStateException.class, () -> old.dhPass(false, true)); assertEquals(0, f.passes);
        assertEquals(12, f.selected); assertEquals(12, f.effective);
        f.serverLimit = 24; assertFalse(old.current()); assertNotSame(old, runtime.leaseDistance());
    }

    @Test void observedAndMutationHookAbaArePermanentForEverySupportedSelection() {
        for (int selected = 2; selected <= 32; selected++) {
            for (boolean observed : new boolean[]{false, true}) {
                Fake f = new Fake(); f.choose(selected); var runtime = f.runtime(); var old = runtime.leaseDistance();
                f.choose(selected == 32 ? 31 : selected + 1);
                if (observed) assertFalse(old.current());
                else InstalledIrisRuntime.invalidateLifecycle("Actual Options render distance mutation before ABA restoration");
                f.choose(selected);
                assertThrows(IllegalStateException.class, () -> old.dhPass(false, true)); assertFalse(old.current());
                assertEquals(0, f.passes); var next = runtime.leaseDistance();
                assertTrue(next.current()); assertTrue(next.lifecycleEpoch() > old.lifecycleEpoch());
            }
        }
    }

    @Test void singleplayerLargerSelectionIsVisiblyPendingUntilEffectiveCatchesUp() {
        for (int distance = 3; distance <= 32; distance++) {
            Fake f = new Fake(); f.choose(distance); f.effective = f.serverLimit = distance - 1;
            var runtime = f.runtime();
            var failure = assertThrows(IllegalStateException.class, runtime::leaseDistance);
            assertTrue(failure.getMessage().contains("pending")); assertTrue(failure.getMessage().contains("selected " + distance));
            assertTrue(failure.getMessage().contains("effective " + (distance - 1)));
            assertEquals(0, f.hashes); assertEquals(distance, f.selected);
            f.serverLimit = f.effective = distance;
            assertEquals(distance, runtime.leaseDistance().selectedRenderDistance()); assertEquals(1, f.hashes);
        }
    }

    @Test void wrongServerClampAndOutOfRangeSelectionRefuseWithoutAttestationOrMutation() {
        for (int[] values : new int[][]{{12, 24, 24}, {24, 12, 0}, {24, 12, -1}, {24, 24, 12},
                {12, 8, 10}, {1, 1, 0}, {0, 0, 0}, {33, 33, 0}, {Integer.MAX_VALUE, 2, 2}, {12, 1, 1}}) {
            Fake f = new Fake(); f.singleplayer = false; f.selected = values[0]; f.effective = values[1]; f.serverLimit = values[2];
            assertThrows(IllegalStateException.class, () -> f.runtime().leaseDistance());
            assertEquals(0, f.hashes); assertEquals(0, f.passes);
            assertEquals(values[0], f.selected); assertEquals(values[1], f.effective); assertEquals(values[2], f.serverLimit);
        }
    }

    @Test void snapshotCannotSpoofActualCatalogueDistancesOrSingleplayerState() {
        for (int field = 0; field < 4; field++) {
            Fake f = new Fake(); f.singleplayer = false; f.selected = 24; f.effective = f.serverLimit = 12;
            var actual = f.state();
            f.substitute = new InstalledIrisRuntime.Snapshot(actual.manager(), actual.pipeline(), actual.pack(), actual.dh(),
                    actual.options(), actual.zip(), actual.reloadVersion(), actual.packName(), actual.directory(),
                    field == 0 ? 24 : actual.renderDistance(), actual.strings(), actual.booleans(),
                    field == 1 ? 12 : actual.selectedRenderDistance(), field == 2 ? 24 : actual.serverRenderDistanceLimit(), field == 3);
            var failure = assertThrows(IllegalStateException.class, () -> f.runtime().leaseDistance());
            assertTrue(failure.getMessage().contains("differs from the shader snapshot")); assertEquals(0, f.hashes);
        }
    }

    @Test void catalogueChangesDuringSnapshotOrPackAttestationRefuseAdmission() {
        Fake f = new Fake(); f.onSnapshot = () -> f.choose(24);
        assertThrows(IllegalStateException.class, () -> f.runtime().leaseDistance()); assertEquals(0, f.hashes);
        for (int mutation = 0; mutation < 4; mutation++) {
            Fake changing = new Fake(); var runtime = changing.runtime(); final int field = mutation;
            changing.onHash = () -> {
                switch (field) {
                    case 0 -> changing.choose(24);
                    case 1 -> { changing.effective = 8; changing.serverLimit = 8; changing.singleplayer = false; }
                    case 2 -> changing.serverLimit = 24;
                    case 3 -> { changing.choose(24); InstalledIrisRuntime.invalidateLifecycle("Setting ABA during hash"); changing.choose(12); }
                }
            };
            assertThrows(IllegalStateException.class, runtime::leaseDistance); assertEquals(1, changing.hashes); assertEquals(0, changing.passes);
        }
    }

    @Test void catalogueChangesDuringPassLookupNeverReturnBorrowedPass() {
        for (boolean aba : new boolean[]{false, true}) {
            Fake f = new Fake(); var runtime = f.runtime(); var lease = runtime.leaseDistance();
            f.onPass = () -> {
                f.choose(24);
                if (aba) { InstalledIrisRuntime.invalidateLifecycle("Actual selected/effective ABA during pass lookup"); f.choose(12); }
            };
            assertThrows(IllegalStateException.class, () -> lease.dhPass(false, true)); assertFalse(lease.current());
            assertEquals(1, f.passes);
        }
    }

    @Test void unchangedPackOptionsArtifactAndRenderThreadRequirementsStillFailClosed() {
        for (String name : InstalledIrisRuntime.BOOLEANS.keySet()) {
            Fake f = new Fake(); var changed = new HashMap<>(f.booleans); changed.put(name, !changed.get(name)); f.booleans = changed;
            assertThrows(IllegalStateException.class, () -> f.runtime().leaseDistance()); assertEquals(0, f.hashes);
        }
        for (String name : InstalledIrisRuntime.STRINGS.keySet()) {
            Fake f = new Fake(); var changed = new HashMap<>(f.strings); changed.remove(name); f.strings = changed;
            assertThrows(IllegalStateException.class, () -> f.runtime().leaseDistance()); assertEquals(0, f.hashes);
        }
        Fake f = new Fake(); var runtime = f.runtime(); f.onHash = () -> { throw new IllegalStateException("wrong mounted pack hash"); };
        for (int i = 0; i < 5; i++) assertThrows(IllegalStateException.class, runtime::leaseDistance);
        assertEquals(1, f.hashes); assertEquals(0, f.passes);
        Fake threaded = new Fake(); var threadedRuntime = threaded.runtime(); var lease = threadedRuntime.leaseDistance();
        threaded.renderThread = false;
        assertThrows(IllegalStateException.class, () -> lease.dhPass(false, true)); assertEquals(0, threaded.passes);
        assertThrows(IllegalStateException.class, threadedRuntime::leaseDistance);
    }

    @Test void compiledInstalledCatalogueReadsActualOptionsAndNeverWritesSettings() throws Exception {
        var catalogue = bytecode("io/earthplatform/compat/dh/NeoForgeIrisCatalogue");
        assertTrue(calls(method(catalogue, "selectedRenderDistance"), "net/minecraft/client/Options", "renderDistance"));
        assertTrue(calls(method(catalogue, "selectedRenderDistance"), "net/minecraft/client/OptionInstance", "get"));
        assertTrue(calls(method(catalogue, "effectiveRenderDistance"), "net/minecraft/client/Options", "getEffectiveRenderDistance"));
        assertTrue(calls(method(catalogue, "serverRenderDistanceLimit"), "java/lang/reflect/Field", "getInt"));
        assertTrue(calls(method(catalogue, "singleplayer"), "net/minecraft/client/Minecraft", "hasSingleplayerServer"));
        var reader = bytecode("io/earthplatform/compat/dh/NeoForgeIrisCatalogue$ServerDistanceField");
        assertTrue(java.util.Arrays.stream(method(reader, "resolve").instructions.toArray())
                .anyMatch(i -> i instanceof LdcInsnNode l && "serverRenderDistance".equals(l.cst)));
        assertTrue(calls(method(reader, "resolve"), "java/lang/Class", "getDeclaredField"));
        for (String name : new String[]{"InstalledIrisRuntime", "InstalledIrisRuntime$Lease", "NeoForgeIrisCatalogue",
                "NeoForgeIrisCatalogue$ServerDistanceField", "ReflectiveIrisAccess"}) {
            for (var method : bytecode("io/earthplatform/compat/dh/" + name).methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
                        assertFalse(call.owner.equals("net/minecraft/client/OptionInstance") && call.name.equals("set"));
                        assertFalse(call.owner.equals("net/minecraft/client/Options") && call.name.startsWith("set"));
                        assertFalse(call.owner.equals("java/lang/reflect/Field") &&
                                (call.name.equals("set") || call.name.equals("setInt") || call.name.equals("setBoolean")));
                    }
                    if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD)
                        assertNotEquals("net/minecraft/client/Options", field.owner);
                }
            }
        }
        assertEquals(0, InstalledIrisRuntime.class.getMethod("leaseDistance").getParameterCount());
    }

    @Test void pinnedMinecraftEffectiveDistanceAbiStillUsesPositiveServerMinimum() throws Exception {
        var options = bytecode("net/minecraft/client/Options");
        var effective = method(options, "getEffectiveRenderDistance");
        assertTrue(calls(effective, "java/lang/Math", "min"));
        assertTrue(calls(effective, "net/minecraft/client/OptionInstance", "get"));
        assertTrue(java.util.Arrays.stream(effective.instructions.toArray())
                .anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals("serverRenderDistance") && f.getOpcode() == Opcodes.GETFIELD));
        assertTrue(java.util.Arrays.stream(effective.instructions.toArray()).anyMatch(i -> i.getOpcode() == Opcodes.IFLE));
    }

    private static ClassNode bytecode(String name) throws Exception {
        try (var input = InstalledIrisDistanceLeaseTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input); var node = new ClassNode(); new ClassReader(input.readAllBytes()).accept(node, 0); return node;
        }
    }
    private static MethodNode method(ClassNode type, String name) {
        return type.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static boolean calls(MethodNode method, String owner, String name) {
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
        return false;
    }
}
