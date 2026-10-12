package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.visual.NativeVisualPublication;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Independent host-boundary checks. These do not replace packaged keyboard/camera qualification. */
class RollingVisualFrontierHostWiringTest {
    private static final String BASE = "io/earthplatform/minecraft/coregate/";
    private static final String CLIENT = BASE + "RollingGateClient";
    private static final String CLIENT_SESSION = CLIENT + "$Session";
    private static final String COMMANDS = BASE + "RollingGateCommands";
    private static final String VISUAL = "io/earthplatform/minecraft/visual/NativeVisualPublication";
    private static final CubePos CURRENT = new CubePos(604879, 460, -194643);
    private static final CubePos BACKWARD = CURRENT.offset(0, -1, 0);
    private static final double X = 9678071.300000012;
    private static final double Z = -3114282.8080077083;
    private static final double CAMERA_MARGIN = 5;

    @Test void frozen033PairNineBodyCertificateDoesNotCertifyItsCamera() {
        // Exact PAIR_READY_9 witness from the sustained Space hold. No downward input was used.
        double cameraY = 7376.917273017791;
        var oldCamera = NativeVisualPublication.cameraPermit(CURRENT);
        var candidateCamera = NativeVisualPublication.cameraPermit(BACKWARD);
        var body = bodyAtCamera(cameraY);

        assertEquals(7354, oldCamera.minY());
        assertEquals(7382, oldCamera.maxY());
        assertEquals(7338, candidateCamera.minY());
        assertEquals(7366, candidateCamera.maxY());
        assertTrue(RollingWindowAdmission.contains(body, CURRENT));
        assertTrue(RollingWindowAdmission.contains(body, BACKWARD));
        assertTrue(oldCamera.contains(X, cameraY, Z));
        assertFalse(candidateCamera.contains(X, cameraY, Z),
                "Body-known admission must never stand in for candidate camera coverage");
    }

    @Test void frozen033PublishedOnlyFenceAllowsEscapeFromBackwardCandidate() {
        // READY_8's camera fits both candidate domains; STAGING_9 was already outside its inset.
        var oldCamera = NativeVisualPublication.cameraPermit(CURRENT);
        var candidateCamera = NativeVisualPublication.cameraPermit(BACKWARD);
        double beforeMetadata = 7360.818516378075;
        double atMetadata = 7363.253242619888;
        double beforeUnsafeInstall = 7376.917273017791;
        assertTrue(insetContains(oldCamera, beforeMetadata));
        assertTrue(insetContains(candidateCamera, beforeMetadata));
        assertTrue(atMetadata - beforeMetadata < RollingMotionGuard.MAX_TRANSLATION_PER_AXIS);
        assertTrue(insetContains(oldCamera, atMetadata));
        assertFalse(insetContains(candidateCamera, atMetadata),
                "The speculative candidate must fence prediction before metadata latency");
        for (double y = beforeMetadata; y < beforeUnsafeInstall; y = Math.min(y + 3, beforeUnsafeInstall)) {
            double next = Math.min(y + 3, beforeUnsafeInstall);
            assertTrue(insetContains(oldCamera, next));
            assertTrue(RollingWindowAdmission.contains(bodyAtCamera(next), BACKWARD));
        }
        assertFalse(candidateCamera.contains(X, beforeUnsafeInstall, Z));
    }

    @Test void sameSelectionRecoveryRequiresBothPhysicalDrainsAndFreshExactReceipt() {
        var selection = new NativeDistanceClientTransition.Selection(12, 12, 0);
        var client = new NativeDistanceClientTransition(9);
        var initial = client.mutation(selection);
        var server = new NativeDistanceRestartGate(initial);
        assertTrue(client.drain(initial.drain()));
        var initialReceipt = client.physicallyDrained();
        assertNotNull(initialReceipt);
        assertTrue(server.clientDrained(initialReceipt));
        assertFalse(server.ready(), "Client release alone cannot authorize a replacement actor");
        server.serverDrained(true);
        assertTrue(server.ready());

        var recovery = client.mutation(selection);
        assertEquals(initial.serial() + 1, recovery.serial());
        assertEquals(initial.selected(), recovery.selected());
        assertEquals(initial.effective(), recovery.effective());
        assertEquals(initial.serverLimit(), recovery.serverLimit());
        assertTrue(server.request(recovery));
        assertFalse(server.ready());
        assertFalse(client.permitsPreflight());
        assertFalse(client.drain(initial.drain()));
        assertFalse(server.clientDrained(initialReceipt));
        assertNull(client.physicallyDrained());
        assertTrue(client.drain(recovery.drain()));
        var recoveryReceipt = client.physicallyDrained();
        assertNotNull(recoveryReceipt);
        assertTrue(recovery.sameSelection(recoveryReceipt));
        assertTrue(server.clientDrained(recoveryReceipt));
        assertTrue(server.ready());
        assertTrue(client.permitsPreflight());
        assertNull(client.physicallyDrained(), "A drained receipt is issued only once");
    }

    @Test void actualNativeAndFarCallbacksRetainStrictCameraValidation() throws IOException {
        var session = read(CLIENT_SESSION);
        assertTrue(has(method(session, "shaderNative"), call(VISUAL, "requireCamera")),
                "The fix must not remove validation from native or shadow callbacks");
        assertTrue(has(method(session, "shaderFar"), call(VISUAL, "requireCamera")),
                "Far callbacks must retain their own actual-camera validation");
        var require = method(read(VISUAL), "requireCamera");
        assertTrue(has(require, call(VISUAL + "$CameraDomain", "contains")));
        assertTrue(has(require, instruction -> instruction.getOpcode() == Opcodes.ATHROW),
                "Out-of-permit draw must remain an exception, never a permissive fallback");
    }

    @Test void restartCapturesHeldPoseBeforeClosingActorAndReopensAtThatPose() throws IOException {
        var commands = read(COMMANDS);
        var change = method(commands, "nativeDistanceChange");
        before(change, call(BASE + "RollingMotionGuard", "sameBody"),
                call(COMMANDS + "$PendingDistance", "<init>"));
        before(change, call(COMMANDS + "$PendingDistance", "<init>"),
                call("io/earthplatform/minecraft/enginebridge/RollingCubeActor", "close"));
        before(change, call(BASE + "CoreCollisionBinding", "fence"),
                call("io/earthplatform/minecraft/enginebridge/RollingCubeActor", "close"));
        var owner = method(commands, "distanceOwnerCurrent");
        assertTrue(has(owner, call("net/minecraft/server/level/ServerPlayer", "position")));
        assertTrue(has(owner, call("net/minecraft/world/phys/Vec3", "distanceToSqr")));
        assertTrue(has(owner, field("net/minecraft/server/level/ServerPlayer", "noPhysics", Opcodes.GETFIELD)));
        assertTrue(has(owner, call("net/minecraft/server/level/ServerPlayerGameMode", "getGameModeForPlayer")));
        before(method(commands, "pumpOpen"), call(BASE + "RollingSessionDescriptor", "resumeAt"),
                call(COMMANDS, "open"));
        var pump = method(commands, "pumpDistance");
        before(pump, call(BASE + "NativeDistanceRestartGate", "ready"),
                call(BASE + "PlayableEntryPayload", "<init>"));
        assertTrue(has(pump, call(COMMANDS, "distanceFailed")),
                "Rebuild watchdog/owner failure needs a finite, explicit terminal outcome");
    }

    @Test void badCandidateCannotReachPreparedOrTheFirstPublicationMutation() throws IOException {
        var session = read(CLIENT_SESSION);
        var install = method(session, "installPair");
        requireBoolean(install, call(CLIENT_SESSION, "validateCandidate"),
                index(install, field(CLIENT_SESSION, "visible", Opcodes.PUTFIELD)), true);
        requireBoolean(install, call(CLIENT_SESSION, "validateCandidate"),
                index(install, call("io/earthplatform/minecraft/lod/DhPreparedLodView", "commitPrepared")), true);
        requireBoolean(install, call(CLIENT_SESSION, "validateCandidate"),
                index(install, field(CLIENT_SESSION, "publishedVisual", Opcodes.PUTFIELD)), true);
        var prepare = method(session, "updatePreparedPair");
        requireBoolean(prepare, call(CLIENT_SESSION, "validateCandidate"),
                index(prepare, field(CLIENT_SESSION, "preparedSent", Opcodes.PUTFIELD)), true);
        assertTrue(has(method(session, "validateCandidate"), call(CLIENT_SESSION, "candidateCameraReady")));
        assertTrue(has(method(session, "candidateCameraReady"), call(CLIENT_SESSION, "queueVisualRecovery")),
                "An invalid candidate must reach the bounded recovery path");
    }

    @Test void speculativeClientFencePrecedesNetworkRequestAndServerFencePrecedesCommit() throws IOException {
        var motion = method(read(CLIENT_SESSION), "visualMotion");
        requireBoolean(motion, call(BASE + "RollingVisualFrontier", "request"),
                index(motion, call("net/neoforged/neoforge/network/PacketDistributor", "sendToServer")), true);
        var commands = read(COMMANDS);
        requireBoolean(method(commands, "tryGrant"), call(BASE + "RollingMotionGuard", "visualCameraKnown"),
                index(method(commands, "tryGrant"), call(BASE + "RollingMotionGuard", "beginCommit")), true);
        requireBoolean(method(commands, "tryCommitReady"), call(BASE + "RollingMotionGuard", "visualCameraKnown"),
                index(method(commands, "tryCommitReady"), call("io/earthplatform/minecraft/enginebridge/RollingCubeActor", "commitPrepared")), true);
        requireBoolean(method(commands, "finishPublication"), call(BASE + "RollingMotionGuard", "visualCameraKnown"),
                index(method(commands, "finishPublication"), call(BASE + "CoreCollisionBinding", "publish")), true);
    }

    @Test void recoveryIsQueuedInsideCallbacksAndRunsThroughTheExistingRestartOnTick() throws IOException {
        var client = read(CLIENT);
        var session = read(CLIENT_SESSION);
        var queued = method(session, "queueVisualRecovery");
        assertFalse(has(queued, call(CLIENT, "restartNativeDistance")),
                "Callbacks must not recursively enter shader restoration and cleanup");
        assertFalse(has(queued, call(CLIENT, "closeCurrent")));
        before(method(client, "tick"), call(CLIENT, "pumpVisualRecovery"), call(CLIENT, "pumpDistanceTransition"));
        assertTrue(has(method(client, "pumpVisualRecovery"), call(CLIENT, "restartNativeDistance")));
        var restart = method(client, "restartNativeDistance");
        assertTrue(has(restart, call(BASE + "NativeDistanceClientTransition", "mutation")));
        assertTrue(has(restart, call("net/neoforged/neoforge/network/PacketDistributor", "sendToServer")));
        for (var method : List.of(queued, method(client, "pumpVisualRecovery"), restart)) {
            assertFalse(has(method, instruction -> instruction instanceof MethodInsnNode call
                    && Set.of("teleport", "teleportTo", "setGameMode", "setPos", "setPosRaw", "absMoveTo").contains(call.name)),
                    "Recovery must not manufacture a new pose or mode: " + method.name);
            assertFalse(has(method, call("net/minecraft/client/OptionInstance", "set")),
                    "Same-selection recovery must not change user Options");
        }
    }

    private static AABB bodyAtCamera(double cameraY) {
        double feetY = cameraY - 1.62;
        return new AABB(X - .3, feetY, Z - .3, X + .3, feetY + 1.8, Z + .3);
    }

    private static boolean insetContains(NativeVisualPublication.CameraDomain camera, double y) {
        return X - CAMERA_MARGIN >= camera.minX() && X + CAMERA_MARGIN <= camera.maxX()
                && y - CAMERA_MARGIN >= camera.minY() && y + CAMERA_MARGIN <= camera.maxY()
                && Z - CAMERA_MARGIN >= camera.minZ() && Z + CAMERA_MARGIN <= camera.maxZ();
    }

    private static ClassNode read(String name) throws IOException {
        try (var input = RollingVisualFrontierHostWiringTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode type, String name) {
        return type.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<AbstractInsnNode> code(MethodNode method) {
        var instructions = new ArrayList<AbstractInsnNode>();
        method.instructions.iterator().forEachRemaining(instructions::add);
        return instructions;
    }

    private static boolean has(MethodNode method, Predicate<AbstractInsnNode> match) {
        return code(method).stream().anyMatch(match);
    }

    private static Predicate<AbstractInsnNode> call(String owner, String name) {
        return instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name);
    }

    private static Predicate<AbstractInsnNode> field(String owner, String name, int opcode) {
        return instruction -> instruction instanceof FieldInsnNode field
                && field.owner.equals(owner) && field.name.equals(name) && field.getOpcode() == opcode;
    }

    private static int index(MethodNode method, Predicate<AbstractInsnNode> match) {
        var instructions = code(method);
        for (int i = 0; i < instructions.size(); i++) if (match.test(instructions.get(i))) return i;
        fail("Missing production instruction in " + method.name);
        return -1;
    }

    private static void before(MethodNode method, Predicate<AbstractInsnNode> first, Predicate<AbstractInsnNode> second) {
        assertTrue(index(method, first) < index(method, second), "Required host order in " + method.name);
    }

    private static void requireBoolean(MethodNode method, Predicate<AbstractInsnNode> check, int target, boolean required) {
        int condition = index(method, check);
        assertFalse(reachable(method, 0, target, Set.of(condition)),
                "Required guard cannot be bypassed in " + method.name);
        AbstractInsnNode next = method.instructions.get(condition).getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        var branch = assertInstanceOf(JumpInsnNode.class, next);
        assertTrue(branch.getOpcode() == Opcodes.IFEQ || branch.getOpcode() == Opcodes.IFNE);
        int jump = method.instructions.indexOf(branch.label);
        int fall = method.instructions.indexOf(branch) + 1;
        int whenTrue = branch.getOpcode() == Opcodes.IFNE ? jump : fall;
        int whenFalse = branch.getOpcode() == Opcodes.IFEQ ? jump : fall;
        assertTrue(reachable(method, required ? whenTrue : whenFalse, target, Set.of()),
                "Admitted guard must reach its intended action in " + method.name);
        assertFalse(reachable(method, required ? whenFalse : whenTrue, target, Set.of()),
                "Refused guard must not reach its protected action in " + method.name);
    }

    private static boolean reachable(MethodNode method, int start, int target, Set<Integer> blocked) {
        var pending = new ArrayDeque<Integer>();
        var visited = new HashSet<Integer>();
        pending.add(start);
        while (!pending.isEmpty()) {
            int index = pending.removeFirst();
            if (index < 0 || index >= method.instructions.size() || blocked.contains(index) || !visited.add(index)) continue;
            if (index == target) return true;
            var instruction = method.instructions.get(index);
            int opcode = instruction.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) continue;
            if (instruction instanceof JumpInsnNode jump) {
                pending.add(method.instructions.indexOf(jump.label));
                if (opcode != Opcodes.GOTO) pending.add(index + 1);
            } else if (instruction instanceof TableSwitchInsnNode table) {
                pending.add(method.instructions.indexOf(table.dflt));
                for (var label : table.labels) pending.add(method.instructions.indexOf(label));
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                pending.add(method.instructions.indexOf(lookup.dflt));
                for (var label : lookup.labels) pending.add(method.instructions.indexOf(label));
            } else pending.add(index + 1);
        }
        return false;
    }
}
