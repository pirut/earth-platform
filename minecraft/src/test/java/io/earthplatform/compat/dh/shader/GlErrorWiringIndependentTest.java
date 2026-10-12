package io.earthplatform.compat.dh.shader;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiled production wiring, including failure handlers; does not initialize Minecraft or GL. */
class GlErrorWiringIndependentTest {
    private static final String STRICT = "io/earthplatform/compat/dh/shader/StrictGlErrors";
    private static final String UPLOAD = "io/earthplatform/minecraft/lod/DhGlUpload";
    private static final String DRIVER = "io/earthplatform/minecraft/lod/DhGeographicLodView$OpenGlDriver";
    private static final String MESH = DRIVER + "$GlMesh";
    private static final String BACKEND = "io/earthplatform/compat/dh/shader/IrisShaderFarBackend";
    private static final String RESTORE = "io/earthplatform/compat/dh/shader/GlBindingRestore";
    private static final String BUILD = "io/earthplatform/minecraft/scene/SceneMesh$Build";
    private static final String NATIVE_RESTORE = "io/earthplatform/minecraft/scene/SceneGlRestoreOwner";
    private static final String SESSION = "io/earthplatform/minecraft/coregate/RollingGateClient$Session";
    private static ClassNode read(String name) throws IOException {
        try (var input = GlErrorWiringIndependentTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name); var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
    private static MethodNode method(ClassNode type, String name) {
        return type.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static List<MethodInsnNode> calls(MethodNode method) {
        var result = new ArrayList<MethodInsnNode>();
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) result.add(call);
        return result;
    }
    private static int call(MethodNode method, String owner, String name) {
        return method.instructions.indexOf(calls(method).stream().filter(c -> c.owner.equals(owner) && c.name.equals(name)).findFirst().orElseThrow());
    }
    private static int constant(MethodNode method, String label) {
        for (int i = 0; i < method.instructions.size(); i++) if (method.instructions.get(i) instanceof LdcInsnNode ldc && label.equals(ldc.cst)) return i;
        throw new AssertionError(method.name + " is missing " + label);
    }
    private static int checkpoint(MethodNode method, String phase) {
        int start = constant(method, phase);
        for (int i = start + 1; i < method.instructions.size(); i++) if (method.instructions.get(i) instanceof MethodInsnNode call
                && call.name.equals("check") && (call.owner.equals(STRICT) || call.owner.equals(UPLOAD + "$Gl"))) return i;
        throw new AssertionError("Missing actual check for " + phase);
    }
    private static int creation(MethodNode method, String owner) {
        for (int i = 0; i < method.instructions.size(); i++) if (method.instructions.get(i) instanceof TypeInsnNode type
                && type.getOpcode() == Opcodes.NEW && type.desc.equals(owner)) return i;
        throw new AssertionError("Missing allocation " + owner);
    }
    private static List<Integer> writes(MethodNode method, String owner, String field) {
        var indexes = new ArrayList<Integer>();
        for (int i = 0; i < method.instructions.size(); i++) if (method.instructions.get(i) instanceof FieldInsnNode value
                && value.getOpcode() == Opcodes.PUTFIELD && value.owner.equals(owner) && value.name.equals(field)) indexes.add(i);
        return indexes;
    }
    private static void exceptionCannotReachWrite(MethodNode method, int throwing, int write) {
        var handlers = method.tryCatchBlocks.stream().filter(t -> method.instructions.indexOf(t.start) <= throwing
                && throwing < method.instructions.indexOf(t.end) && ("java/lang/RuntimeException".equals(t.type) || "java/lang/Error".equals(t.type))).toList();
        assertFalse(handlers.isEmpty(), "Checkpoint must remain under its retained-owner catch");
        for (var handler : handlers) assertTrue(method.instructions.indexOf(handler.handler) > write,
                "Checkpoint failure may not reach a completion/owner-clear write");
    }

    @Test void realFarUploadChecksEntryBeforeNativeAllocationAndUsesTheTestedHelperExactlyOnce() throws Exception {
        var driver = read(DRIVER); var upload = method(driver, "uploadBuffer");
        int entry = checkpoint(upload, "far.upload.buffer.entry");
        assertTrue(entry < call(upload, "org/lwjgl/system/MemoryUtil", "memAlloc"));
        assertTrue(entry < call(upload, "org/lwjgl/system/MemoryUtil", "memAllocInt"));
        assertEquals(1, calls(upload).stream().filter(c -> c.owner.equals(UPLOAD) && c.name.equals("upload")).count());
        assertFalse(calls(upload).stream().anyMatch(c -> Set.of("glGenVertexArrays", "glGenBuffers", "glBufferData", "glVertexAttribIPointer", "glVertexAttribPointer").contains(c.name)));
        assertTrue(read(MESH).interfaces.contains(UPLOAD + "$Owner"));
        var prepare = method(driver, "uploadPrepared");
        int retain = call(prepare, "java/util/List", "add"), run = call(prepare, DRIVER, "uploadBuffer");
        assertTrue(retain < run); assertTrue(run < call(prepare, "java/util/List", "remove"));
        int close = call(prepare, MESH, "close"), deleted = call(prepare, MESH, "deleted");
        assertTrue(close < deleted); assertTrue(run < close);
        assertTrue(prepare.tryCatchBlocks.stream().anyMatch(t -> "java/lang/RuntimeException".equals(t.type)
                && prepare.instructions.indexOf(t.start) <= run && run < prepare.instructions.indexOf(t.end)
                && prepare.instructions.indexOf(t.handler) < close));
        assertTrue(calls(prepare).stream().anyMatch(c -> c.name.equals("addSuppressed")), "Retirement failure must not replace upload primary");
    }

    @Test void realAdapterPassesAllThreeCurrentOwnerIdsIntoTheStrictNumericBoundary() throws Exception {
        ClassNode adapter = null;
        for (var instruction : method(read(DRIVER), "<clinit>").instructions) if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
            var candidate = read(type.desc); if (candidate.interfaces.contains(UPLOAD + "$Gl")) adapter = candidate;
        }
        assertNotNull(adapter); var check = method(adapter, "check");
        int strict = call(check, STRICT, "check");
        for (String name : List.of("vao", "vbo", "ibo")) assertTrue(call(check, UPLOAD + "$Owner", name) < strict);
        for (String name : List.of("genVertexArray", "genBuffer", "bindVertexArray", "bindBuffer", "vertexData", "indexData", "enableAttribute", "integerAttribute", "attribute"))
            assertTrue(calls(method(adapter, name)).stream().anyMatch(c -> c.owner.startsWith("org/lwjgl/opengl/")), name);
    }

    @Test void allFarResourceNamesRemainOwnedUntilDeletionAndReadbackBothCrossStrictChecks() throws Exception {
        var close = method(read(MESH), "close"); int prior = -1;
        for (String resource : List.of("vao", "vbo", "ibo")) {
            int entry = checkpoint(close, "far.retire." + resource + ".entry");
            int deleted = checkpoint(close, "far.retire." + resource + ".delete");
            int verified = checkpoint(close, "far.retire." + resource + ".verify");
            var cleared = writes(close, MESH, resource); assertEquals(1, cleared.size()); int clear = cleared.getFirst();
            assertTrue(prior < entry && entry < deleted && deleted < verified && verified < clear, resource);
            exceptionCannotReachWrite(close, entry, clear); exceptionCannotReachWrite(close, deleted, clear); exceptionCannotReachWrite(close, verified, clear);
            assertTrue(calls(close).stream().anyMatch(c -> c.name.equals(resource.equals("vao") ? "glDeleteVertexArrays" : "glDeleteBuffers")));
            prior = clear;
        }
        assertTrue(calls(close).stream().filter(c -> c.name.equals("append")).count() >= 3, "All three resource categories retain separate cleanup failures");
    }

    @Test void farBindingOwnerIsReleasedOnlyAfterTheActualTestedRestorerReturnsSuccessfully() throws Exception {
        var backend = read(BACKEND); var capture = method(backend, "captureBindings");
        assertTrue(call(capture, RESTORE, "capture") < writes(capture, BACKEND, "pendingBindings").getFirst());
        var retry = method(backend, "restorePendingState"); int restore = call(retry, RESTORE, "restore");
        var clears = writes(retry, BACKEND, "pendingBindings"); assertEquals(1, clears.size()); assertTrue(restore < clears.getFirst());
        exceptionCannotReachWrite(retry, restore, clears.getFirst());
        var closure = backend.methods.stream().filter(m -> m.name.startsWith("lambda$captureBindings$")).findFirst().orElseThrow();
        assertTrue(call(closure, RESTORE, "restore") < writes(closure, BACKEND, "pendingBindings").getFirst());
        assertTrue(closure.tryCatchBlocks.isEmpty(), "Closure must propagate restoration failure without releasing pendingBindings");
        assertTrue(calls(method(backend, "restorationPending")).isEmpty(), "Pending state is a direct retained-owner query");
    }

    @Test void nativeUploadEntryCaptureAllocationBindingAndDataAreAllSeparatelyAttributed() throws Exception {
        var build = read(BUILD); var finish = method(build, "finish");
        int entry = checkpoint(finish, "native.upload.entry"), capture = creation(finish, "io/earthplatform/minecraft/scene/SceneMesh$BufferBindings");
        int pending = writes(finish, BUILD, "pendingUploadRestore").getFirst();
        int checkedCapture = checkpoint(finish, "native.upload.capture");
        assertTrue(entry < capture && capture < checkedCapture && checkedCapture < pending,
                "Failed read-only capture must never retain untrusted queried binding IDs");
        var upload = build.methods.stream().filter(m -> m.name.startsWith("lambda$finish$")
                && calls(m).stream().anyMatch(c -> c.owner.equals("com/mojang/blaze3d/vertex/VertexBuffer") && c.name.equals("upload"))).findFirst().orElseThrow();
        int retain = call(upload, "io/earthplatform/minecraft/scene/SceneBuildResources", "retain");
        int allocate = call(upload, "com/mojang/blaze3d/vertex/VertexBuffer", "<init>");
        int allocationCheck = checkpoint(upload, "native.upload.allocate");
        int bind = call(upload, "com/mojang/blaze3d/vertex/VertexBuffer", "bind"), bindCheck = checkpoint(upload, "native.upload.bind");
        int data = call(upload, "com/mojang/blaze3d/vertex/VertexBuffer", "upload"), dataCheck = checkpoint(upload, "native.upload.data");
        assertTrue(retain < allocate && allocate < allocationCheck && allocationCheck < bind && bind < bindCheck && bindCheck < data && data < dataCheck);
    }

    @Test void nativeRestorationIsRetainedUntilSuccessAndRetriedBeforeGpuDeletion() throws Exception {
        var build = read(BUILD); var restore = method(build, "restoreUploadBindings");
        int retry = call(restore, NATIVE_RESTORE, "restore"); var clear = writes(restore, BUILD, "pendingUploadRestore");
        assertEquals(1, clear.size()); assertTrue(retry < clear.getFirst()); assertTrue(restore.tryCatchBlocks.isEmpty());
        var close = method(build, "close");
        assertTrue(call(close, BUILD, "restoreUploadBindings") < call(close, "io/earthplatform/minecraft/scene/SceneBuildResources", "close"));
        var labels = new HashSet<String>();
        for (var m : build.methods) if (m.name.startsWith("lambda$finish$")) for (var instruction : m.instructions)
            if (instruction instanceof LdcInsnNode constant && constant.cst instanceof String label) labels.add(label);
        assertTrue(labels.containsAll(List.of("native.upload.restore.entry", "native.upload.restore.exit")));
        var finish = method(build, "finish");
        assertTrue(call(finish, BUILD, "restoreUploadBindings") < call(finish, "io/earthplatform/minecraft/scene/SceneBuildResources", "releaseToPublishedOwner"));
    }

    @Test void hostChecksAreInsideTheOncePerActualFrameGuardAndBracketPreparation() throws Exception {
        var frame = method(read(SESSION), "shaderFrame"); int entry = checkpoint(frame, "shader.prepare.entry");
        int prepare = call(frame, SESSION, "updatePreparedPair"), exit = checkpoint(frame, "shader.prepare.exit");
        assertTrue(entry < prepare && prepare < exit);
        int branchIndex = -1;
        for (int i = 0; i < entry; i++) if (frame.instructions.get(i) instanceof FieldInsnNode field && field.name.equals("actualRenderFrame")) {
            var next = field.getNext(); while (next != null && next.getOpcode() < 0) next = next.getNext();
            if (next != null && next.getOpcode() == Opcodes.LCMP) {
                next = next.getNext(); while (next != null && next.getOpcode() < 0) next = next.getNext();
                if (next instanceof JumpInsnNode branch && branch.getOpcode() == Opcodes.IFEQ) branchIndex = frame.instructions.indexOf(next);
            }
        }
        assertTrue(branchIndex >= 0); var seen = new HashSet<Integer>(); var queue = new ArrayDeque<Integer>();
        queue.add(frame.instructions.indexOf(((JumpInsnNode) frame.instructions.get(branchIndex)).label));
        while (!queue.isEmpty()) {
            int i = queue.removeFirst(); if (i < 0 || i >= frame.instructions.size() || !seen.add(i)) continue;
            var instruction = frame.instructions.get(i); int opcode = instruction.getOpcode();
            if (instruction instanceof JumpInsnNode branch) { queue.add(frame.instructions.indexOf(branch.label)); if (opcode != Opcodes.GOTO) queue.add(i + 1); }
            else if (opcode != Opcodes.ATHROW && !(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)) queue.add(i + 1);
        }
        assertFalse(seen.contains(entry)); assertFalse(seen.contains(prepare)); assertFalse(seen.contains(exit));
    }
}
