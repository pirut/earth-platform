package io.earthplatform.minecraft.coregate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/** Independently checks that added stream diagnostics do not become authority or retain resources. */
class NativeStreamTimingAuthorityIndependentTest {
    private static final String STREAM = "io/earthplatform/minecraft/scene/NativeVisualStream";
    private static final String JOB = STREAM + "$Job", COUNTERS = STREAM + "$WorkerCounters";
    private static final Set<String> DIAGNOSTICS = Set.of(
            "pumpCalls", "pumpOwnerWallNanos", "pumpMaxOwnerWallNanos", "successfulUploadWallNanos", "maxSuccessfulUploadWallNanos",
            "pumpNoRequestStops", "pumpUploadLimitStops", "pumpTimeLimitStops", "pumpEmptyMailboxStops", "pumpFailedStops",
            "lastPumpWallNanos", "lastPumpBudgetNanos", "lastPumpUploadLimit", "lastPumpUploads", "lastPumpStop", "lastWorkerCounters");

    @Test void completedWorkProgressKeepsItsExactOriginalComponentsAndNeverReadsTiming() throws Exception {
        var progress = read(STREAM + "$Progress");
        assertEquals(List.of("stamp", "center", "captureIdentity", "sampledColumnsDone", "planTilesDone", "haloRowsDone",
                "faceScansDone", "producedPieces", "uploadedPieces", "sourceColumnsBound", "planTilesBound", "pagesBound",
                "haloRowsBound", "faceScansBound", "piecesBound", "workerExited"), progress.recordComponents.stream().map(r -> r.name).toList());
        assertNoTimingReads(method(read(STREAM), "progress"));
        assertNoTimingReads(method(read(JOB), "progress"));
    }

    @Test void readinessPublicationIdentityAndPhysicalCompletionDoNotDependOnTimers() throws Exception {
        var stream = read(STREAM);
        for (String name : List.of("request", "drawReady", "publication", "retainPresentation", "drawPlan", "closeComplete", "requireOpen"))
            assertNoTimingReads(method(stream, name));
    }

    @Test void retainedTerminalTimingHolderAndSnapshotsContainOnlyScalarsOrFixedEnums() throws Exception {
        var holder = read(COUNTERS);
        for (var field : holder.fields) {
            assertTrue(scalarOrEnum(field.desc), "No job, snapshot, page, queue, callback or native resource may be retained: " + field.name);
            assertTrue((field.access & Opcodes.ACC_VOLATILE) != 0, "Worker/owner publication remains visible: " + field.name);
        }
        for (String record : List.of("PumpTiming", "WorkerTiming")) {
            var node = read(STREAM + "$" + record);
            for (var component : node.recordComponents) assertTrue(scalarOrEnum(component.descriptor), component.name);
        }
        var retained = read(STREAM).fields.stream().filter(f -> f.name.equals("lastWorkerCounters")).findFirst().orElseThrow();
        assertEquals("L" + COUNTERS + ";", retained.desc);
        var evidence = read(STREAM + "$Evidence");
        assertEquals(List.of("physicalPieces", "gpuBytes", "peakGpuBytes", "peakPhysicalPieces", "cachedPages", "workerActive",
                "queuedPieces", "retainedCpuBytes", "uploadedPieces", "completedRequests", "reusedPages", "closed", "failed",
                "pumpTiming", "workerTiming"), evidence.recordComponents.stream().map(r -> r.name).toList());
    }

    @Test void successfulDrainAndCleanupRetainOnlyAnExistingHolderWithoutNewAllocation() throws Exception {
        var stream = read(STREAM);
        for (String name : List.of("finishRequest", "drainCancelled", "close")) {
            var method = method(stream, name); int retains = 0;
            for (var instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW)
                    assertFalse(allocation.desc.startsWith(STREAM + "$Worker"), name + " cannot allocate timing during physical ownership release");
                if (instruction instanceof MethodInsnNode call)
                    assertFalse(call.owner.equals(COUNTERS) && call.name.equals("snapshot"), name + " must not allocate a terminal timing snapshot");
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && field.name.equals("lastWorkerCounters")) {
                    retains++;
                    var previous = previous(field);
                    assertInstanceOf(FieldInsnNode.class, previous);
                    var source = (FieldInsnNode) previous;
                    assertEquals(JOB, source.owner); assertEquals("timing", source.name); assertEquals(Opcodes.GETFIELD, source.getOpcode());
                }
            }
            assertEquals(1, retains, name + " retains exactly the existing bounded holder");
        }
    }

    @Test void timingMutatorsAreScalarNonthrowingOperationsWithNoAuthorityCallbacks() throws Exception {
        var stream = read(STREAM);
        for (String name : List.of("recordPump", "elapsedSince", "addTiming")) {
            var method = method(stream, name);
            for (var instruction : method.instructions) {
                assertNotEquals(Opcodes.ATHROW, instruction.getOpcode(), name);
                if (instruction instanceof TypeInsnNode allocation) assertNotEquals(Opcodes.NEW, allocation.getOpcode(), name);
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) {
                    assertEquals(STREAM, field.owner, name); assertTrue(DIAGNOSTICS.contains(field.name), name + " wrote authority field " + field.name);
                }
                if (instruction instanceof MethodInsnNode call) {
                    assertTrue(call.owner.equals("java/lang/System") && call.name.equals("nanoTime")
                            || call.owner.equals("java/lang/Math") && call.name.equals("max")
                            || call.owner.equals(STREAM) && Set.of("elapsedSince", "addTiming").contains(call.name),
                            name + " calls an unreviewed fallible/authority operation: " + call.owner + "." + call.name);
                }
            }
        }
    }

    private static boolean scalarOrEnum(String descriptor) {
        return Set.of("J", "I", "Z", "L" + STREAM + "$PumpStopReason;", "L" + STREAM + "$WorkerStopReason;", "L" + STREAM + "$WorkerPhase;").contains(descriptor);
    }
    private static void assertNoTimingReads(MethodNode method) {
        for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)) {
                assertFalse(field.owner.equals(COUNTERS) || field.owner.equals(JOB) && field.name.equals("timing")
                        || field.owner.equals(STREAM) && DIAGNOSTICS.contains(field.name), method.name + " read diagnostic authority " + field.name);
            }
            if (instruction instanceof MethodInsnNode call)
                assertFalse(call.owner.equals(COUNTERS) || call.owner.equals(STREAM) && Set.of("pumpTiming", "workerTiming", "recordPump").contains(call.name), method.name);
        }
    }
    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static MethodNode method(ClassNode node, String name) { return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(); }
    private static ClassNode read(String name) throws Exception {
        try (var in = NativeStreamTimingAuthorityIndependentTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(in, name); var node = new ClassNode(); new ClassReader(in).accept(node, 0); return node;
        }
    }
}
