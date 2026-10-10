package io.earthplatform.minecraft.coregate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent production-wiring regressions. Queue fault tests prove phase behavior; these
 * checks ensure the real client routes work through that queue and preserves ACK retirement.
 * They inspect bytecode without constructing a Minecraft client or pretending to test GL.
 */
class RollingSlicedClientContractTest {
    private static final String BASE = "io/earthplatform/minecraft/";
    private static final String SESSION = BASE + "coregate/RollingGateClient$Session";
    private static final String QUEUE = BASE + "coregate/RollingMeshWorkQueue";
    private static final String REPLICA = BASE + "coregate/RollingGateReplica";
    private static final String COLLISION = BASE + "coregate/CoreCollisionBinding";
    private static final String MESH = BASE + "scene/SceneMesh";
    private static final String FAR = BASE + "lod/DhGeographicLodView";

    @Test void actualRenderPathPumpsExactlyOneNativePhaseAndDoesNotBuildSynchronously() throws IOException {
        var code = read(SESSION);
        assertEquals(1, method(code, "render").count(SESSION, "prepareCandidateWork"));
        var work = method(code, "prepareCandidateWork");
        assertEquals(1, work.count(QUEUE, "pump"));
        assertEquals(1, work.count(QUEUE, "publishCompleted"));
        assertTrue(work.index(QUEUE, "pump") < work.index(QUEUE, "publishCompleted"));
        for (var entry : code.entrySet()) {
            assertEquals(0, entry.getValue().count(MESH, "build"), entry.getKey());
            assertEquals(0, entry.getValue().count(MESH, "begin"), entry.getKey());
            assertEquals(0, entry.getValue().count(MESH + "$Build", "step"), entry.getKey());
            assertEquals(0, entry.getValue().count(MESH + "$Build", "finish"), entry.getKey());
        }
    }

    @Test void preparedAndPairInstallationRequireTheQueueToDrainBeforePublication() throws IOException {
        var code = read(SESSION);
        var work = method(code, "prepareCandidateWork");
        assertEquals(1, work.count(QUEUE, "isDrained"));
        assertTrue(work.index(QUEUE, "isDrained") < work.index(SESSION, "send"));
        assertTrue(work.index(REPLICA, "candidateTargets") < work.index(SESSION, "send"));
        assertTrue(work.index(MESH, "isCurrent") < work.index(SESSION, "send"));
        assertTrue(work.literals.contains("Candidate queue drained before all targets were published"));
        var install = method(code, "installPair");
        assertEquals(1, install.count(QUEUE, "isDrained"));
        assertTrue(install.index(QUEUE, "isDrained") < install.index(FAR, "replaceCoverage"));
        assertTrue(install.index(FAR, "replaceCoverage") < install.index(SESSION, "closeExcept"));
        assertTrue(install.index(SESSION, "closeExcept") < install.index(SESSION, "send"));
    }

    @Test void onlyExactServerCommitRetiresReplicaSnapshotsOrPublishesCollision() throws IOException {
        var code = read(SESSION);
        for (var entry : code.entrySet()) {
            if (entry.getKey().equals("committed")) continue;
            assertEquals(0, entry.getValue().count(REPLICA, "commitStage"), entry.getKey());
            assertEquals(0, entry.getValue().count(COLLISION, "publish"), entry.getKey());
        }
        var committed = method(code, "committed");
        assertEquals(1, committed.count(REPLICA, "commitStage"));
        assertEquals(1, committed.count(COLLISION, "publish"));
        assertTrue(committed.index(SESSION, "checkPair") < committed.index(REPLICA, "commitStage"));
        assertTrue(committed.index(REPLICA, "commitStage") < committed.index(COLLISION, "publish"));
        assertTrue(committed.literals.contains("Commit precedes paired rendering"));
    }

    @Test void cleanupRetainsReplicaUntilQueueAndAdoptedNativeOwnersHaveBeenClosed() throws IOException {
        var close = method(read(SESSION), "close");
        assertEquals(1, close.count(QUEUE, "close"));
        assertTrue(close.index(QUEUE, "close") < close.index(SESSION, "closeExcept"));
        assertTrue(close.index(SESSION, "closeExcept") < close.index(REPLICA, "close"));
        assertTrue(close.index(FAR, "close") < close.index(REPLICA, "close"));
        assertTrue(close.throwCount >= 2, "RuntimeException and Error cleanup failures must escape before retirement");
    }

    @Test void delayedScreenshotCannotRewriteAnotherPublicationsJsonReport() throws IOException {
        var code = read(SESSION);
        var screenshot = method(code, "afterHud");
        assertEquals(0, screenshot.count(SESSION, "report"));
        assertEquals(0, screenshot.count(SESSION, "write"));
        assertTrue(screenshot.reads.contains("pending"));
        assertTrue(screenshot.reads.contains("publication"));
        assertTrue(screenshot.reads.contains("drawn"));
        assertTrue(screenshot.writes.contains("capture"));
        assertTrue(method(code, "metadata").writes.contains("capture"));
        assertTrue(method(code, "beginEdit").writes.contains("capture"));
        assertEquals(1, screenshot.count("net/minecraft/client/Screenshot", "takeScreenshot"));
    }

    private static MethodCode method(Map<String, MethodCode> methods, String name) {
        var result = methods.get(name);
        assertNotNull(result, "Missing production method " + name);
        return result;
    }

    private static Map<String, MethodCode> read(String className) throws IOException {
        var methods = new LinkedHashMap<String, MethodCode>();
        try (var stream = RollingSlicedClientContractTest.class.getClassLoader()
                .getResourceAsStream(className + ".class")) {
            assertNotNull(stream, className);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    var code = methods.computeIfAbsent(name, ignored -> new MethodCode());
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                              String desc, boolean iface) {
                            code.calls.add(new Call(owner, method));
                        }
                        @Override public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                            if (owner.equals(SESSION)) {
                                if (opcode == Opcodes.GETFIELD) code.reads.add(field);
                                if (opcode == Opcodes.PUTFIELD) code.writes.add(field);
                            }
                        }
                        @Override public void visitLdcInsn(Object value) { code.literals.add(value); }
                        @Override public void visitInsn(int opcode) {
                            if (opcode == Opcodes.ATHROW) code.throwCount++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return methods;
    }

    private record Call(String owner, String name) {}
    private static final class MethodCode {
        final List<Call> calls = new ArrayList<>();
        final List<String> reads = new ArrayList<>(), writes = new ArrayList<>();
        final List<Object> literals = new ArrayList<>();
        int throwCount;
        long count(String owner, String name) { return calls.stream().filter(new Call(owner, name)::equals).count(); }
        int index(String owner, String name) {
            int index = calls.indexOf(new Call(owner, name));
            assertTrue(index >= 0, "Missing production call " + owner + "." + name);
            return index;
        }
    }
}
