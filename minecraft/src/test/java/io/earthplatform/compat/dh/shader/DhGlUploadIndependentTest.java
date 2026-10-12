package io.earthplatform.compat.dh.shader;

import io.earthplatform.minecraft.lod.DhGlUpload;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Faults the real production upload sequence; no copied upload/ownership algorithm. */
class DhGlUploadIndependentTest {
    private static final List<String> PHASES = List.of("far.upload.entry", "far.upload.vao.allocate", "far.upload.vao.bind",
            "far.upload.vbo.allocate", "far.upload.vbo.bind", "far.upload.vertex.data", "far.upload.ibo.allocate",
            "far.upload.ibo.bind", "far.upload.index.data", "far.upload.attribute0.enable", "far.upload.attribute0.pointer",
            "far.upload.attribute1.enable", "far.upload.attribute1.pointer", "far.upload.attribute2.enable", "far.upload.attribute2.pointer");
    private static final List<String> OPERATIONS = List.of("generate.vao", "bind.vao:31", "generate.vbo", "bind.buffer:34962:47",
            "vertex.data", "generate.ibo", "bind.buffer:34963:59", "index.data", "enable:0", "integer:0:4:5123:16:0",
            "enable:1", "attribute:1:4:5121:true:16:8", "enable:2", "integer:2:4:5121:16:12");
    private static final class Owner implements DhGlUpload.Owner {
        int vao, vbo, ibo;
        public int vao() { return vao; } public int vbo() { return vbo; } public int ibo() { return ibo; }
        public void vao(int id) { vao = id; } public void vbo(int id) { vbo = id; } public void ibo(int id) { ibo = id; }
        List<Integer> ids() { return List.of(vao, vbo, ibo); }
    }
    private static final class Gl implements DhGlUpload.Gl, StrictGlErrors.Gl {
        final List<String> operations = new ArrayList<>(), phases = new ArrayList<>();
        final List<List<Integer>> observedOwners = new ArrayList<>();
        final ByteBuffer vertices = ByteBuffer.allocate(64); final IntBuffer indices = IntBuffer.wrap(new int[]{0, 1, 2, 2, 3, 0});
        String badPhase, badOperation; Throwable javaFailure; int pendingError, errorReads, generation;
        void operation(String operation) {
            operations.add(operation);
            if (operation.equals(badOperation)) {
                if (javaFailure instanceof Error error) throw error;
                if (javaFailure instanceof RuntimeException error) throw error;
            }
        }
        public int genVertexArray() { operation("generate.vao"); return 31; }
        public int genBuffer() { int index = ++generation; operation(index == 1 ? "generate.vbo" : "generate.ibo"); return index == 1 ? 47 : 59; }
        public void bindVertexArray(int id) { operation("bind.vao:" + id); }
        public void bindBuffer(int target, int id) { operation("bind.buffer:" + target + ":" + id); }
        public void vertexData(ByteBuffer value) { assertSame(vertices, value); operation("vertex.data"); }
        public void indexData(IntBuffer value) { assertSame(indices, value); operation("index.data"); }
        public void enableAttribute(int index) { operation("enable:" + index); }
        public void integerAttribute(int index, int count, int type, int stride, long offset) { operation("integer:" + index + ":" + count + ":" + type + ":" + stride + ":" + offset); }
        public void attribute(int index, int count, int type, boolean normalized, int stride, long offset) { operation("attribute:" + index + ":" + count + ":" + type + ":" + normalized + ":" + stride + ":" + offset); }
        public void check(String phase, DhGlUpload.Owner owner) {
            phases.add(phase); observedOwners.add(List.of(owner.vao(), owner.vbo(), owner.ibo()));
            if (phase.equals(badPhase)) pendingError = 0x502;
            StrictGlErrors.check(this, phase, owner.vao(), owner.vbo(), owner.ibo());
        }
        public int error() { errorReads++; int error = pendingError; pendingError = 0; return error; }
        public StrictGlErrors.Bindings bindings(int vao, int vbo, int ibo) {
            return new StrictGlErrors.Bindings(vao, vbo, ibo, 101, 102, 103, vao, vbo, ibo, true, true, true, true);
        }
        void upload(Owner owner) { DhGlUpload.upload(this, owner, vertices, indices); }
    }
    private static List<Integer> afterPhase(int phase) { return List.of(phase >= 1 ? 31 : 0, phase >= 3 ? 47 : 0, phase >= 6 ? 59 : 0); }

    @Test void inheritedErrorRunsNoAllocationBindingOrUploadAndLeavesOwnerEmpty() {
        var gl = new Gl(); var owner = new Owner(); gl.pendingError = 0x501;
        var failure = assertThrows(StrictGlErrors.Failure.class, () -> gl.upload(owner));
        assertEquals("far.upload.entry", failure.phase()); assertEquals(0x501, failure.errorCode());
        assertTrue(gl.operations.isEmpty()); assertEquals(List.of("far.upload.entry"), gl.phases);
        assertEquals(List.of(0, 0, 0), owner.ids()); assertEquals(2, gl.errorReads);
    }

    @Test void eachCheckpointFailureRetainsEveryReturnedOwnerNameAndStopsBeforeTheNextOperation() {
        for (int phase = 0; phase < PHASES.size(); phase++) {
            var gl = new Gl(); var owner = new Owner(); gl.badPhase = PHASES.get(phase);
            var failure = assertThrows(StrictGlErrors.Failure.class, () -> gl.upload(owner));
            assertEquals(gl.badPhase, failure.phase()); assertEquals(0x502, failure.errorCode());
            assertEquals(afterPhase(phase), owner.ids(), gl.badPhase);
            assertEquals(owner.ids(), List.of(failure.bindings().ownedVao(), failure.bindings().ownedVbo(), failure.bindings().ownedIbo()));
            assertEquals(PHASES.subList(0, phase + 1), gl.phases); assertEquals(OPERATIONS.subList(0, phase), gl.operations);
            assertEquals(phase + 2, gl.errorReads); assertEquals(owner.ids(), gl.observedOwners.getLast());
        }
    }

    @Test void javaExceptionsAndErrorsAtEveryOperationPreservePreviouslyReturnedIdsWithoutContinuing() {
        for (boolean asError : List.of(false, true)) for (int operation = 0; operation < OPERATIONS.size(); operation++) {
            var gl = new Gl(); var owner = new Owner(); gl.badOperation = OPERATIONS.get(operation);
            gl.javaFailure = asError ? new AssertionError(gl.badOperation) : new IllegalStateException(gl.badOperation);
            assertSame(gl.javaFailure, assertThrows(Throwable.class, () -> gl.upload(owner)));
            assertEquals(OPERATIONS.subList(0, operation + 1), gl.operations);
            assertEquals(PHASES.subList(0, operation + 1), gl.phases);
            // A throwing generator returned no new name; all earlier returned names remain retained.
            assertEquals(afterPhase(operation), owner.ids(), gl.badOperation);
            assertEquals(operation + 1, gl.errorReads);
        }
    }

    @Test void successfulProductionSequencePreservesLayoutPayloadIdentityAndExactOwnerAssignmentOrder() {
        var gl = new Gl(); var owner = new Owner(); gl.upload(owner);
        assertEquals(PHASES, gl.phases); assertEquals(OPERATIONS, gl.operations); assertEquals(List.of(31, 47, 59), owner.ids());
        for (int phase = 0; phase < PHASES.size(); phase++) assertEquals(afterPhase(phase), gl.observedOwners.get(phase), PHASES.get(phase));
        assertEquals(15, gl.errorReads); assertEquals(0, gl.vertices.position()); assertEquals(0, gl.indices.position());
    }

    @Test void nullInputsFailBeforeAnyGlReadOrAllocation() {
        var gl = new Gl(); var owner = new Owner();
        assertThrows(NullPointerException.class, () -> DhGlUpload.upload(null, owner, gl.vertices, gl.indices));
        assertThrows(NullPointerException.class, () -> DhGlUpload.upload(gl, null, gl.vertices, gl.indices));
        assertThrows(NullPointerException.class, () -> DhGlUpload.upload(gl, owner, null, gl.indices));
        assertThrows(NullPointerException.class, () -> DhGlUpload.upload(gl, owner, gl.vertices, null));
        assertTrue(gl.operations.isEmpty()); assertTrue(gl.phases.isEmpty()); assertEquals(0, gl.errorReads); assertEquals(List.of(0, 0, 0), owner.ids());
    }
}
