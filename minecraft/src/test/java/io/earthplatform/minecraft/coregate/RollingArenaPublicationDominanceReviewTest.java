package io.earthplatform.minecraft.coregate;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent control-flow proof: call presence and lexical order admitted the028 arena bypass.
 * These tests require authorization on EVERY entry-to-publication path and self-test the proof
 * against a synthetic arena-only bypass that leaves the original call and order intact.
 */
class RollingArenaPublicationDominanceReviewTest {
    private static final String BASE = "io/earthplatform/minecraft/coregate/";
    private static final String HOST = BASE + "RollingGateCommands";
    private static final String ACTOR = "io/earthplatform/minecraft/enginebridge/RollingCubeActor";
    private static final String HANDSHAKE = BASE + "RollingPublicationHandshake";

    @Test void hostAuthorizationDominatesCollisionManifestAndCommittedOnEveryNormalPath() throws IOException {
        var host = readHost();
        var finish = method(host, "finishPublication");
        var authorization = unique(finish, call(HOST, "authorizePublication"));
        assertTrue(finish.tryCatchBlocks.isEmpty(), "Failed authorization must escape to the enclosing fail/close path");
        assertTrue(method(host, "authorizePublication").tryCatchBlocks.isEmpty(), "Actor refusal must never be swallowed");
        for (var effect : List.of(call(HOST, "windowSnapshots"), call(BASE + "CoreCollisionBinding", "publish"),
                field(HOST + "$Session", "committedManifest", Opcodes.PUTFIELD),
                call(HANDSHAKE, "committed"), call(HOST, "sendPair"))) {
            var effectNode = unique(finish, effect);
            assertTrue(dominates(finish, authorization, effectNode),
                    "An arena/mode branch can reach " + describe(effectNode) + " without exact actor authorization");
        }
        for (var guard : List.of(call(HOST, "authoritativePairMatches"), call(HOST, "bodyInsideWindow")))
            assertTrue(dominates(finish, unique(finish, guard), authorization));
        assertEquals(1, host.methods.stream().filter(m -> any(m, call(BASE + "CoreCollisionBinding", "publish"))).count(),
                "Any second collision publication entry point needs the same independent authority proof");
        assertEquals(1, host.methods.stream().filter(m -> any(m, call(HANDSHAKE, "committed"))).count(),
                "COMMITTED cannot be advanced through an unreviewed alternate host path");
    }

    @Test void dominanceProofDetectsArenaOnlyBypassEvenWithIdenticalCallPresenceAndOrder() throws IOException {
        var original = method(readHost(), "finishPublication");
        var altered = copy(original);
        var authorization = unique(altered, call(HOST, "authorizePublication"));
        var after = new LabelNode();
        altered.instructions.insert(authorization, after);
        // Skip the complete five-argument invocation only for arenaMode. The call remains lexically
        // before collision and COMMITTED, exactly the weakness of the old presence/order contract.
        var gateStart = authorization;
        while (!(gateStart instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 1))
            gateStart = Objects.requireNonNull(gateStart.getPrevious());
        // The last ALOAD 1 feeds descriptor.arenaMode; find the first actor argument of this call.
        while (!(gateStart.getNext() instanceof FieldInsnNode f && f.owner.equals(HOST + "$Session")
                && f.name.equals("actor")))
            gateStart = Objects.requireNonNull(gateStart.getPrevious());
        var bypass = new InsnList();
        bypass.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bypass.add(new FieldInsnNode(Opcodes.GETFIELD, HOST + "$Session", "descriptor", "L" + BASE + "RollingSessionDescriptor;"));
        bypass.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BASE + "RollingSessionDescriptor", "arenaMode", "()Z", false));
        bypass.add(new JumpInsnNode(Opcodes.IFNE, after));
        altered.instructions.insertBefore(gateStart, bypass);
        var collision = unique(altered, call(BASE + "CoreCollisionBinding", "publish"));
        var committed = unique(altered, call(HANDSHAKE, "committed"));
        assertTrue(altered.instructions.indexOf(authorization) < altered.instructions.indexOf(collision));
        assertTrue(altered.instructions.indexOf(authorization) < altered.instructions.indexOf(committed));
        assertFalse(dominates(altered, authorization, collision), "Mutation must expose the arena-only bypass");
        assertFalse(dominates(altered, authorization, committed));
        assertTrue(dominates(original, unique(original, call(HOST, "authorizePublication")),
                unique(original, call(BASE + "CoreCollisionBinding", "publish"))), "Unmodified production must pass");
    }

    @Test void editAndMovementBranchesKeepTheirOriginalPairAndActorCommitBarriers() throws IOException {
        var host = readHost();
        var control = method(host, "control");
        var pair = unique(control, call(HANDSHAKE, "pairReady"));
        var dispatch = unique(control, call(HOST, "tryCommitReady"));
        assertTrue(dominates(control, pair, dispatch));
        assertBooleanRejection(control,pair,dispatch,false);
        assertTrue(dominates(control, unique(control, call(HOST, "authoritativePairMatches")), pair));
        assertFalse(any(control,call(HOST,"finishPublication")));assertFalse(any(control,call(ACTOR,"commitPrepared")));
        // Correction acknowledgement can arrive after PAIR_READY. Every retry enters the same
        // state/manifest/body gate instead of dropping the receipt or publishing early.
        var commit=method(host,"tryCommitReady");
        var editFinish=unique(commit,call(HOST,"finishPublication"));
        var stagedCommit=unique(commit,call(ACTOR,"commitPrepared"));
        var state=unique(commit,call(HANDSHAKE,"state"));
        var pairState=unique(commit,field(HANDSHAKE+"$State","PAIR_READY",Opcodes.GETSTATIC));
        var manifest=nodes(commit).stream().filter(field(HOST+"$Session","pendingManifest",Opcodes.GETFIELD)).findFirst().orElseThrow();
        for(var effect:List.of(editFinish,stagedCommit)) {
            assertTrue(dominates(commit,state,effect));assertTrue(dominates(commit,pairState,effect));
            assertTrue(dominates(commit,manifest,effect));
            var stateBranch=branchAfter(pairState);assertTrue(stateBranch.getOpcode()==Opcodes.IF_ACMPEQ||stateBranch.getOpcode()==Opcodes.IF_ACMPNE);
            assertFalse(reachableForced(commit,effect,stateBranch,stateBranch.getOpcode()==Opcodes.IF_ACMPNE));
            var nullBranch=branchAfter(manifest);assertTrue(nullBranch.getOpcode()==Opcodes.IFNULL||nullBranch.getOpcode()==Opcodes.IFNONNULL);
            assertFalse(reachableForced(commit,effect,nullBranch,nullBranch.getOpcode()==Opcodes.IFNULL));
            for(String guard:List.of("authoritativePairMatches","bodyInsideWindow")) {
                var check=unique(commit,call(HOST,guard));assertTrue(dominates(commit,check,effect));
                assertBooleanRejection(commit,check,effect,false);
            }
            var correction=unique(commit,call(BASE+"RollingMotionGuard","correctionPending"));
            assertTrue(dominates(commit,correction,effect));assertBooleanRejection(commit,correction,effect,true);
        }
        assertFalse(dominates(commit,stagedCommit,editFinish));assertFalse(dominates(commit,editFinish,stagedCommit));
        assertEquals(1,host.methods.stream().filter(m->any(m,call(HOST,"finishPublication"))).count());
        assertEquals(1,host.methods.stream().filter(m->any(m,call(ACTOR,"commitPrepared"))).count());
        var handler = method(host, "handleEvent");
        assertFalse(any(handler,call(HOST,"finishPublication")));
        var acknowledged=unique(handler,field(HOST+"$Session","motionActorCommitted",Opcodes.PUTFIELD));
        var retry=unique(handler,call(HOST,"tryCommitReady"));
        for(String component:List.of("world","epoch","generation","center"))
            assertTrue(dominates(handler,unique(handler,call(ACTOR+"$Committed",component)),acknowledged));
        for(String flag:List.of("motionCommitQueued","motionActorCommitted"))
            assertTrue(dominates(handler,unique(handler,field(HOST+"$Session",flag,Opcodes.GETFIELD)),acknowledged));
        assertTrue(dominates(handler,acknowledged,retry));
        assertTrue(any(method(host,"tick"),call(HOST,"tryCommitReady")));
    }

    private static JumpInsnNode branchAfter(AbstractInsnNode guard) {
        var next=guard.getNext();while(next!=null&&next.getOpcode()<0)next=next.getNext();
        return assertInstanceOf(JumpInsnNode.class,next);
    }
    private static void assertBooleanRejection(MethodNode method,AbstractInsnNode guard,AbstractInsnNode effect,boolean rejectedValue) {
        var branch=branchAfter(guard);assertTrue(branch.getOpcode()==Opcodes.IFEQ||branch.getOpcode()==Opcodes.IFNE);
        boolean takeTarget=rejectedValue?branch.getOpcode()==Opcodes.IFNE:branch.getOpcode()==Opcodes.IFEQ;
        assertFalse(reachableForced(method,effect,branch,takeTarget),"Rejected guard can still publish: "+describe(guard));
    }
    private static boolean reachableForced(MethodNode method,AbstractInsnNode target,JumpInsnNode forced,boolean takeTarget) {
        Set<AbstractInsnNode> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        var pending=new ArrayDeque<AbstractInsnNode>();pending.add(method.instructions.getFirst());
        while(!pending.isEmpty()) {
            var node=pending.removeFirst();if(!seen.add(node))continue;if(node==target)return true;
            if(node==forced){pending.add(takeTarget?forced.label:forced.getNext());continue;}
            for(var next:successors(method,node))if(next!=null)pending.add(next);
        }
        return false;
    }

    /** Removing a proposed dominator must make the target unreachable from method entry.
     * Includes switch edges and all applicable exception-handler edges, not just lexical flow.
     */
    private static boolean dominates(MethodNode method, AbstractInsnNode dominator, AbstractInsnNode target) {
        return reachable(method, target, null) && !reachable(method, target, dominator);
    }
    private static boolean reachable(MethodNode method, AbstractInsnNode target, AbstractInsnNode removed) {
        Set<AbstractInsnNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var work = new ArrayDeque<AbstractInsnNode>(); work.add(method.instructions.getFirst());
        while (!work.isEmpty()) {
            var node = work.removeFirst();
            if (node == removed || !visited.add(node)) continue;
            if (node == target) return true;
            for (var next : successors(method, node)) if (next != null) work.addLast(next);
        }
        return false;
    }
    private static List<AbstractInsnNode> successors(MethodNode method, AbstractInsnNode node) {
        var result = new ArrayList<AbstractInsnNode>();
        int opcode = node.getOpcode();
        if (node instanceof JumpInsnNode jump) {
            result.add(jump.label);
            if (opcode != Opcodes.GOTO && opcode != Opcodes.JSR) result.add(node.getNext());
        } else if (node instanceof TableSwitchInsnNode table) {
            result.add(table.dflt); result.addAll(table.labels);
        } else if (node instanceof LookupSwitchInsnNode lookup) {
            result.add(lookup.dflt); result.addAll(lookup.labels);
        } else if (opcode != Opcodes.ATHROW && !(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN))
            result.add(node.getNext());
        // Conservative exception edges ensure a swallowed failed authorization cannot publish.
        if (opcode >= 0) for (var handler : method.tryCatchBlocks) {
            int index = method.instructions.indexOf(node);
            if (index >= method.instructions.indexOf(handler.start) && index < method.instructions.indexOf(handler.end))
                result.add(handler.handler);
        }
        return result;
    }
    private static ClassNode readHost() throws IOException {
        try (var input = RollingArenaPublicationDominanceReviewTest.class.getClassLoader().getResourceAsStream(HOST + ".class")) {
            assertNotNull(input); var host = new ClassNode(); new ClassReader(input).accept(host, 0); return host;
        }
    }
    private static MethodNode copy(MethodNode source) {
        var target = new MethodNode(source.access, source.name, source.desc, source.signature, source.exceptions.toArray(String[]::new));
        source.accept(target); return target;
    }
    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static List<AbstractInsnNode> nodes(MethodNode method) { return Arrays.asList(method.instructions.toArray()); }
    private static boolean any(MethodNode method, Predicate<AbstractInsnNode> predicate) { return nodes(method).stream().anyMatch(predicate); }
    private static AbstractInsnNode unique(MethodNode method, Predicate<AbstractInsnNode> predicate) {
        var matches = nodes(method).stream().filter(predicate).toList();
        assertEquals(1, matches.size(), method.name + " requires exactly one matching instruction"); return matches.getFirst();
    }
    private static Predicate<AbstractInsnNode> call(String owner, String name) {
        return node -> node instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name);
    }
    private static Predicate<AbstractInsnNode> field(String owner, String name, int opcode) {
        return node -> node instanceof FieldInsnNode f && f.owner.equals(owner) && f.name.equals(name) && f.getOpcode() == opcode;
    }
    private static String describe(AbstractInsnNode node) {
        return node instanceof MethodInsnNode m ? m.owner + "." + m.name : node instanceof FieldInsnNode f ? f.owner + "." + f.name : node.toString();
    }
}
