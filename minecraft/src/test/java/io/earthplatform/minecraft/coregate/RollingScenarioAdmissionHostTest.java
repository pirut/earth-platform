package io.earthplatform.minecraft.coregate;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiled host wiring proof; reading bytecode does not start Minecraft or attest a GUI run. */
class RollingScenarioAdmissionHostTest {
    private static final String HOST = "io/earthplatform/minecraft/coregate/RollingGateCommands";
    private static final String SESSION = HOST + "$Session";
    private static final String WITNESS = "io/earthplatform/minecraft/coregate/RollingScenarioClientWitness";
    private static final String HANDSHAKE = "io/earthplatform/minecraft/coregate/RollingPublicationHandshake";
    private static final String RUNNER = "io/earthplatform/minecraft/coregate/RollingIndexedScenario";
    private static ClassNode read(String name) throws IOException {
        try (var in = RollingScenarioAdmissionHostTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(in, "Compiled candidate class required: " + name);
            var result = new ClassNode(); new ClassReader(in).accept(result, 0); return result;
        }
    }
    private static MethodNode method(ClassNode type, String name) {
        return type.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static List<MethodInsnNode> calls(MethodNode method) {
        return java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i).toList();
    }
    private static int call(MethodNode method, String owner, String name) {
        var found = calls(method).stream().filter(i -> i.owner.equals(owner) && i.name.equals(name)).toList();
        assertEquals(1, found.size(), "One exact " + owner + "." + name + " call required");
        return method.instructions.indexOf(found.getFirst());
    }
    private static AbstractInsnNode nextOpcode(AbstractInsnNode node) {
        for (var next = node.getNext(); next != null; next = next.getNext())
            if (next.getOpcode() >= 0) return next;
        throw new AssertionError("Missing next instruction");
    }

    /** Explore actual normal/exception control flow while forcing one predicate result to false. */
    private static Set<Integer> reachableWithFalseResult(MethodNode method, int predicate) {
        var branch = nextOpcode(method.instructions.get(predicate));
        assertInstanceOf(JumpInsnNode.class, branch);
        assertTrue(branch.getOpcode() == Opcodes.IFEQ || branch.getOpcode() == Opcodes.IFNE);
        int condition = method.instructions.indexOf(branch);
        var seen = new HashSet<Integer>(); var queue = new ArrayDeque<Integer>(); queue.add(0);
        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            if (index < 0 || index >= method.instructions.size() || !seen.add(index)) continue;
            var instruction = method.instructions.get(index); int opcode = instruction.getOpcode();
            for (var handler : method.tryCatchBlocks) {
                if (method.instructions.indexOf(handler.start) <= index && index < method.instructions.indexOf(handler.end))
                    queue.add(method.instructions.indexOf(handler.handler));
            }
            if (index == condition) {
                queue.add(opcode == Opcodes.IFEQ ? method.instructions.indexOf(((JumpInsnNode) branch).label) : index + 1);
            } else if (instruction instanceof JumpInsnNode jump) {
                queue.add(method.instructions.indexOf(jump.label));
                if (opcode != Opcodes.GOTO) queue.add(index + 1);
            } else if (instruction instanceof TableSwitchInsnNode table) {
                queue.add(method.instructions.indexOf(table.dflt));
                for (var label : table.labels) queue.add(method.instructions.indexOf(label));
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                queue.add(method.instructions.indexOf(lookup.dflt));
                for (var label : lookup.labels) queue.add(method.instructions.indexOf(label));
            } else if (opcode != Opcodes.ATHROW && !(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)) {
                queue.add(index + 1);
            }
        }
        assertTrue(seen.contains(predicate), "The tested gate must be reachable from entry"); return seen;
    }

    @Test void commonAdmissionRefusalDominatesEveryEvidencePlayerAndRunnerMutation() throws Exception {
        var admission = method(read(HOST), "scenario");
        int gate = call(admission, HOST, "scenarioReady");
        var refused = reachableWithFalseResult(admission, gate);
        int effects = 0;
        for (int index = 0; index < admission.instructions.size(); index++) {
            var instruction = admission.instructions.get(index);
            boolean mutation = instruction.getOpcode() == Opcodes.PUTFIELD || instruction.getOpcode() == Opcodes.PUTSTATIC;
            if (instruction instanceof MethodInsnNode invoke) {
                mutation |= invoke.owner.equals("java/nio/file/Files")
                        || invoke.owner.equals(HOST) && List.of("walk", "scenarioPose", "writeScenario").contains(invoke.name)
                        || invoke.owner.equals(RUNNER) && invoke.name.equals("<init>");
            }
            if (instruction instanceof TypeInsnNode type) mutation |= type.getOpcode() == Opcodes.NEW && type.desc.equals(RUNNER);
            if (mutation) {
                effects++; assertFalse(refused.contains(index), "Refusal reaches mutation at instruction " + index);
            }
        }
        assertTrue(effects >= 8, "Evidence fields, I/O, player mode/pose and runner construction must be covered");
        assertTrue(refused.stream().anyMatch(i -> admission.instructions.get(i).getOpcode() == Opcodes.IRETURN));
        assertTrue(refused.stream().map(admission.instructions::get).anyMatch(i -> i instanceof LdcInsnNode text
                && text.cst instanceof String s && s.contains("exact current client READY receipt")));
    }

    @Test void theSameAdmissionEntryIsUsedForExerciseReloadAndRestart() throws Exception {
        var host = read(HOST); var modes = new HashSet<String>(); int callers = 0;
        for (var method : host.methods) for (var invoke : calls(method)) {
            if (!invoke.owner.equals(HOST) || !invoke.name.equals("scenario")) continue;
            callers++;
            for (var instruction : method.instructions) if (instruction instanceof FieldInsnNode field
                    && field.owner.equals(RUNNER + "$Mode") && field.getOpcode() == Opcodes.GETSTATIC) modes.add(field.name);
        }
        assertEquals(3, callers); assertEquals(Set.of("EXERCISE", "AFTER_RELOAD", "AFTER_RESTART"), modes);
    }

    @Test void readinessRechecksTheRetainedExactWitnessAlongsideExistingHostFences() throws Exception {
        var ready = method(read(HOST), "scenarioReady");
        call(ready, WITNESS, "current");
        for (var name : List.of("committedPublication", "resourceGeneration", "committedDigest", "pending"))
            call(ready, HANDSHAKE, name);
        call(ready, "io/earthplatform/minecraft/coregate/CoreCollisionBinding", "ready");
        var fields = new HashSet<String>();
        for (var instruction : ready.instructions) if (instruction instanceof FieldInsnNode field
                && field.owner.equals(SESSION)) fields.add(field.name);
        assertTrue(fields.containsAll(Set.of("scenarioClientWitness", "epoch", "stopping", "editPending", "ray", "committedManifest")));
        var witnessFields = read(SESSION).fields.stream().filter(f -> f.name.equals("scenarioClientWitness")).toList();
        assertEquals(1, witnessFields.size()); assertEquals("L" + WITNESS + ";", witnessFields.getFirst().desc);
        assertTrue(read(SESSION).fields.stream().noneMatch(f -> Set.of("scenarioClientPublication", "scenarioClientCoverage", "scenarioClientReloadSequence").contains(f.name)),
                "No loose scalar/default channel may bypass exact witness presence");
    }

    @Test void anUnmatchedReceiptCannotOverwriteTheWitnessOrAcknowledgeAScreenshot() throws Exception {
        var receipt = method(read(HOST), "scenarioReceipt");
        var refused = reachableWithFalseResult(receipt, call(receipt, WITNESS, "current"));
        for (int index : refused) {
            var instruction = receipt.instructions.get(index);
            assertFalse(instruction instanceof FieldInsnNode field && field.owner.equals(SESSION)
                    && field.getOpcode() == Opcodes.PUTFIELD, "Mismatched receipt mutates session state");
        }
        int writes = 0;
        for (var method : read(HOST).methods) for (var instruction : method.instructions)
            if (instruction instanceof FieldInsnNode field && field.owner.equals(SESSION)
                    && field.name.equals("scenarioClientWitness") && field.getOpcode() == Opcodes.PUTFIELD) {
                assertEquals("scenarioReceipt", method.name); writes++;
            }
        assertEquals(1, writes, "Only validated incoming receipt handling may supply an actual client witness");
    }

    @Test void observationGetsReloadAndCoverageFromTheSameImmutableWitness() throws Exception {
        var observation = method(read(HOST), "scenarioObserve");
        call(observation, WITNESS, "reloadSequence"); call(observation, WITNESS, "coverage");
        call(observation, HOST, "scenarioReady");
    }

    @Test void mismatchedScreenshotStampStillFencesWithoutClearingOrCountingItsAck() throws Exception {
        var receipt = method(read(HOST), "scenarioReceipt");
        var refused = reachableWithFalseResult(receipt, call(receipt,
                "io/earthplatform/minecraft/coregate/RollingScenarioCheckpoint", "matches"));
        boolean cancels = false;
        for (int index : refused) {
            var instruction = receipt.instructions.get(index);
            assertFalse(instruction instanceof FieldInsnNode field && field.owner.equals(SESSION)
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && Set.of("scenarioCapture", "scenarioCheckpoint", "scenarioScreenshotAcks").contains(field.name));
            cancels |= instruction instanceof MethodInsnNode invoke && invoke.owner.equals(RUNNER) && invoke.name.equals("cancel");
        }
        assertTrue(cancels, "Wrong exact screenshot ACK retains the failure fence");
    }

    @Test void ordinaryActionPumpStillRequiresExactReadinessAndScreenshotAckStillHasADeadline() throws Exception {
        var pump = method(read(HOST), "pumpScenario");
        var refused = reachableWithFalseResult(pump, call(pump, HOST, "scenarioReady"));
        for (int index : refused) {
            var instruction = pump.instructions.get(index);
            assertFalse(instruction instanceof MethodInsnNode invoke && invoke.owner.equals(HOST)
                    && Set.of("scenarioPose", "focus", "mouseIntent", "scenarioTravelNext", "scenarioComplete").contains(invoke.name),
                    "Unready pair executes or completes a scenario action");
        }
        assertTrue(calls(pump).stream().anyMatch(c -> c.owner.equals(RUNNER) && c.name.equals("cancel")));
        assertTrue(calls(pump).stream().anyMatch(c -> c.owner.equals(RUNNER) && c.name.equals("tick")),
                "Readiness delays must still run the bounded runner clock");
        assertTrue(java.util.stream.StreamSupport.stream(pump.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof LdcInsnNode text && "Screenshot checkpoint acknowledgement deadline".equals(text.cst)));
    }
}
