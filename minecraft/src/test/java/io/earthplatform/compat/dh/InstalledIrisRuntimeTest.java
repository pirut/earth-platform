package io.earthplatform.compat.dh;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InstalledIrisRuntimeTest {
    private static final class Borrowed {
        int closes, frees, binds;
        public void close() { closes++; }
        public void free() { frees++; }
        public void bind() { binds++; }
    }
    private static final class Fake implements InstalledIrisRuntime.Catalogue, InstalledIrisRuntime.Access {
        final Map<String, InstalledIrisRuntime.InstalledMod> mods = new HashMap<>();
        int artifacts, origins, loaded, factories, hashes, snapshots, passes;
        boolean denyArtifact, denyOrigin, renderThread = true, failSnapshot;
        Runnable onHash = () -> {}, onPass = () -> {};
        final Borrowed program = new Borrowed();
        int framebuffer = 77;
        InstalledIrisRuntime.Snapshot state = InstalledIrisRuntimeTest.snapshot(new Object(), new Object(), new Object(), new Object(), new Object(), 0,
                InstalledIrisRuntime.STRINGS, InstalledIrisRuntime.BOOLEANS, 2);
        Fake() {
            mods.put("iris", mod("iris", InstalledIrisRuntime.IRIS_VERSION));
            mods.put("sodium", mod("sodium", InstalledIrisRuntime.SODIUM_VERSION));
        }
        @Override public Optional<InstalledIrisRuntime.InstalledMod> installed(String id) { return Optional.ofNullable(mods.get(id)); }
        @Override public int effectiveRenderDistance() { return 2; }
        @Override public void requireRenderThread() { if (!renderThread) throw new IllegalStateException("off render thread"); }
        @Override public InstalledIrisRuntime.Snapshot snapshot() {
            requireRenderThread(); snapshots++;
            if (failSnapshot) throw new IllegalStateException("pipeline destroyed");
            return state;
        }
        @Override public InstalledIrisRuntime.BorrowedPass pass(InstalledIrisRuntime.Snapshot state, boolean shadow, boolean opaque) {
            requireRenderThread(); passes++; onPass.run();
            return new InstalledIrisRuntime.BorrowedPass(program, 44, 8, framebuffer, shadow, opaque, 10, 11);
        }
        InstalledIrisRuntime runtime() {
            return InstalledIrisRuntime.resolve(this,
                    mod -> { artifacts++; if (denyArtifact) throw new IOException("wrong hash"); },
                    (name, loader) -> { loaded++; return String.class; },
                    (mod, type) -> { origins++; if (denyOrigin) throw new IOException("wrong origin"); },
                    (classes, catalogue) -> {
                        assertEquals(InstalledIrisRuntime.IRIS_CLASSES.size() + 1, origins);
                        assertEquals(2, artifacts); factories++; return this;
                    },
                    value -> { hashes++; onHash.run(); });
        }
    }
    private static InstalledIrisRuntime.InstalledMod mod(String id, String version) {
        return new InstalledIrisRuntime.InstalledMod(id, version, Path.of("/installed/" + id + ".jar"),
                InstalledIrisRuntimeTest.class.getClassLoader(), id, URI.create("file:/installed/" + id + ".jar"));
    }
    private static InstalledIrisRuntime.Snapshot snapshot(Object manager, Object pipeline, Object pack, Object dh, Object options,
            int version, Map<String, String> strings, Map<String, Boolean> booleans, int distance) {
        return new InstalledIrisRuntime.Snapshot(manager, pipeline, pack, dh, options, FileSystems.getDefault(), version,
                InstalledIrisRuntime.PHOTON_NAME, Path.of("/shaderpacks"), distance, strings, booleans);
    }
    private static InstalledIrisRuntime.Snapshot changed(InstalledIrisRuntime.Snapshot s, int field) {
        return snapshot(field == 0 ? new Object() : s.manager(), field == 1 ? new Object() : s.pipeline(),
                field == 2 ? new Object() : s.pack(), field == 3 ? new Object() : s.dh(), field == 4 ? new Object() : s.options(),
                field == 5 ? s.reloadVersion() + 1 : s.reloadVersion(), s.strings(), s.booleans(), field == 6 ? 4 : 2);
    }

    @Test void absentAndWrongVersionFailBeforeClassLoadingOrInitialization() {
        for (String id : new String[]{"iris", "sodium"}) {
            Fake f = new Fake(); f.mods.remove(id);
            assertTrue(assertThrows(IllegalStateException.class, f::runtime).getMessage().contains("absent"));
            assertEquals(0, f.loaded); assertEquals(0, f.factories);
            f = new Fake(); f.mods.put(id, mod(id, "future-version"));
            assertTrue(assertThrows(IllegalStateException.class, f::runtime).getMessage().contains("version"));
            assertEquals(0, f.loaded); assertEquals(0, f.factories);
        }
    }
    @Test void hashesAndAllClassOriginsAreVerifiedBeforeRuntimeFactory() {
        Fake f = new Fake(); f.denyArtifact = true;
        assertThrows(IllegalStateException.class, f::runtime); assertEquals(0, f.loaded); assertEquals(0, f.factories);
        f = new Fake(); f.denyOrigin = true;
        assertThrows(IllegalStateException.class, f::runtime); assertEquals(1, f.loaded); assertEquals(0, f.factories);
        f = new Fake(); assertTrue(f.runtime().installationVerified());
        assertEquals(InstalledIrisRuntime.IRIS_CLASSES.size() + 1, f.origins); assertEquals(1, f.factories);
        assertEquals(0, f.snapshots); // Resolving installation does not activate/read the live shader pipeline.
    }
    @Test void unsupportedRendererAndMissingAuthoritativeModuleAreRejected() {
        for (String id : new String[]{"oculus", "embeddium"}) {
            Fake f = new Fake(); f.mods.put(id, mod(id, "1"));
            assertThrows(IllegalStateException.class, f::runtime); assertEquals(0, f.loaded);
        }
        Fake f = new Fake(); var m = f.mods.get("iris");
        f.mods.put("iris", new InstalledIrisRuntime.InstalledMod(m.id(), m.version(), m.path(), m.loader(), "", m.moduleLocation()));
        assertThrows(IllegalStateException.class, f::runtime); assertEquals(0, f.loaded);
    }
    @Test void currentAndRepeatedLeaseReadNeverHashOrCloseOrModifyBorrowedResources() {
        Fake f = new Fake(); var runtime = f.runtime(); var lease = runtime.lease();
        for (int i = 0; i < 1000; i++) { assertTrue(lease.current()); assertSame(lease, runtime.lease()); }
        assertEquals(1, f.hashes); assertEquals(2, f.artifacts);
        var pass = lease.dhPass(false, true);
        assertSame(f.program, pass.programIdentity()); assertEquals(44, pass.programId()); assertEquals(8, pass.modelOffsetLocation());
        assertEquals(77, pass.framebufferId()); assertTrue(pass.opaque()); assertFalse(pass.shadow());
        assertEquals(10, pass.projectionLocation()); assertEquals(11, pass.modelViewLocation());
        f.framebuffer = 88;
        assertEquals(88, lease.dhPass(false, false).framebufferId()); assertEquals(77, pass.framebufferId());
        assertEquals(0, f.program.closes + f.program.frees + f.program.binds);
        assertEquals(InstalledIrisRuntime.STRINGS, f.state.strings()); assertEquals(InstalledIrisRuntime.BOOLEANS, f.state.booleans());
    }
    @Test void everyIdentityChangeRevokesOldLeasePermanentlyIncludingObservedAba() {
        for (int field = 0; field < 7; field++) {
            Fake f = new Fake(); var runtime = f.runtime(); var lease = runtime.lease(); var original = f.state;
            f.state = changed(f.state, field); assertFalse(lease.current());
            f.state = original; assertFalse(lease.current());
            var next = runtime.lease(); assertNotSame(lease, next); assertTrue(next.current()); assertFalse(lease.current());
        }
    }
    @Test void lifecycleHookRevokesUnobservedAbaAndSameObjectReload() {
        Fake f = new Fake(); var runtime = f.runtime(); var lease = runtime.lease();
        InstalledIrisRuntime.invalidateLifecycle("A to B to A occurred between reads");
        assertFalse(lease.current()); var next = runtime.lease(); assertTrue(next.current());
        assertTrue(next.lifecycleEpoch() > lease.lifecycleEpoch()); assertSame(lease.pipelineIdentity(), next.pipelineIdentity());
        assertSame(lease.packIdentity(), next.packIdentity()); assertEquals(lease.reloadVersion(), next.reloadVersion());
        assertFalse(lease.current());
    }
    @Test void missingOrWrongOptionsAndRenderDistanceFailWithoutHashingOrMutation() {
        for (String name : InstalledIrisRuntime.BOOLEANS.keySet()) {
            Fake f = new Fake(); var values = new HashMap<>(f.state.booleans()); values.remove(name);
            f.state = snapshot(f.state.manager(), f.state.pipeline(), f.state.pack(), f.state.dh(), f.state.options(), 0, f.state.strings(), values, 2);
            assertThrows(IllegalStateException.class, () -> f.runtime().lease()); assertEquals(0, f.hashes);
            assertFalse(values.containsKey(name));
        }
        for (String name : InstalledIrisRuntime.STRINGS.keySet()) {
            Fake f = new Fake(); var values = new HashMap<>(f.state.strings()); values.put(name, "16.0");
            f.state = snapshot(f.state.manager(), f.state.pipeline(), f.state.pack(), f.state.dh(), f.state.options(), 0, values, f.state.booleans(), 2);
            assertThrows(IllegalStateException.class, () -> f.runtime().lease()); assertEquals(0, f.hashes); assertEquals("16.0", values.get(name));
        }
        assertThrows(IllegalStateException.class, () -> ReflectiveIrisAccess.requireOption(Map.of("SHADOW", true), "UNKNOWN"));
        assertDoesNotThrow(() -> ReflectiveIrisAccess.requireOption(Map.of("SHADOW", false), "SHADOW"));
        Fake f = new Fake(); f.state = changed(f.state, 6);
        assertThrows(IllegalStateException.class, () -> f.runtime().lease()); assertEquals(0, f.hashes);
    }
    @Test void changedEffectiveOptionRevokesEvenWhenOptionObjectIdentityDoesNotChange() {
        Fake f = new Fake(); var runtime = f.runtime(); var lease = runtime.lease(); var values = new HashMap<>(f.state.booleans());
        values.put("TAA", true);
        f.state = snapshot(f.state.manager(), f.state.pipeline(), f.state.pack(), f.state.dh(), f.state.options(), 0, f.state.strings(), values, 2);
        assertFalse(lease.current()); assertThrows(IllegalStateException.class, () -> lease.dhPass(false, true)); assertEquals(0, f.passes);
    }
    @Test void generationRacesDuringAdmissionAndPassNeverReturnBorrowedObjects() {
        Fake f = new Fake(); var runtime = f.runtime(); f.onHash = () -> InstalledIrisRuntime.invalidateLifecycle("reload during hash");
        assertThrows(IllegalStateException.class, runtime::lease);
        f = new Fake(); runtime = f.runtime(); var lease = runtime.lease();
        f.onPass = () -> InstalledIrisRuntime.invalidateLifecycle("destroy during pass lookup");
        assertThrows(IllegalStateException.class, () -> lease.dhPass(false, true)); assertFalse(lease.current());
        assertEquals(0, f.program.closes + f.program.frees + f.program.binds);
    }
    @Test void failingPackAttestationIsCachedUntilLifecycleChanges() {
        Fake f = new Fake(); var runtime = f.runtime(); f.onHash = () -> { throw new IllegalStateException("wrong mounted pack hash"); };
        for (int i = 0; i < 20; i++) assertThrows(IllegalStateException.class, runtime::lease);
        assertEquals(1, f.hashes);
        InstalledIrisRuntime.invalidateLifecycle("user actually reloads");
        assertThrows(IllegalStateException.class, runtime::lease); assertEquals(2, f.hashes);
    }
    @Test void shadowPassMissingProgramAndWrongThreadFailClosed() {
        Fake f = new Fake(); var runtime = f.runtime(); var lease = runtime.lease();
        assertThrows(IllegalStateException.class, () -> lease.dhPass(true, true)); assertEquals(0, f.passes);
        assertThrows(IllegalStateException.class, () -> new InstalledIrisRuntime.BorrowedPass(new Object(), 0, 8, 77, false, true, 10, 11));
        assertThrows(IllegalStateException.class, () -> new InstalledIrisRuntime.BorrowedPass(new Object(), 44, -1, 77, false, true, 10, 11));
        assertThrows(IllegalStateException.class, () -> new InstalledIrisRuntime.BorrowedPass(new Object(), 44, 8, 0, false, true, 10, 11));
        assertThrows(IllegalStateException.class, () -> new InstalledIrisRuntime.BorrowedPass(new Object(), 44, 8, 77, false, true, -1, 11));
        assertThrows(IllegalStateException.class, () -> new InstalledIrisRuntime.BorrowedPass(new Object(), 44, 8, 77, false, true, 10, -1));
        f.renderThread = false; assertFalse(lease.current()); assertThrows(IllegalStateException.class, runtime::lease);
    }
    @Test void releaseOldLeaseCannotClearNewerLeaseAndRetirementNeverClosesUpstream() {
        Fake f = new Fake(); var runtime = f.runtime(); var old = runtime.lease();
        runtime.release(old); assertFalse(old.current());
        var next = runtime.lease(); runtime.release(old); assertTrue(next.current());
        runtime.release(null); assertTrue(next.current()); runtime.releaseAllReferences();
        assertFalse(next.current()); assertFalse(old.current());
        assertTrue(runtime.lease().current()); assertFalse(next.current());
        assertEquals(0, f.program.closes + f.program.frees + f.program.binds);
    }
    @Test void namedModuleLocationIsAuthoritativeAndForeignOriginsAreRejected() throws Exception {
        Module module = String.class.getModule();
        URI location = module.getLayer().configuration().findModule(module.getName()).orElseThrow().reference().location().orElseThrow();
        var exact = new InstalledIrisRuntime.InstalledMod("iris", InstalledIrisRuntime.IRIS_VERSION, Path.of("/a.jar"),
                getClass().getClassLoader(), module.getName(), location);
        assertDoesNotThrow(() -> InstalledIrisRuntime.verifyOrigin(exact, String.class));
        assertThrows(IOException.class, () -> InstalledIrisRuntime.verifyOrigin(mod("iris", InstalledIrisRuntime.IRIS_VERSION), String.class));
        var foreign = new InstalledIrisRuntime.InstalledMod(exact.id(), exact.version(), exact.path(), exact.loader(), exact.moduleName(), URI.create("file:/elsewhere.jar"));
        assertThrows(IOException.class, () -> InstalledIrisRuntime.verifyOrigin(foreign, String.class));
    }
    @Test void unsupportedSkylightOrMissingOptionRefusesAdmissionWithoutChangingNativeShadowOrFade() {
        assertEquals(Boolean.FALSE,InstalledIrisRuntime.BOOLEANS.get("SH_SKYLIGHT"));
        assertEquals(Boolean.TRUE,InstalledIrisRuntime.BOOLEANS.get("SHADOW"));
        assertEquals(Map.of("DH_OVERDRAW_DISTANCE","24.0","DH_OVERDRAW_FADE_LENGTH","4.0"),InstalledIrisRuntime.STRINGS);
        for(boolean missing:new boolean[]{false,true}){
            Fake f=new Fake();var values=new HashMap<>(f.state.booleans());
            if(missing)values.remove("SH_SKYLIGHT");else values.put("SH_SKYLIGHT",true);
            f.state=snapshot(f.state.manager(),f.state.pipeline(),f.state.pack(),f.state.dh(),f.state.options(),0,f.state.strings(),values,2);
            var failure=assertThrows(IllegalStateException.class,()->f.runtime().lease());
            assertTrue(failure.getMessage().contains("SH_SKYLIGHT"));assertEquals(0,f.hashes);assertEquals(0,f.passes);
            assertEquals(!missing,values.containsKey("SH_SKYLIGHT"));assertEquals(Boolean.TRUE,values.get("SHADOW"));
        }
    }
    @Test void skylightChangeOnSameOptionsObjectRevokesObservedAndHookedAbaWithoutBorrowingPass() {
        for(boolean observe:new boolean[]{false,true}){
            Fake f=new Fake();var runtime=f.runtime();var lease=runtime.lease();var original=f.state;
            var values=new HashMap<>(original.booleans());values.put("SH_SKYLIGHT",true);
            f.state=snapshot(original.manager(),original.pipeline(),original.pack(),original.dh(),original.options(),0,original.strings(),values,2);
            if(observe)assertFalse(lease.current());else InstalledIrisRuntime.invalidateLifecycle("actual option reload before restored value");
            f.state=original;assertFalse(lease.current());assertThrows(IllegalStateException.class,()->lease.dhPass(false,true));assertEquals(0,f.passes);
            var current=runtime.lease();assertTrue(current.current());assertNotSame(lease,current);
            assertEquals(0,f.program.closes+f.program.frees+f.program.binds);
        }
    }
}
