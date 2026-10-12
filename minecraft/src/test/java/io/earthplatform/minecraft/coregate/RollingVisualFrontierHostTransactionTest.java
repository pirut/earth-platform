package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.compat.dh.shader.DhShaderPassGate;
import io.earthplatform.compat.dh.shader.InstalledShaderCarrier;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.lod.DhPreparedLodView;
import io.earthplatform.minecraft.visual.NativeVisualPublication;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes production installPair/candidateCameraReady/queueVisualRecovery and tick recovery
 * bytecode with deterministic renderer/replica getters. Mesh validation is an independent
 * prerequisite stub; the actual camera guard is retained. The first publication mutation is
 * intercepted, so this is a host transaction barrier test, not a successful GL-install claim.
 */
class RollingVisualFrontierHostTransactionTest {
    private static final String BASE = "io/earthplatform/minecraft/coregate/";
    private static final String ROOT = BASE + "RollingGateClient";
    private static final String SESSION = ROOT + "$Session";
    private static final String SELF = BASE + "RollingVisualFrontierHostTransactionTest";
    private static final String GENERATED = BASE + "VisualFrontierExecutableHostFixture";
    private static final CubePos CURRENT = new CubePos(604879, 460, -194643);
    private static final CubePos CANDIDATE = CURRENT.offset(0, -1, 0);
    private static final WorldIdentity WORLD = new WorldIdentity(1, "host-fixture", "v1", "source", "palette");
    private static final ThreadLocal<Inputs> INPUTS = new ThreadLocal<>();
    private static Class<?> fixtureClass;

    private static final class Inputs {
        Vec3 camera;
        CubePos current = CURRENT, candidate = CANDIDATE;
        long generation = 2;
        int firstMutation, lifecycle, rebuilds, closes, drawGuard;
        NativeDistanceClientTransition client;
        NativeDistanceRestartGate server;
        NativeDistanceChangePayload request;
    }
    private static final class FirstMutation extends RuntimeException { }

    @Test void invalidActualCameraQueuesOnlyAndCannotReachAnyPublicationSideEffect() throws Throwable {
        var inputs = new Inputs(); inputs.camera = new Vec3(9678071.300000012, 7376.917273017791, -3114282.8080077083);
        INPUTS.set(inputs);
        try {
            var fixture = fixture(); var type = fixture.getClass();
            Object published = type.getField("publishedVisual").get(fixture);
            invoke(fixture, "installPair");
            assertEquals(0, inputs.firstMutation, "No far commit, visual swap or PAIR_READY is reachable");
            assertSame(published, type.getField("publishedVisual").get(fixture));
            assertFalse(type.getField("pairSent").getBoolean(fixture));
            assertTrue(type.getField("visible").getBoolean(fixture));
            assertTrue(type.getField("visualRecoveryQueued").getBoolean(fixture));
            assertEquals(0, inputs.lifecycle, "The render callback must only queue recovery");
            assertEquals(0, inputs.rebuilds);
            invoke(fixture, "installPair");
            assertEquals(0, inputs.firstMutation, "Repeated callback cannot install a rejected candidate");

            type.getField("current").set(null, fixture);
            invokeStatic(type, "tick");
            assertEquals(1, inputs.rebuilds);
            assertEquals(1, inputs.lifecycle);
            assertEquals(12, inputs.request.selected());
            assertEquals(12, inputs.request.effective());
            assertFalse(inputs.server.ready());
            inputs.server.serverDrained(true);
            assertFalse(inputs.server.ready(), "Logical replan does not replace a physical client drain");
            assertTrue(inputs.client.drain(inputs.request.drain()));
            assertTrue(inputs.server.clientDrained(inputs.client.physicallyDrained()));
            assertTrue(inputs.server.ready());

            // Model the completed same-pose continuation, retaining the real host recovery budget.
            type.getField("distanceTransition").set(null, null);
            invokeStatic(type, "tick");
            assertEquals(1, inputs.rebuilds, "A second rejected handover cannot loop forever");
            assertEquals(1, inputs.closes, "Repeated failure has an explicit terminal cleanup path");
        } finally { INPUTS.remove(); }
    }

    @Test void validOverlapReachesTheActualInstallMutationAsPositiveControl() throws Throwable {
        var inputs = new Inputs(); inputs.camera = new Vec3(9678071.300000012, 7360.818516378075, -3114282.8080077083);
        INPUTS.set(inputs);
        try {
            var fixture = fixture();
            assertThrows(FirstMutation.class, () -> invoke(fixture, "installPair"));
            assertEquals(1, inputs.firstMutation);
            assertFalse(fixture.getClass().getField("visualRecoveryQueued").getBoolean(fixture));
            assertEquals(0, inputs.rebuilds);
        } finally { INPUTS.remove(); }
    }

    @Test void staleGenerationCannotReachMutationEvenWhenActualCameraFits() throws Throwable {
        var inputs = new Inputs(); inputs.camera = new Vec3(9678071.300000012, 7360.818516378075, -3114282.8080077083);
        inputs.generation = 3; INPUTS.set(inputs);
        try {
            var fixture = fixture();
            var rejected = assertThrows(IllegalStateException.class, () -> invoke(fixture, "installPair"));
            assertTrue(rejected.getMessage().contains("handover identity"));
            assertEquals(0, inputs.firstMutation);
            assertFalse(fixture.getClass().getField("pairSent").getBoolean(fixture));
        } finally { INPUTS.remove(); }
    }

    @Test void bootstrapBeforeFirstReadyDoesNotSpendMovementRecoveryDeadline() {
        var frontier = new RollingVisualFrontier(1);
        assertTrue(frontier.metadata(1, 1, 1, CURRENT, CURRENT, 1, 1));
        assertFalse(frontier.expired(20L * 60 * 1_000_000_000),
                "Initial native-model qualification can exceed the movement handover deadline");
    }

    @Test void extremeRearCameraPacketPhaseGetsOneFiniteRecoveryWithoutWideningPermit() throws Throwable {
        var inputs = new Inputs(); inputs.current = new CubePos(0, 8, 0); inputs.candidate = inputs.current.offset(1, 0, 0);
        var eye = new Vec3(13.25, 136, 8); inputs.camera = eye.add(-4, 0, 0);
        var body = new AABB(eye.x - .3, eye.y - 1.62, eye.z - .3, eye.x + .3, eye.y + .18, eye.z + .3);
        assertFalse(RollingVisualFrontier.motionInside(inputs.current, body, new Vec3(4, 0, 0)),
                "A supported 4m packet can request the next permit before the rear camera fits it");
        assertTrue(RollingVisualFrontier.cameraInside(inputs.current, inputs.camera));
        assertTrue(RollingVisualFrontier.cameraInside(inputs.candidate, eye));
        assertFalse(RollingVisualFrontier.cameraInside(inputs.candidate, inputs.camera));
        INPUTS.set(inputs);
        try {
            var fixture = fixture(); var type = fixture.getClass();
            invoke(fixture, "installPair");
            assertEquals(0, inputs.firstMutation);
            assertTrue(type.getField("visualRecoveryQueued").getBoolean(fixture));
            assertFalse(type.getField("pairSent").getBoolean(fixture));
            assertEquals(0, inputs.lifecycle);
            type.getField("current").set(null, fixture);
            invokeStatic(type, "tick");
            assertEquals(1, inputs.rebuilds);
            type.getField("distanceTransition").set(null, null);
            invokeStatic(type, "tick");
            assertEquals(1, inputs.rebuilds);
            assertEquals(1, inputs.closes);
            assertEquals(10, NativeVisualPublication.cameraPermit(inputs.candidate).minX(),
                    "Exceptional recovery must not widen the requested visual permit");
        } finally { INPUTS.remove(); }
    }

    @Test void outsideBothPermitsSuppressesAlreadyPinnedDispatchAndTheNextWholeFrame() throws Throwable {
        var inputs = new Inputs(); inputs.camera = new Vec3(9678071.300000012, 8000, -3114282.8080077083);
        assertFalse(RollingVisualFrontier.cameraInside(inputs.current, inputs.camera));
        assertFalse(RollingVisualFrontier.cameraInside(inputs.candidate, inputs.camera));
        INPUTS.set(inputs);
        try {
            var fixture = fixture(); var type = fixture.getClass();
            invoke(fixture, "installPair");
            assertTrue(type.getField("visualRecoveryQueued").getBoolean(fixture));
            assertEquals(0, inputs.firstMutation);
            String retainedPair = "a".repeat(64);
            type.getField("actualRenderFrame").setLong(fixture, 1);
            type.getField("shaderPreparedFrame").setLong(fixture, 1);
            type.getField("shaderFrameDigest").set(fixture, retainedPair);
            var pinned = (InstalledShaderCarrier.Frame) call(fixture, "shaderFrame");
            assertEquals(retainedPair, pinned.pairDigest(), "Do not change an already pinned frame's identity");
            var gate = new DhShaderPassGate(); var level = new Object(); var pipeline = new Object();
            var resources = new DhShaderPassGate.Resources() {
                public boolean presentationCurrent(String pair, boolean historical) { return true; }
                public void draw(DhShaderPassGate.Pass pass, Object context) {
                    try {
                        if (pass.name().contains("_DH_")) call(fixture, "shaderFar", null, inputs.camera.x, inputs.camera.y, inputs.camera.z);
                        else call(fixture, "shaderNative", pass, null);
                    } catch (Throwable failure) { throw new AssertionError("Queued callback must suppress drawing without recursive cleanup", failure); }
                }
                public void close() { }
            };
            var lease = gate.acquire(level, pipeline, "host-fixture", 1, 1, 4095, resources);
            assertTrue(gate.beginFrame(lease, 1, pipeline, 1, retainedPair, false));
            assertTrue(gate.beginFrame(lease, pinned.serial(), pipeline, pinned.resourceGeneration(), pinned.pairDigest(), pinned.historical()));
            for (var pass : DhShaderPassGate.Pass.values())
                assertEquals(DhShaderPassGate.Result.SUBMITTED, gate.submit(lease, level, 1, pipeline, 1, pass, null));
            assertEquals(0, inputs.drawGuard, "Queued native/far dispatch must return before strict camera checks and geometry");
            assertEquals(0, inputs.lifecycle, "Same-frame suppression must not enter recursive lifecycle cleanup");
            assertFalse(gate.counters().fenced());

            type.getField("actualRenderFrame").setLong(fixture, 2);
            var next = (InstalledShaderCarrier.Frame) call(fixture, "shaderFrame");
            assertNull(next.pairDigest()); assertFalse(next.historical());
            assertTrue(gate.beginFrame(lease, next.serial(), pipeline, next.resourceGeneration(), next.pairDigest(), next.historical()));
            for (var pass : DhShaderPassGate.Pass.values())
                assertEquals(DhShaderPassGate.Result.NO_PRESENTATION, gate.submit(lease, level, 2, pipeline, 1, pass, null));
            assertEquals(0, inputs.drawGuard); assertEquals(0, inputs.firstMutation); assertEquals(0, inputs.lifecycle);
            assertFalse(gate.counters().fenced()); gate.close();
        } finally { INPUTS.remove(); }
    }

    private static Object fixture() throws Throwable {
        var type = fixtureType();
        type.getField("current").set(null, null);
        type.getField("distanceTransition").set(null, null);
        type.getField("visualRecoveryCount").setInt(null, 0);
        type.getField("visualRecoveryReason").set(null, "");
        type.getField("actualRenderFrame").setLong(null, 0);
        var fixture = type.getConstructor().newInstance();
        type.getField("fence").set(fixture, new Object());
        for (String field : List.of("allowed", "preparedSent", "visible")) type.getField(field).setBoolean(fixture, true);
        type.getField("epoch").setLong(fixture, 1);
        type.getField("publication").setLong(fixture, 9);
        var inputs = INPUTS.get();
        type.getField("testTarget").set(fixture, inputs.candidate);
        var frontier = new RollingVisualFrontier(1);
        long now = System.nanoTime();
        assertTrue(frontier.metadata(1, 8, 1, inputs.current, inputs.current, 1, now)); frontier.committed(8, 1, 1, inputs.current);
        assertTrue(frontier.request(1, 8, 1, 1, inputs.candidate, now));
        assertTrue(frontier.metadata(1, 9, 1, inputs.current, inputs.candidate, 2, now));
        type.getField("visualFrontier").set(fixture, frontier);
        var center = new RollingFarPublication.Center(0, 0);
        type.getField("preparedFarCenter").set(fixture, center);
        type.getField("desiredFarCenter").set(fixture, center);
        type.getField("preparedFar").set(fixture, uninitialized(DhPreparedLodView.CoverageTicket.class));
        type.getField("publishedVisual").set(fixture, uninitialized(NativeVisualPublication.class));
        return fixture;
    }

    private static synchronized Class<?> fixtureType() throws Throwable {
        if (fixtureClass != null) return fixtureClass;
        var session = read(SESSION); var root = read(ROOT);
        var generated = new ClassNode(); generated.version = session.version;
        generated.access = Opcodes.ACC_PUBLIC; generated.name = GENERATED; generated.superName = "java/lang/Object";
        for (var field : session.fields) if ((field.access & Opcodes.ACC_STATIC) == 0)
            generated.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, field.name, remap(field.desc), null, null));
        for (String name : List.of("current", "distanceTransition", "visualRecoveryCount", "visualRecoveryReason", "actualRenderFrame")) {
            var field = root.fields.stream().filter(value -> value.name.equals(name)).findFirst().orElseThrow();
            generated.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field.name, remap(field.desc), null, null));
        }
        generated.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "testTarget", "Lio/earthplatform/core/CubePos;", null, null));
        for (String name : List.of("installPair", "candidateCameraReady", "queueVisualRecovery", "shaderFrame", "shaderNative", "shaderFar"))
            generated.methods.add(instrument(method(session, name)));
        for (String name : List.of("pumpVisualRecovery", "tick")) generated.methods.add(instrument(method(root, name)));
        addStubs(generated);
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        generated.accept(writer);
        fixtureClass = MethodHandles.lookup().defineClass(writer.toByteArray());
        return fixtureClass;
    }

    private static MethodNode instrument(MethodNode original) {
        var copy = new MethodNode(Opcodes.ACC_PUBLIC | (original.access & Opcodes.ACC_STATIC), original.name, remap(original.desc), null, null);
        original.accept(copy);
        copy.access = Opcodes.ACC_PUBLIC | (original.access & Opcodes.ACC_STATIC);
        copy.localVariables = null;
        boolean mutationInserted = false;
        for (var node : copy.instructions.toArray()) {
            if (node instanceof FrameNode) { copy.instructions.remove(node); continue; }
            if (node instanceof FieldInsnNode field) {
                if (field.owner.equals(SESSION) || field.owner.equals(ROOT)) { field.owner = GENERATED; field.desc = remap(field.desc); }
                if (original.name.equals("installPair") && !mutationInserted && field.owner.equals(GENERATED)
                        && field.name.equals("visible") && field.getOpcode() == Opcodes.PUTFIELD) {
                    copy.instructions.insertBefore(field, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "firstMutation", "()V", false));
                    mutationInserted = true;
                }
                if (field.owner.equals("net/minecraft/client/Minecraft") && field.name.equals("gameRenderer"))
                    copy.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "nullReceiver", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                else if (field.owner.equals("io/earthplatform/minecraft/EarthPlatformMod") && field.name.equals("LOGGER"))
                    copy.instructions.set(field, new InsnNode(Opcodes.ACONST_NULL));
            } else if (node instanceof MethodInsnNode call) {
                if (call.owner.equals(SESSION) || call.owner.equals(ROOT)) { call.owner = GENERATED; call.desc = remap(call.desc); }
                else if (call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("getInstance")) stub(call, "nullMinecraft", "()Lnet/minecraft/client/Minecraft;");
                else if (call.owner.equals("net/minecraft/client/renderer/GameRenderer") && call.name.equals("getMainCamera")) stub(call, "nullReceiver", "(Ljava/lang/Object;)Ljava/lang/Object;");
                else if (call.owner.equals("net/minecraft/client/Camera") && call.name.equals("getPosition")) stub(call, "actualCamera", "(Ljava/lang/Object;)Lnet/minecraft/world/phys/Vec3;");
                else if (call.owner.equals(BASE + "RollingGateReplica") && call.name.equals("stagedMetadata")) stub(call, "metadata", "(Ljava/lang/Object;)Lio/earthplatform/minecraft/enginebridge/RollingCubeActor$StageMetadata;");
                else if (call.owner.equals(BASE + "RollingGateReplica") && call.name.equals("candidateReady")) stub(call, "ready", "(Ljava/lang/Object;)Lio/earthplatform/minecraft/enginebridge/RollingCubeActor$StageReady;");
                else if (call.owner.equals("io/earthplatform/minecraft/renderprobe/RenderPublicationGuard") && call.name.equals("resourceGeneration")) stub(call, "resources", "(Ljava/lang/Object;)J");
                else if (call.owner.equals("io/earthplatform/minecraft/renderprobe/RenderPublicationGuard") && call.name.equals("invalidationRequested")) stub(call, "falseReceiver", "(Ljava/lang/Object;)Z");
                else if (call.owner.equals("io/earthplatform/minecraft/lod/DhPreparedLodView") && call.name.equals("isPrepared")) stub(call, "isPrepared", "(Ljava/lang/Object;Ljava/lang/Object;)Z");
                else if (call.owner.equals("org/slf4j/Logger") && call.name.equals("warn")) stub(call, "log", "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V");
                else if (call.owner.equals("io/earthplatform/compat/dh/InstalledIrisRuntime") && call.name.equals("invalidateLifecycle")) stub(call, "lifecycle", "(Ljava/lang/String;)V");
                else if (call.owner.endsWith("/StrictGlErrors") && call.name.equals("check")) stub(call, "noGlCheck", "(Ljava/lang/String;)V");
                else if (call.owner.equals("io/earthplatform/minecraft/scene/SceneNativeShaderContract") && call.name.equals("captureIris")) stub(call, "capture", "(Ljava/lang/Object;)Lio/earthplatform/minecraft/scene/SceneNativeShaderContract$Capture;");
                else if (call.owner.equals("io/earthplatform/minecraft/visual/NativeVisualPublication") && call.name.equals("requireCamera")) stub(call, "drawGuard", "(Ljava/lang/Object;DDD)V");
            } else if (node instanceof InvokeDynamicInsnNode dynamic) {
                dynamic.desc = remap(dynamic.desc);
                for (int i = 0; i < dynamic.bsmArgs.length; i++) {
                    if (dynamic.bsmArgs[i] instanceof Handle handle && (handle.getOwner().equals(SESSION) || handle.getOwner().equals(ROOT)))
                        dynamic.bsmArgs[i] = new Handle(handle.getTag(), GENERATED, handle.getName(), remap(handle.getDesc()), handle.isInterface());
                    else if (dynamic.bsmArgs[i] instanceof Type type)
                        dynamic.bsmArgs[i] = Type.getType(remap(type.getDescriptor()));
                }
            }
        }
        if (original.name.equals("installPair")) assertTrue(mutationInserted, "Instrument the actual first publication mutation");
        return copy;
    }

    private static void addStubs(ClassNode type) {
        // This frontier-only fixture has no active distance episode. The changed watchdog path
        // is executed independently by NativeDistanceProgressHostAdversarialTest.
        var noDistanceEpisode = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pumpDistanceProgress", "()Z", null, null);
        noDistanceEpisode.instructions.add(new InsnNode(Opcodes.ICONST_0));
        noDistanceEpisode.instructions.add(new InsnNode(Opcodes.IRETURN));type.methods.add(noDistanceEpisode);
        var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN)); type.methods.add(constructor);
        var mode = new MethodNode(Opcodes.ACC_PUBLIC, "distanceMode", "()Z", null, null);
        mode.instructions.add(new InsnNode(Opcodes.ICONST_1)); mode.instructions.add(new InsnNode(Opcodes.IRETURN)); type.methods.add(mode);
        var valid = new MethodNode(Opcodes.ACC_PUBLIC, "valid", "()Z", null, null);
        valid.instructions.add(new InsnNode(Opcodes.ICONST_1)); valid.instructions.add(new InsnNode(Opcodes.IRETURN)); type.methods.add(valid);
        var update = new MethodNode(Opcodes.ACC_PUBLIC, "updatePreparedPair", "()V", null, null);
        update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        update.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, GENERATED, "installPair", "()V", false));
        update.instructions.add(new InsnNode(Opcodes.RETURN)); type.methods.add(update);
        var validation = new MethodNode(Opcodes.ACC_PUBLIC, "validateCandidate", "()Z", null, null);
        validation.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); validation.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        validation.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, GENERATED, "testTarget", "Lio/earthplatform/core/CubePos;"));
        validation.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, GENERATED, "candidateCameraReady", "(Lio/earthplatform/core/CubePos;)Z", false));
        validation.instructions.add(new InsnNode(Opcodes.IRETURN)); type.methods.add(validation);
        var invalidation = new MethodNode(Opcodes.ACC_PUBLIC, "invalidateOwned", "()V", null, null);
        invalidation.instructions.add(new InsnNode(Opcodes.RETURN));type.methods.add(invalidation);
        var report = new MethodNode(Opcodes.ACC_PUBLIC, "report", "(Ljava/lang/String;)V", null, null);
        report.instructions.add(new InsnNode(Opcodes.RETURN)); type.methods.add(report);
        var restart = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "restartNativeDistance", "(Ljava/lang/String;)V", null, null);
        restart.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        restart.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "restart", "(Ljava/lang/String;)L" + BASE + "NativeDistanceClientTransition;", false));
        restart.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, GENERATED, "distanceTransition", "L" + BASE + "NativeDistanceClientTransition;"));
        restart.instructions.add(new InsnNode(Opcodes.RETURN)); type.methods.add(restart);
        var close = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "closeCurrent", "(Ljava/lang/String;)Z", null, null);
        close.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); close.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "close", "(Ljava/lang/String;)Z", false));
        close.instructions.add(new InsnNode(Opcodes.IRETURN)); type.methods.add(close);
    }

    private static void stub(MethodInsnNode call, String name, String descriptor) { call.setOpcode(Opcodes.INVOKESTATIC); call.owner = SELF; call.name = name; call.desc = descriptor; call.itf = false; }
    private static String remap(String descriptor) { return descriptor.replace("L" + SESSION + ";", "L" + GENERATED + ";"); }
    private static MethodNode method(ClassNode type, String name) { return type.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow(); }
    private static ClassNode read(String name) throws Exception {
        try (var stream = RollingVisualFrontierHostTransactionTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name); var type = new ClassNode(); new ClassReader(stream).accept(type, 0); return type;
        }
    }
    private static Object uninitialized(Class<?> type) throws Exception {
        var unsafeClass = Class.forName("sun.misc.Unsafe"); var field = unsafeClass.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
    }
    private static void invoke(Object fixture, String method) throws Throwable {
        try { fixture.getClass().getMethod(method).invoke(fixture); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static Object call(Object fixture, String name, Object... arguments) throws Throwable {
        var method = java.util.Arrays.stream(fixture.getClass().getMethods()).filter(value -> value.getName().equals(name)).findFirst().orElseThrow();
        try { return method.invoke(fixture, arguments); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static void invokeStatic(Class<?> type, String name) throws Throwable {
        var method = java.util.Arrays.stream(type.getMethods()).filter(value -> value.getName().equals(name)).findFirst().orElseThrow();
        try { method.invoke(null, new Object[]{null}); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    static net.minecraft.client.Minecraft nullMinecraft() { return null; }
    static Object nullReceiver(Object ignored) { return null; }
    static Vec3 actualCamera(Object ignored) { return INPUTS.get().camera; }
    static long resources(Object ignored) { return 1; }
    static boolean falseReceiver(Object ignored) { return false; }
    static boolean isPrepared(Object ignored, Object ticket) { return true; }
    static void firstMutation() { INPUTS.get().firstMutation++; throw new FirstMutation(); }
    static void log(Object ignored, String format, Object[] arguments) { }
    static void lifecycle(String reason) { INPUTS.get().lifecycle++; }
    static void noGlCheck(String label) { }
    static io.earthplatform.minecraft.scene.SceneNativeShaderContract.Capture capture(Object ignored) { return null; }
    static void drawGuard(Object visual, double x, double y, double z) { INPUTS.get().drawGuard++; throw new AssertionError("Queued recovery reached geometry dispatch"); }
    static boolean close(String reason) { INPUTS.get().closes++; return true; }
    static NativeDistanceClientTransition restart(String reason) {
        var inputs = INPUTS.get(); inputs.rebuilds++; inputs.client = new NativeDistanceClientTransition(1);
        inputs.request = inputs.client.mutation(new NativeDistanceClientTransition.Selection(12, 12, 0));
        inputs.server = new NativeDistanceRestartGate(inputs.request); return inputs.client;
    }
    static RollingCubeActor.StageMetadata metadata(Object ignored) {
        var inputs = INPUTS.get();
        return new RollingCubeActor.StageMetadata(WORLD, 1, inputs.generation, false, inputs.current, inputs.current, inputs.candidate,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }
    static RollingCubeActor.StageReady ready(Object ignored) {
        return new RollingCubeActor.StageReady(WORLD, 1, INPUTS.get().generation, INPUTS.get().candidate, List.of());
    }
}
