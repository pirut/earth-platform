package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.scene.NativeVisualStream;
import io.earthplatform.minecraft.visual.NativeVisualStamp;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import io.earthplatform.minecraft.enginebridge.ReplacementCertificate;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import io.earthplatform.minecraft.lod.DhVoxelReplacementGeometry;
import io.earthplatform.minecraft.lod.DhPreparedLodView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static io.earthplatform.minecraft.coregate.NativeDistanceProgressWatchdog.Lane.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes copied production host method bytecode, retaining its real control flow, watchdog,
 * packet/state checks, failure checkpoint, and closeCurrent ownership handling. Substitutions:
 * clock, Minecraft connection/pause/main-thread getters, producer observations, network/logging,
 * Session.distanceMode/report and physical close. No GL, successful publication, or actual
 * renderer cleanup is claimed. Optional host descriptors require shaderCompatibilityTest.
 */
class NativeDistanceProgressHostAdversarialTest {
    private static final String BASE = "io/earthplatform/minecraft/coregate/";
    private static final String ROOT = BASE + "RollingGateClient", SESSION = ROOT + "$Session";
    private static final String SELF = BASE + "NativeDistanceProgressHostAdversarialTest";
    private static final String GENERATED = BASE + "DistanceProgressExecutableHostFixture";
    private static final long SECOND = 1_000_000_000L;
    private static final String HASH = "a".repeat(64);
    private static final WorldIdentity WORLD = new WorldIdentity(1, "progress-fixture", "v1", "source", "palette");
    private static final CubePos CENTER = new CubePos(0, 8, 0);
    private static final ThreadLocal<Inputs> INPUTS = new ThreadLocal<>();
    private static Class<?> fixtureClass;
    private static Class<?> serverFixtureClass;

    private static final class Inputs {
        long now, resources = 3;
        boolean paused, physicalCloseFails;
        ClientPacketListener connection;
        Object level;
        NativeVisualStream.Progress work;
        com.google.gson.JsonObject far = new com.google.gson.JsonObject();
        int samples, closes, reports;
        final List<String> effects = new ArrayList<>();
        final List<CustomPacketPayload> packets = new ArrayList<>();
        NativeDistanceProgressWatchdog.Checkpoint atPhysicalClose;
        NativeDistanceProgressState serverState;
        int serverTick;
    }

    private static NativeVisualStamp stamp(long actor, long resources, long generation) {
        return new NativeVisualStamp(WORLD, new UUID(1, 2), actor, resources, generation, 0,
                24, 24, 0, HASH, HASH, HASH, HASH, HASH);
    }
    private static NativeVisualStream.Progress work(NativeVisualStamp stamp, long count) {
        return new NativeVisualStream.Progress(stamp, CENTER, HASH, count, count, count, count,
                0, 0, SOURCE_COLUMNS.cap(), PLAN_TILES.cap(), 3072, HALO_ROWS.cap(), FACE_SCANS.cap(), 3072, false);
    }
    private static NativeDistanceProgressState state() {
        var state = new NativeDistanceProgressState(NativeDistanceChangePayload.request(41, 1, 24, 24, 0), false, 0, false, 0);
        assertTrue(state.header(42, 3, 1, HASH, ""));
        return state;
    }
    private static Object fixture(Inputs inputs, NativeDistanceProgressState state) throws Throwable {
        INPUTS.set(inputs);
        var type = fixtureType();
        for (var field : type.getFields()) if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
            if (!field.getType().isPrimitive()) field.set(null, null);
            else if (field.getType() == boolean.class) field.setBoolean(null, false);
            else if (field.getType() == long.class) field.setLong(null, 0);
            else if (field.getType() == int.class) field.setInt(null, 0);
        }
        inputs.connection = (ClientPacketListener) uninitialized(ClientPacketListener.class);
        var value = type.getConstructor().newInstance();
        put(null, "distanceProgress", state); put(null, "distanceProgressConnection", inputs.connection);
        put(null, "current", value);
        put(value, "session_epoch", 42L); put(value, "session_publication", 1L);
        put(value, "session_world", WORLD); put(value, "session_digest", HASH);
        put(value, "session_visualRequest", 1L);
        put(value, "session_proposedReplacement", uninitialized(DhReplacementInput.class));
        put(value, "session_proposedCertificate", uninitialized(ReplacementCertificate.class));
        put(value, "session_visible", false); put(value, "session_pending", true);
        return value;
    }

    @Test void actualPumpExpiresBeforeSamplingLateWorkerOrTouchingPublication() throws Throwable {
        var inputs = new Inputs(); inputs.now = 120 * SECOND; inputs.physicalCloseFails = true;
        try {
            var state = state(); var fixture = fixture(inputs, state);
            put(fixture, "session_visualStream", uninitialized(NativeVisualStream.class));
            inputs.work = work(stamp(42, 3, 1), 10);
            assertEquals(true, call("pumpDistanceProgress"));
            assertEquals(0, inputs.samples, "Expiry precedes reading even genuine newly completed work");
            assertEquals("useful-work-idle", inputs.atPhysicalClose.failure());
            assertEquals(0, inputs.atPhysicalClose.rawVector()[SOURCE_COLUMNS.ordinal()]);
            assertEquals(List.of("report", "close"), inputs.effects);
            assertSame(fixture, get(null, "current"), "Failed physical cleanup retains the exact owning session");
            assertFalse((boolean) get(fixture, "session_visible"));
            assertTrue((boolean) get(fixture, "session_pending"));
            assertFalse((boolean) get(fixture, "session_pairSent"));
            assertEquals(true, call("pumpDistanceProgress"));
            assertEquals(2, inputs.closes); assertEquals(1, inputs.reports);
            assertSame(inputs.atPhysicalClose, get(null, "lastDistanceProgress"));
        } finally { INPUTS.remove(); }
    }

    @Test void explicitLogoutSnapshotsAndStartsCleanupImmediatelyWithoutWaitingForDeadline() throws Throwable {
        var inputs = new Inputs(); inputs.physicalCloseFails = true;
        try {
            var state = state(); state.credit(FACE_SCANS, 4096);
            var fixture = fixture(inputs, state);
            call("logout", new Object[]{null});
            assertEquals(1, inputs.closes);
            assertEquals("disconnected", inputs.atPhysicalClose.failure());
            assertEquals(4096, inputs.atPhysicalClose.rawVector()[FACE_SCANS.ordinal()]);
            assertEquals(0, inputs.atPhysicalClose.activeNanos());
            assertSame(fixture, get(null, "current"));
            assertNull(get(null, "distanceProgress"));
            inputs.physicalCloseFails = false;
            assertEquals(true, call("closeCurrent", "retry physical cleanup"));
            assertNull(get(null, "current"));
            assertEquals(4096, ((NativeDistanceProgressWatchdog.Checkpoint) get(null, "lastDistanceProgress")).rawVector()[FACE_SCANS.ordinal()]);
        } finally { INPUTS.remove(); }
    }

    @Test void connectionLossDetectedByActualPumpClosesImmediately() throws Throwable {
        var inputs = new Inputs();
        try {
            var state = state(); fixture(inputs, state); inputs.connection = null;
            assertEquals(true, call("pumpDistanceProgress"));
            assertEquals(1, inputs.closes);
            assertEquals("disconnected", inputs.atPhysicalClose.failure());
            assertTrue(inputs.packets.isEmpty(), "Do not send FAILED to a missing connection");
        } finally { INPUTS.remove(); }
    }

    @Test void actualPauseEventChargesPreviousStateAndLongPauseDoesNotBecomeIdle() throws Throwable {
        var inputs = new Inputs();
        try {
            var state = state(); fixture(inputs, state); put(null, "current", null);
            inputs.now = 5 * SECOND; inputs.paused = true; call("distancePause", new Object[]{null});
            inputs.now = 1_000 * SECOND; call("pumpDistanceProgress");
            inputs.paused = false; call("distancePause", new Object[]{null});
            inputs.now = 1_001 * SECOND; call("pumpDistanceProgress");
            assertEquals(6 * SECOND, state.checkpoint().activeNanos());
            assertFalse(state.watchdog.failed()); assertEquals(0, inputs.closes);
        } finally { INPUTS.remove(); }
    }

    @Test void actualPacketHandlerExpiresBeforeAcceptingLateAuthoritativeReceipt() throws Throwable {
        var inputs = new Inputs(); inputs.now = 120 * SECOND;
        try {
            var state = state(); fixture(inputs, state);
            long[] counters = new long[NativeDistanceProgressWatchdog.LANE_COUNT]; counters[ACTOR_SNAPSHOTS.ordinal()] = 188;
            var late = new NativeDistanceProgressPayload(41, 41, 1, 24, 24, 0, 42, 3, 1, HASH, "", 1, 1, true, counters);
            call("nativeDistanceProgress", late);
            assertEquals(1, inputs.closes); assertEquals("useful-work-idle", state.watchdog.failure());
            assertEquals(0, state.checkpoint().rawVector()[ACTOR_SNAPSHOTS.ordinal()]);
            assertEquals(-1, state.checkpoint().serverObservation());
        } finally { INPUTS.remove(); }
    }

    @Test void actualHandlerIgnoresWrongOriginAndStaleSerialWithoutGrantingPeerCredit() throws Throwable {
        var inputs = new Inputs(); inputs.now = SECOND;
        try {
            var state = state(); fixture(inputs, state);
            call("nativeDistanceProgress", state.packet(999));
            var stale = new NativeDistanceProgressPayload(41, 41, 2, 24, 24, 0, 42, 3, 1, HASH, "", 1, 999, true, new long[NativeDistanceProgressWatchdog.LANE_COUNT]);
            call("nativeDistanceProgress", stale);
            assertEquals(0, state.checkpoint().lastPeerStamp());
            assertEquals(-1, state.checkpoint().serverObservation());
            assertEquals(0, inputs.closes);
        } finally { INPUTS.remove(); }
    }

    @Test void actualPumpCreditsSourcePlanHaloAndScansBeforeFirstUploadButNeverReadiness() throws Throwable {
        var inputs = new Inputs(); inputs.now = SECOND;
        try {
            var state = state(); var fixture = fixture(inputs, state);
            put(fixture, "session_visualStream", uninitialized(NativeVisualStream.class));
            inputs.work = work(stamp(42, 3, 1), 1);
            assertEquals(false, call("pumpDistanceProgress"));
            for (var lane : new NativeDistanceProgressWatchdog.Lane[]{SOURCE_COLUMNS, PLAN_TILES, HALO_ROWS, FACE_SCANS})
                assertEquals(1, state.checkpoint().rawVector()[lane.ordinal()]);
            assertEquals(0, state.checkpoint().rawVector()[UPLOADED_PIECES.ordinal()]);
            assertFalse((boolean) get(fixture, "session_visible"));
            assertTrue((boolean) get(fixture, "session_pending"));
            assertFalse((boolean) get(fixture, "session_pairSent"));
            assertTrue(((String) get(fixture, "session_status")).contains("/earth leave"));
            inputs.now = 60 * SECOND;
            assertEquals(false, call("pumpDistanceProgress"));
            assertEquals(59 * SECOND, state.checkpoint().usefulIdleNanos(), "Polling the same completed prefix is not new work");
        } finally { INPUTS.remove(); }
    }

    @Test void actualPumpRejectsStaleActorAndResourcesBeforeAnyCompletedCounter() throws Throwable {
        for (var stamp : List.of(stamp(43, 3, 1), stamp(42, 4, 1), stamp(42, 3, 2))) {
            var inputs = new Inputs(); inputs.now = SECOND;
            try {
                var state = state(); var fixture = fixture(inputs, state);
                put(fixture, "session_visualStream", uninitialized(NativeVisualStream.class)); inputs.work = work(stamp, 1);
                assertEquals(true, call("pumpDistanceProgress"));
                assertEquals("native-work-identity-changed", state.watchdog.failure());
                assertEquals(0, state.checkpoint().rawVector()[SOURCE_COLUMNS.ordinal()]);
                assertEquals(1, inputs.closes);
            } finally { INPUTS.remove(); }
        }
    }

    @Test void ordinaryWorldReturnClearsEpisodeOnlyAfterPhysicalSessionHasGone() throws Throwable {
        var inputs = new Inputs();
        try {
            var state = state(); var fixture = fixture(inputs, state);
            inputs.level = new Object();
            inputs.physicalCloseFails = true; inputs.connection = null;
            assertEquals(true, call("pumpDistanceProgress"));
            assertSame(fixture, get(null, "current"));
            assertSame(state, get(null, "distanceProgress"), "Ordinary-world return cannot discard physical owner still draining");
            inputs.physicalCloseFails = false;
            assertEquals(true, call("closeCurrent", "retry after leave"));
            assertNull(get(null, "current"));
            assertEquals(false, call("pumpDistanceProgress"));
            assertNull(get(null, "distanceProgress"));
            assertNull(get(null, "distanceProgressTransition"));
            assertNull(get(null, "distanceTransition"));
            assertNotNull(get(null, "lastDistanceProgress"));
        } finally { INPUTS.remove(); }
    }

    @Test void repeatedDhCandidateOrOversizedUploadNeverBecomesUsefulWork() throws Throwable {
        for (String fault : List.of("host-generation", "highestRequestedCoverageGeneration", "commits", "oversized-bytes")) {
            var inputs = new Inputs(); inputs.now = SECOND;
            try {
                var state = state(); var fixture = fixture(inputs, state);
                put(fixture, "session_far", uninitialized(DhPreparedLodView.class));
                inputs.far.addProperty("uploadedBufferBytes", fault.equals("oversized-bytes") ? DH_UPLOADED_BYTES.cap() + 1 : 4096);
                if (fault.equals("host-generation")) put(fixture, "session_preparedCoverageGeneration", 2L);
                else if (!fault.equals("oversized-bytes")) inputs.far.addProperty(fault, 2);
                assertEquals(true, call("pumpDistanceProgress"));
                assertEquals(fault.equals("oversized-bytes") ? "progress-out-of-range:DH_UPLOADED_BYTES" : "dh-progress-job-replaced", state.watchdog.failure());
                assertEquals(0, state.checkpoint().rawVector()[DH_UPLOADED_BYTES.ordinal()]);
                assertEquals(1, inputs.closes);
            } finally { INPUTS.remove(); }
        }
    }

    @Test void actualServerHandlerExpiresCurrentTickBeforeAcceptingQueuedClientCompletion() throws Throwable {
        var inputs = new Inputs(); INPUTS.set(inputs);
        try {
            inputs.serverState = new NativeDistanceProgressState(NativeDistanceChangePayload.request(41, 1, 24, 24, 0), true, 0, false, 0);
            assertTrue(inputs.serverState.header(42, 1, 1, HASH, HASH));
            inputs.serverState.serverTick(2399, new NativeDistanceProgressState.ClockSample(120 * SECOND - 50_000_000L, null));
            inputs.now = 120 * SECOND;
            inputs.serverTick = 2400;
            var counters = new long[NativeDistanceProgressWatchdog.LANE_COUNT]; counters[FACE_SCANS.ordinal()] = 4096;
            var payload = new NativeDistanceProgressPayload(41, 41, 1, 24, 24, 0, 42, 1, 1, HASH, HASH, 1, 0, false, counters);
            invokeServer(payload);
            assertEquals("useful-work-idle", inputs.serverState.watchdog.failure());
            assertEquals(120 * SECOND, inputs.serverState.checkpoint().activeNanos());
            assertEquals(0, inputs.serverState.checkpoint().rawVector()[FACE_SCANS.ordinal()]);
            assertEquals(-1, inputs.serverState.checkpoint().clientObservation());
            assertEquals(1, inputs.closes, "Actual handler must route terminal clock to failure cleanup");
        } finally { INPUTS.remove(); }
    }

    @Test void actualServerHandlerAcceptsExactWorkBeforeDeadlineAndRejectsDifferentActor() throws Throwable {
        var inputs = new Inputs(); INPUTS.set(inputs);
        try {
            inputs.serverState = new NativeDistanceProgressState(NativeDistanceChangePayload.request(41, 1, 24, 24, 0), true, 0, false, 0);
            assertTrue(inputs.serverState.header(42, 1, 1, HASH, HASH)); inputs.serverTick = 20; inputs.now = SECOND;
            var counters = new long[NativeDistanceProgressWatchdog.LANE_COUNT]; counters[FACE_SCANS.ordinal()] = 4096;
            invokeServer(new NativeDistanceProgressPayload(41, 41, 1, 24, 24, 0, 43, 1, 1, HASH, HASH, 1, 0, false, counters));
            assertEquals(0, inputs.serverState.checkpoint().rawVector()[FACE_SCANS.ordinal()]);
            invokeServer(new NativeDistanceProgressPayload(41, 41, 1, 24, 24, 0, 42, 1, 1, HASH, HASH, 1, 0, false, counters));
            assertEquals(4096, inputs.serverState.checkpoint().rawVector()[FACE_SCANS.ordinal()]);
            assertEquals(1, inputs.serverState.checkpoint().lastPeerStamp());
            assertEquals(0, inputs.closes);
        } finally { INPUTS.remove(); }
    }

    /** Actual server packet handler with authenticated owner/getter seams and a recorded cleanup sink. */
    private static synchronized void invokeServer(NativeDistanceProgressPayload payload) throws Throwable {
        if (serverFixtureClass == null) {
            String server = BASE + "RollingGateCommands", session = server + "$Session", generatedName = BASE + "DistanceProgressExecutableServerFixture";
            var root = read(server); var source = root.methods.stream().filter(m -> m.name.equals("nativeDistanceProgress")).findFirst().orElseThrow();
            var copy = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, source.name, source.desc, null, null);
            source.accept(copy); copy.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC; copy.localVariables = null;
            for (var node : copy.instructions.toArray()) {
                if (node instanceof FrameNode) { copy.instructions.remove(node); continue; }
                if (node instanceof FieldInsnNode field) {
                    if (field.owner.equals(server) && (field.name.equals("session") || field.name.equals("pendingDistance")))
                        copy.instructions.set(field, new InsnNode(Opcodes.ACONST_NULL));
                    else if (field.owner.equals(session)) {
                        if (field.name.equals("continuationProgress")) copy.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "serverState", "(Ljava/lang/Object;)L" + BASE + "NativeDistanceProgressState;", false));
                        else if (field.name.equals("stopping")) copy.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "falseValue", "(Ljava/lang/Object;)Z", false));
                        else copy.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "nullValue", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                    } else if (field.owner.equals("net/minecraft/server/level/ServerPlayer"))
                        copy.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "nullValue", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                } else if (node instanceof MethodInsnNode call) {
                    if (call.owner.endsWith("/NativeDistanceProgressState") && call.name.equals("serverClock")) stub(call, "serverClock", "(Ljava/lang/Object;J)L" + BASE + "NativeDistanceProgressState$ClockSample;");
                    else if (call.owner.equals(server) && (call.name.equals("owns") || call.name.equals("distanceOwnerCurrent"))) stub(call, "owns", "(Ljava/lang/Object;Ljava/lang/Object;)Z");
                    else if (call.owner.equals(server) && (call.name.equals("failDistanceProgress") || call.name.equals("distanceFailed"))) stub(call, "serverFailure", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V");
                    else if (call.owner.equals("net/minecraft/server/MinecraftServer") && call.name.equals("getTickCount")) stub(call, "serverTick", "(Ljava/lang/Object;)I");
                    else if (call.owner.endsWith("/RollingPublicationHandshake") && call.name.equals("publication")) stub(call, "publication", "(Ljava/lang/Object;)J");
                    else if (call.owner.endsWith("/RollingPublicationHandshake") && call.name.equals("digest")) stub(call, "hash", "(Ljava/lang/Object;)Ljava/lang/String;");
                }
            }
            var generated = new ClassNode(); generated.version = root.version; generated.access = Opcodes.ACC_PUBLIC;
            generated.name = generatedName; generated.superName = "java/lang/Object"; generated.methods.add(copy);
            var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); generated.accept(writer);
            serverFixtureClass = MethodHandles.lookup().defineClass(writer.toByteArray());
        }
        try { serverFixtureClass.getMethod("nativeDistanceProgress", net.minecraft.server.level.ServerPlayer.class, NativeDistanceProgressPayload.class).invoke(null, null, payload); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static synchronized Class<?> fixtureType() throws Exception {
        if (fixtureClass != null) return fixtureClass;
        var root = read(ROOT); var session = read(SESSION);
        var generated = new ClassNode(); generated.version = root.version; generated.access = Opcodes.ACC_PUBLIC;
        generated.name = GENERATED; generated.superName = "java/lang/Object";
        for (var field : root.fields)
            generated.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field.name, remap(field.desc), null, null));
        for (var field : session.fields) if ((field.access & Opcodes.ACC_STATIC) == 0)
            generated.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "session_" + field.name, remap(field.desc), null, null));
        for (String name : List.of("pumpDistanceProgress", "nativeDistanceProgress", "failDistanceProgress", "closeCurrent", "logout", "distancePause", "finishDistanceProgress"))
            generated.methods.add(instrument(root.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow()));
        var ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        ctor.instructions.add(new InsnNode(Opcodes.RETURN)); generated.methods.add(ctor);
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        generated.accept(writer); fixtureClass = MethodHandles.lookup().defineClass(writer.toByteArray());
        return fixtureClass;
    }

    private static MethodNode instrument(MethodNode source) {
        var copy = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, source.name, remap(source.desc), null, null);
        source.accept(copy); copy.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC; copy.localVariables = null;
        for (var node : copy.instructions.toArray()) {
            if (node instanceof FrameNode) { copy.instructions.remove(node); continue; }
            if (node instanceof FieldInsnNode field) {
                if (field.owner.equals(ROOT)) { field.owner = GENERATED; field.desc = remap(field.desc); }
                else if (field.owner.equals(SESSION)) { field.owner = GENERATED; field.name = "session_" + field.name; field.desc = remap(field.desc); }
                else if (field.owner.equals("net/minecraft/client/Minecraft") && field.name.equals("level"))
                    copy.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "level", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                else if (field.owner.equals("io/earthplatform/minecraft/scene/SceneCommands") && field.name.equals("CARRIER"))
                    copy.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "carrier", "()Lnet/minecraft/resources/ResourceKey;", false));
                else if (field.owner.equals("io/earthplatform/minecraft/EarthPlatformMod") && field.name.equals("LOGGER"))
                    copy.instructions.set(field, new InsnNode(Opcodes.ACONST_NULL));
            } else if (node instanceof MethodInsnNode call) {
                if (call.owner.equals(ROOT)) { call.owner = GENERATED; call.desc = remap(call.desc); }
                else if (call.owner.endsWith("/NativeDistanceProgressState") && (call.name.equals("observeActualPause") || call.name.equals("bindPauseClock"))) stub(call, "observePause", "(Ljava/lang/Object;Ljava/lang/Object;JZ)V");
                else if (call.owner.endsWith("/NativeDistanceProgressState") && call.name.equals("revokePauseClock")) stub(call, "revokePause", "(Ljava/lang/Object;Ljava/lang/Object;J)V");
                else if (call.owner.equals(SESSION) && call.name.equals("distanceMode")) stub(call, "mode", "(Ljava/lang/Object;)Z");
                else if (call.owner.equals(SESSION) && call.name.equals("report")) stub(call, "report", "(Ljava/lang/Object;Ljava/lang/String;)V");
                else if (call.owner.equals(SESSION) && call.name.equals("closeInstalledBridge")) stub(call, "physicalClose", "(Ljava/lang/Object;)V");
                else if (call.owner.equals("java/lang/System") && call.name.equals("nanoTime")) stub(call, "now", "()J");
                else if (call.owner.equals("net/minecraft/client/Minecraft")) {
                    if (call.name.equals("getInstance")) stub(call, "minecraft", "()Lnet/minecraft/client/Minecraft;");
                    else if (call.name.equals("isSameThread")) stub(call, "mode", "(Ljava/lang/Object;)Z");
                    else if (call.name.equals("isPaused")) stub(call, "paused", "(Ljava/lang/Object;)Z");
                    else if (call.name.equals("getConnection")) stub(call, "connection", "(Ljava/lang/Object;)Lnet/minecraft/client/multiplayer/ClientPacketListener;");
                    else if (call.name.equals("getSingleplayerServer")) stub(call, "integratedServer", "(Ljava/lang/Object;)Lnet/minecraft/client/server/IntegratedServer;");
                    else if (call.name.equals("execute")) stub(call, "execute", "(Ljava/lang/Object;Ljava/lang/Runnable;)V");
                } else if (call.owner.endsWith("ClientPauseChangeEvent$Post") && call.name.equals("isPaused")) stub(call, "paused", "(Ljava/lang/Object;)Z");
                else if ((call.owner.equals("net/minecraft/client/multiplayer/ClientLevel") || call.owner.equals("net/minecraft/world/level/Level")) && call.name.equals("dimension")) stub(call, "dimension", "(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceKey;");
                else if (call.owner.endsWith("ClientPauseChangeEvent") && call.name.equals("isPaused")) stub(call, "paused", "(Ljava/lang/Object;)Z");
                else if (call.owner.endsWith("/RenderPublicationGuard") && call.name.equals("resourceGeneration")) stub(call, "resources", "(Ljava/lang/Object;)J");
                else if (call.owner.endsWith("/NativeVisualStream") && call.name.equals("progress")) stub(call, "work", "(Ljava/lang/Object;)Lio/earthplatform/minecraft/scene/NativeVisualStream$Progress;");
                else if (call.owner.endsWith("/RollingMeshWorkQueue") && call.name.equals("metrics")) stub(call, "metrics", "(Ljava/lang/Object;)Lio/earthplatform/minecraft/coregate/RollingMeshWorkQueue$Metrics;");
                else if (call.owner.endsWith("/RollingGateReplica") && call.name.equals("stagedMetadata")) stub(call, "metadata", "(Ljava/lang/Object;)Lio/earthplatform/minecraft/enginebridge/RollingCubeActor$StageMetadata;");
                else if (call.owner.endsWith("/RollingGateReplica") && call.name.equals("currentCenter")) stub(call, "center", "(Ljava/lang/Object;)Lio/earthplatform/core/CubePos;");
                else if (call.owner.endsWith("/DhReplacementInput") && call.name.equals("data")) stub(call, "data", "(Ljava/lang/Object;)Lio/earthplatform/minecraft/lod/DhReplacementInput$Data;");
                else if (call.owner.endsWith("/DhReplacementInput$Data") && call.name.equals("capture")) stub(call, "capture", "(Ljava/lang/Object;)Lio/earthplatform/minecraft/lod/DhVoxelReplacementGeometry$Capture;");
                else if (call.owner.endsWith("/DhVoxelReplacementGeometry$Capture") && call.name.equals("earthCapture")) stub(call, "earthCapture", "(Ljava/lang/Object;)Lio/earthplatform/minecraft/enginebridge/EarthReplacementCapture;");
                else if (call.owner.endsWith("/EarthReplacementCapture") && call.name.equals("contentIdentity")) stub(call, "hash", "(Ljava/lang/Object;)Ljava/lang/String;");
                else if (call.owner.endsWith("/ReplacementCertificate") && call.name.equals("originalRevision")) stub(call, "revision", "(Ljava/lang/Object;)J");
                else if (call.owner.endsWith("/ReplacementCertificate") && call.name.equals("storeIdentity")) stub(call, "store", "(Ljava/lang/Object;)Ljava/lang/String;");
                else if (call.owner.endsWith("/DhPreparedLodView") && call.name.equals("diagnostics")) stub(call, "far", "(Ljava/lang/Object;)Lcom/google/gson/JsonObject;");
                else if (call.owner.equals("net/neoforged/neoforge/network/PacketDistributor") && call.name.equals("sendToServer")) stub(call, "send", call.desc);
                else if (call.owner.equals("org/slf4j/Logger") && (call.name.equals("error") || call.name.equals("info"))) stub(call, "log", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)V");
            } else if (node instanceof InvokeDynamicInsnNode dynamic && dynamic.desc.endsWith("Ljava/lang/Runnable;"))
                copy.instructions.set(dynamic, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "later", "(Ljava/lang/Object;)Ljava/lang/Runnable;", false));
        }
        return copy;
    }
    private static void stub(MethodInsnNode call, String name, String descriptor) { call.setOpcode(Opcodes.INVOKESTATIC); call.owner = SELF; call.name = name; call.desc = descriptor; call.itf = false; }
    private static String remap(String descriptor) { return descriptor.replace("L" + SESSION + ";", "L" + GENERATED + ";"); }
    private static ClassNode read(String name) throws Exception {
        try (var stream = NativeDistanceProgressHostAdversarialTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name); var result = new ClassNode(); new ClassReader(stream).accept(result, 0); return result;
        }
    }
    private static Object uninitialized(Class<?> type) throws Exception {
        var unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
    }
    private static void put(Object receiver, String name, Object value) throws Exception { fixtureClass.getField(name).set(receiver, value); }
    private static Object get(Object receiver, String name) throws Exception { return fixtureClass.getField(name).get(receiver); }
    private static Object call(String name, Object... args) throws Throwable {
        var method = Arrays.stream(fixtureClass.getMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        try { return method.invoke(null, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    public static Minecraft minecraft() { return null; }
    public static net.minecraft.client.server.IntegratedServer integratedServer(Object ignored) { return null; }
    public static Object level(Object ignored) { return INPUTS.get().level; }
    public static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension(Object ignored) { return net.minecraft.world.level.Level.OVERWORLD; }
    public static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> carrier() {
        return net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("earthplatform", "native_view_carrier"));
    }
    public static long now() { return INPUTS.get().now; }
    public static void observePause(Object server, Object connection, long episode, boolean paused) { }
    public static void revokePause(Object server, Object connection, long episode) { }
    public static NativeDistanceProgressState.ClockSample serverClock(Object server, long episode) { return new NativeDistanceProgressState.ClockSample(INPUTS.get().now, null); }
    public static boolean mode(Object ignored) { return true; }
    public static boolean paused(Object ignored) { return INPUTS.get().paused; }
    public static ClientPacketListener connection(Object ignored) { return INPUTS.get().connection; }
    public static long resources(Object ignored) { return INPUTS.get().resources; }
    public static NativeVisualStream.Progress work(Object ignored) { var inputs = INPUTS.get(); inputs.samples++; return inputs.work; }
    public static RollingMeshWorkQueue.Metrics metrics(Object ignored) { return new RollingMeshWorkQueue.Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, null, null, null, null, new RollingMeshWorkQueue.OwnerSliceMetrics(new RollingMeshWorkQueue.Timing(0, 0, 0), 0, 0, 0, 0, 0, 0)); }
    public static RollingCubeActor.StageMetadata metadata(Object ignored) { return null; }
    public static CubePos center(Object ignored) { return CENTER; }
    public static DhReplacementInput.Data data(Object ignored) { return null; }
    public static DhVoxelReplacementGeometry.Capture capture(Object ignored) { return null; }
    public static EarthReplacementCapture earthCapture(Object ignored) { return null; }
    public static String hash(Object ignored) { return HASH; }
    public static long revision(Object ignored) { return 0; }
    public static String store(Object ignored) { return new UUID(1, 2).toString(); }
    public static com.google.gson.JsonObject far(Object ignored) { return INPUTS.get().far; }
    public static NativeDistanceProgressState serverState(Object ignored) { return INPUTS.get().serverState; }
    public static Object nullValue(Object ignored) { return null; }
    public static boolean falseValue(Object ignored) { return false; }
    public static boolean owns(Object player, Object session) { return true; }
    public static int serverTick(Object ignored) { return INPUTS.get().serverTick; }
    public static long publication(Object ignored) { return 1; }
    public static void serverFailure(Object session, Object player, String reason) {
        var inputs = INPUTS.get(); inputs.serverState.watchdog.fail(reason);
        inputs.atPhysicalClose = inputs.serverState.checkpoint(); inputs.closes++;
    }
    public static void send(CustomPacketPayload first, CustomPacketPayload... packets) { INPUTS.get().packets.add(first); INPUTS.get().packets.addAll(List.of(packets)); }
    public static void log(Object ignored, String message, Object argument) { }
    public static Runnable later(Object payload) { throw new AssertionError("Fixture must remain on owner thread"); }
    public static void execute(Object ignored, Runnable task) { throw new AssertionError("Unexpected asynchronous owner dispatch"); }
    public static void report(Object session, String stage) { var inputs = INPUTS.get(); inputs.reports++; inputs.effects.add("report"); }
    public static void physicalClose(Object session) {
        var inputs = INPUTS.get(); inputs.closes++; inputs.effects.add("close");
        try { inputs.atPhysicalClose = (NativeDistanceProgressWatchdog.Checkpoint) get(null, "lastDistanceProgress"); }
        catch (Exception failure) { throw new AssertionError(failure); }
        assertNotNull(inputs.atPhysicalClose, "Cutoff checkpoint must be saved before cleanup can dispose counters");
        if (inputs.physicalCloseFails) throw new IllegalStateException("fixture physical writer/alias still retained");
    }
}
