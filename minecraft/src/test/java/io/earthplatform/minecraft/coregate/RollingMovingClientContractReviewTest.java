package io.earthplatform.minecraft.coregate;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact-overload and owner-fence bytecode contracts, separate from live GUI acceptance. */
class RollingMovingClientContractReviewTest {
    private static final String BASE = "io/earthplatform/minecraft/";
    private static final String SESSION = BASE + "coregate/RollingGateClient$Session";
    private static final String POLICY = BASE + "coregate/RollingFarPublication", CENTER = POLICY + "$Center";
    private static final String PROFILE = BASE + "coregate/RollingSessionDescriptor";
    private static final String REPLICA = BASE + "coregate/RollingGateReplica";
    private static final String FAR = BASE + "lod/DhPreparedLodView";
    private static final String PRESENTATION = BASE + "coregate/RollingEditPresentation";
    private static final String MESH = BASE + "scene/SceneMesh", REUSE = MESH + "$Reuse";
    private static final String LAYOUT = BASE + "lod/DhLodLayout", DEPTH = BASE + "coregate/RollingDepthComposition";
    private static final String GUARD = BASE + "renderprobe/RenderPublicationGuard";
    private static final String READY = BASE + "enginebridge/RollingCubeActor$StageReady";
    private static final String REPLACEMENT = BASE + "lod/DhReplacementInput";

    @Test void acceptedExactReadyIsTheOnlyAuthorityForNativeDigestAndFinalDesiredCenter() throws IOException {
        var code = method(read(SESSION), "ready");
        assertTrue(code.at(REPLICA, "acceptReady") < code.at(REPLICA, "candidateManifestDigest"));
        assertTrue(code.at(REPLICA, "candidateManifestDigest") < code.at(READY, "candidateCenter"));
        assertTrue(code.at(READY, "candidateCenter") < code.at(POLICY, "center"));
        assertTrue(code.at(POLICY, "center") < code.at(POLICY, "digest"));
        assertTrue(code.calls().contains(new Call(POLICY, "digest", "(L" + PROFILE + ";Ljava/lang/String;L" + CENTER + ";)Ljava/lang/String;")));
        assertTrue(code.at(POLICY, "digest") < code.field(Opcodes.PUTFIELD, SESSION, "digest"));
        assertFalse(code.fields().stream().anyMatch(f -> f.name().equals("camera")));
    }

    @Test void privatePreparationUsesExplicitCenterOverloadAndRetainsOneViewPerSession() throws IOException {
        var methods = read(SESSION); var code = method(methods, "prepareFarCoverage");
        assertTrue(code.at(SESSION, "validateCandidate") < code.at(FAR, "prepareCoverage"));
        assertTrue(code.field(Opcodes.PUTFIELD, SESSION, "preparedFarCenter") < code.at(FAR, "prepareCoverage"));
        assertEquals(1, code.count(FAR, "prepareCoverage"));
        assertTrue(code.calls().contains(new Call(FAR, "prepareCoverage", "(JLjava/util/List;JJL" + REPLACEMENT + ";)L" + FAR + "$CoverageTicket;")));
        assertTrue(code.at(SESSION, "prepareReplacementInput") < code.at(FAR, "prepareCoverage"));
        assertTrue(code.field(Opcodes.GETFIELD, SESSION, "proposedReplacement") < code.at(FAR, "prepareCoverage"));
        assertFalse(code.calls().contains(new Call(FAR, "prepareCoverage", "(JLjava/util/List;JJ)L" + FAR + "$CoverageTicket;")),
                "The production path must bind its explicit replacement or NONE to the same far ticket");
        assertFalse(code.calls().contains(new Call(FAR, "prepareCoverage", "(JLjava/util/List;)L" + FAR + "$CoverageTicket;")));
        String visual=BASE+"visual/NativeVisualPublication";
        assertEquals(1,code.count(FAR,"prepareCoverageDistance"));
        assertTrue(code.calls().contains(new Call(FAR,"prepareCoverageDistance","(JLjava/util/List;JJL"+REPLACEMENT+";L"+visual+";L"+BASE+"coregate/RollingDistanceBodyBinding$NativeJoined;)L"+FAR+"$CoverageTicket;")));
        for(String preparation:List.of("prepareCoverage","prepareCoverageDistance","prepareCoverageArena","prepareCoverageSet")) {
            assertEquals(1,code.count(FAR,preparation));
            assertTrue(code.at(SESSION,"validateCandidate")<code.at(FAR,preparation));
            assertTrue(code.at(SESSION,"prepareReplacementInput")<code.at(FAR,preparation));
            assertTrue(code.at(SESSION,"prepareVisual")<code.at(FAR,preparation));
            assertTrue(code.field(Opcodes.PUTFIELD,SESSION,"preparedFarCenter")<code.at(FAR,preparation));
        }
        assertTrue(code.field(Opcodes.GETFIELD,SESSION,"proposedVisual")<code.at(FAR,"prepareCoverageDistance"));
        assertTrue(code.field(Opcodes.GETFIELD,SESSION,"proposedReplacement")<code.at(FAR,"prepareCoverageDistance"));
        assertEquals(3, methods.values().stream().mapToLong(c -> c.count(FAR, "<init>")).sum());
        assertTrue(code.at(PROFILE, "lodLayout") < code.at(FAR, "<init>"));
        // Keep the exact source/world/epoch/resource/center/layout binding and require the resolved installed runtime.
        String installedConstructor="(L"+BASE+"scene/SceneSurfaceSampler;Lio/earthplatform/core/WorldIdentity;JJJJL"
                +LAYOUT+";Lio/earthplatform/compat/dh/InstalledDhResolver$Runtime;)V";
        String shaderConstructor=installedConstructor.substring(0,installedConstructor.length()-2)
                +"Lio/earthplatform/compat/dh/shader/IrisShaderFarBackend;)V";
        String distanceConstructor=shaderConstructor.substring(0,shaderConstructor.length()-2)+"L"+FAR+"$DistanceConfig;)V";
        assertEquals(Set.of(installedConstructor,shaderConstructor,distanceConstructor),code.calls().stream()
                .filter(c -> c.owner().equals(FAR) && c.name().equals("<init>"))
                .map(Call::descriptor).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1,code.count(BASE+"lod/DhNativeDistanceBudget","configuration"));
        assertTrue(code.calls().contains(new Call(BASE+"lod/DhNativeDistanceBudget","configuration","()L"+FAR+"$DistanceConfig;")));
        assertSingleConditionalFarOwner();
        assertTrue(code.field(Opcodes.GETFIELD,SESSION,"installedRuntime")<code.at(FAR,"<init>"));
        assertFalse(code.fields().stream().anyMatch(f -> f.name().equals("camera")));
    }

    @Test void farCommitChecksCenterBeforeAndAfterAndNeverExposesPartialNativeInstallation() throws IOException {
        var code = method(read(SESSION), "installPair");
        int validate = code.at(SESSION, "validateCandidate"), equal = code.at(CENTER, "equals");
        int prepared = code.at(FAR, "isPrepared"), commit = code.at(FAR, "commitPrepared");
        int coverage = code.at(FAR, "coverageReady");
        assertTrue(code.field(Opcodes.GETFIELD, SESSION, "allowed") < validate);
        assertTrue(validate < equal); assertTrue(equal < prepared); assertTrue(prepared < commit);
        assertTrue(code.field(Opcodes.PUTFIELD, SESSION, "visible") < commit);
        assertTrue(code.at(PRESENTATION, "invalidate") < commit); assertTrue(commit < coverage);
        assertTrue(code.calls().contains(new Call(FAR, "coverageReady", "(JJJL" + LAYOUT + ";)Z")));
        assertTrue(coverage < code.field(Opcodes.PUTFIELD, SESSION, "publishedFarCenter"));
        assertTrue(code.field(Opcodes.PUTFIELD, SESSION, "publishedFarCenter") < code.lastAt(PRESENTATION, "retire"));
        assertTrue(code.at(PRESENTATION,"retire") < commit,"Distance history must retire before far/native publication mutation");
        assertTrue(code.lastAt(PRESENTATION, "retire") < code.at(REUSE, "commit"));
        assertTrue(code.at(REUSE, "commit") < code.at(SESSION, "closeExcept"));
        assertTrue(code.at(SESSION, "closeExcept") < code.lastField(Opcodes.PUTFIELD, SESSION, "visible"));
        assertTrue(code.lastField(Opcodes.PUTFIELD, SESSION, "visible") < code.field(Opcodes.PUTFIELD, SESSION, "pairSent"));
        assertTrue(code.field(Opcodes.PUTFIELD, SESSION, "pairSent") < code.at(SESSION, "send"));
    }

    @Test void currentDrawAndEditAdmissionUsePublishedCenterRatherThanDesiredOrPendingCenter() throws IOException {
        var methods = read(SESSION); var helper = method(methods, "publishedFarCoverageReady");
        assertEquals(1, helper.count(FAR, "coverageReady"));
        assertTrue(helper.calls().contains(new Call(FAR, "coverageReady", "(JJJL" + LAYOUT + ";)Z")));
        assertTrue(helper.field(Opcodes.GETFIELD, SESSION, "publishedFarCenter") < helper.at(FAR, "coverageReady"));
        assertFalse(helper.fields().stream().anyMatch(f -> List.of("preparedFarCenter", "desiredFarCenter").contains(f.name())));
        var render = method(methods, "render");
        assertEquals(1, render.count(SESSION, "publishedFarCoverageReady"));
        assertTrue(render.at(SESSION, "publishedFarCoverageReady") < render.at(DEPTH, "draw"));
        var draws = methods.entrySet().stream().filter(e -> render.callbacks.contains(e.getKey())
                && e.getValue().count(FAR, "draw") == 1).toList();
        assertEquals(1, draws.size()); var current = draws.getFirst();
        assertTrue(render.callbacks.contains(current.getKey()), "The depth helper callback must contain BOTH current renderers");
        assertTrue(current.getValue().at(FAR, "draw") < current.getValue().at(MESH, "draw"));
        assertTrue(current.getValue().at(GUARD, "invalidationRequested") < current.getValue().at(FAR, "draw"));
        assertTrue(current.getValue().count(GUARD, "invalidationRequested") >= 3);
        assertEquals(0, render.count(FAR, "draw")); assertEquals(0, render.count(MESH, "draw"));
        assertTrue(render.at(DEPTH, "draw") < render.lastField(Opcodes.PUTFIELD, SESSION, "drawn"));
        // The separate shader path has its own current-pair gate and never enters the diagnostic depth transaction.
        var shader=method(methods,"shaderDrawCurrentFar");
        assertEquals(1,shader.count(FAR,"draw"));assertEquals(0,shader.count(MESH,"draw"));assertEquals(0,shader.count(DEPTH,"draw"));
        assertEquals(2,methods.values().stream().filter(c->c.count(FAR,"draw")==1).count(),
                "Only the default depth callback and the separately fenced shader far pass may submit current far geometry");
        var shaderCurrent=method(methods,"shaderPresentationCurrent");
        assertEquals(1,shaderCurrent.count(SESSION,"publishedFarCoverageReady"));
        assertFalse(shaderCurrent.fields().stream().anyMatch(f->List.of("preparedFarCenter","desiredFarCenter").contains(f.name())));
        assertTrue(method(methods,"shaderFar").at(SESSION,"shaderPresentationCurrent")<method(methods,"shaderFar").at(SESSION,"shaderDrawCurrentFar"));
        var edit = method(methods, "beginEdit");
        assertTrue(edit.at(SESSION, "publishedFarCoverageReady") < edit.at(PRESENTATION, "capture"));
    }

    @Test void cameraAndFarPumpsCannotChooseOrPublishCentersOutsideTheAllowedFramePath() throws IOException {
        var methods = read(SESSION);
        for (var entry : methods.entrySet()) {
            if (entry.getValue().count(FAR, "commitPrepared") > 0) assertTrue(entry.getKey().startsWith("installPair("), entry.getKey());
            if (entry.getValue().count(POLICY, "center") > 0)
                assertFalse(entry.getValue().fields().stream().anyMatch(f -> f.name().equals("camera")), entry.getKey());
        }
        var render = method(methods, "render");
        assertEquals(0, render.count(POLICY, "center"));
        assertEquals(0, render.count(FAR, "prepareCoverage"));
        assertEquals(0, render.count(FAR, "commitPrepared"));
        assertTrue(render.events.stream().anyMatch(v -> v instanceof Instruction i && i.opcode() == Opcodes.MONITORENTER));
        assertTrue(render.events.stream().anyMatch(v -> v instanceof Instruction i && i.opcode() == Opcodes.MONITOREXIT));
        assertEquals(0, method(read(FAR), "pump").count(FAR, "commitPrepared"));
        assertEquals(0, method(read(FAR), "draw").count(FAR, "prepareCoverage"));
    }

    @Test void staticBackgroundGeometryReadsImmutableInputAndNeverRetainsPublishedViewAuthority() throws Exception {
        String source = FAR + "$GeometrySource", composition = BASE + "lod/DhReplacementComposition";
        assertTrue(Modifier.isStatic(Class.forName(source.replace('/', '.')).getModifiers()));
        var methods = read(FAR); var worker = method(read(source), "load");
        assertEquals(1, methods.values().stream().mapToLong(c -> c.count(source, "<init>")).sum());
        assertEquals(0, methods.values().stream().mapToLong(c -> c.count(composition, "build")).sum(),
                "A View-capturing lambda must not own worker geometry generation");
        assertEquals(1, worker.count(composition, "build"));
        assertTrue(worker.count(FAR + "$Input", "center") >= 2);
        assertTrue(worker.count(FAR + "$Input", "layout") >= 1);
        assertTrue(worker.at(FAR + "$Input", "center") < worker.at(composition, "build"));
        assertTrue(worker.at(FAR + "$Input", "layout") < worker.at(composition, "build"));
        assertTrue(worker.at(FAR + "$Input", "replacement") < worker.at(composition, "build"));
        assertFalse(worker.fields().stream().anyMatch(f -> f.owner().equals(FAR)),
                "The worker must retain neither mutable view state nor its authority guards");
        var composed = method(read(composition), "build");
        assertEquals(1, composed.count(BASE + "lod/DhAdaptiveTerrainData", "<init>"));
        assertTrue(composed.calls().stream().filter(c -> c.owner().equals(BASE + "lod/DhAdaptiveTerrainData") && c.name().equals("<init>"))
                .allMatch(c -> c.descriptor().endsWith("L" + LAYOUT + ";)V")));
        assertFalse(composed.fields().stream().anyMatch(f -> f.owner().equals(FAR)));
    }

    @Test void lateReadyAndAllowanceRemainSerializedAgainstExistingPendingEditOrRecenter() throws IOException {
        var methods = read(SESSION);
        for (String name : List.of("metadata", "beginEdit")) {
            var code = method(methods, name);
            assertTrue(code.field(Opcodes.GETFIELD, SESSION, "pending") < code.field(Opcodes.PUTFIELD, SESSION, "publication"));
            assertTrue(code.field(Opcodes.GETFIELD, SESSION, "preparedFar") < code.field(Opcodes.PUTFIELD, SESSION, "publication"));
        }
        var allow = method(methods, "allow");
        assertTrue(allow.at(SESSION, "checkPair") < allow.at(FAR, "isPrepared"));
        assertTrue(allow.at(FAR, "isPrepared") < allow.field(Opcodes.PUTFIELD, SESSION, "allowed"));
        assertTrue(allow.field(Opcodes.PUTFIELD, SESSION, "allowed") < allow.at(SESSION, "fenceCollision"));
    }

    @Test void frozenPresentationEnclosesTheEntireDepthTransactionAndCleanupRetainsRestoreOwnership() throws IOException {
        var methods=read(SESSION);var render=method(methods,"render");
        var historical=methods.entrySet().stream().filter(e->e.getValue().count(FAR,"drawPresented")==1
                &&e.getValue().count(MESH,"drawPresented")==1).toList();
        assertEquals(1,historical.size());var pass=historical.getFirst();
        assertEquals(1,pass.getValue().count(MESH,"drawPresented"));
        assertTrue(pass.getValue().at(FAR,"drawPresented")<pass.getValue().at(MESH,"drawPresented"));
        assertTrue(pass.getValue().count(GUARD,"invalidationRequested")>=3);
        var wrappers=methods.entrySet().stream().filter(e->e.getValue().count(DEPTH,"draw")==1&&e.getValue().callbacks.contains(pass.getKey())).toList();
        assertEquals(1,wrappers.size());assertTrue(render.callbacks.contains(wrappers.getFirst().getKey()));
        assertEquals(1,render.count(PRESENTATION,"draw"));assertEquals(1,render.count(DEPTH,"draw"));
        assertEquals(0,pass.getValue().count(FAR,"draw"));assertEquals(0,pass.getValue().count(MESH,"draw"));
        var shaderFar=method(methods,"shaderFar");var shaderNative=method(methods,"shaderNative");
        var shaderHistorical=methods.entrySet().stream().filter(e->shaderFar.callbacks.contains(e.getKey())&&e.getValue().count(FAR,"drawPresented")==1).toList();
        assertEquals(2,shaderHistorical.size(),"Distance body and legacy mesh history each retain their own typed presentation draw");for(var historicalPath:shaderHistorical)assertEquals(0,historicalPath.getValue().count(MESH,"drawPresented"));
        assertEquals(3,methods.values().stream().filter(c->c.count(FAR,"drawPresented")==1).count());
        assertEquals(2,shaderFar.count(PRESENTATION,"draw"));assertEquals(1,shaderNative.count(PRESENTATION,"draw"));
        assertEquals(1,methods.entrySet().stream().filter(e->shaderNative.callbacks.contains(e.getKey())&&e.getValue().count(MESH,"drawPresentedLayer")==1).count());
        for(var shaderCode:List.of(shaderFar,shaderNative,shaderHistorical.getFirst().getValue())) {
            assertEquals(0,shaderCode.count(DEPTH,"draw"));assertEquals(0,shaderCode.count(FAR,"commitPrepared"));assertEquals(0,shaderCode.count(FAR,"prepareCoverage"));
        }
        for(var code:List.of(render,pass.getValue(),wrappers.getFirst().getValue())) {
            assertEquals(0,code.count("com/mojang/blaze3d/systems/RenderSystem","clear"));
            assertEquals(0,code.count(FAR,"commitPrepared"));assertEquals(0,code.count(FAR,"prepareCoverage"));
        }
        var close=method(methods,"close");
        assertTrue(close.at(DEPTH,"close")<close.at(SESSION,"closeExcept"));
        assertTrue(close.at(DEPTH,"close")<close.at(FAR,"close"));
        assertTrue(close.at(DEPTH,"close")<close.field(Opcodes.PUTFIELD,SESSION,"disposed"));
    }

    @Test void diagnosticAndGameplayOwnersAreMutuallyExclusiveIncludingFailedCleanup() throws IOException {
        String client=BASE+"coregate/RollingGateClient",probe=BASE+"lod/DhLongDistanceGpuProbe";
        var handle=method(read(client),"handle");
        assertTrue(handle.at(probe,"hasRetainedResources")<handle.at(SESSION,"<init>"));
        assertTrue(handle.at(probe,"hasRetainedResources")<handle.at("net/neoforged/neoforge/network/PacketDistributor","sendToServer"));
        assertTrue(handle.at("net/neoforged/neoforge/network/PacketDistributor","sendToServer")<handle.at(SESSION,"<init>"));
        assertTrue(handle.events.subList(handle.at(probe,"hasRetainedResources"),handle.at(SESSION,"<init>")).contains(new Instruction(Opcodes.RETURN)),
                "Rejected initial epoch must return without allocating a gameplay session");
        var retained=method(read(client),"hasRetainedSession");assertTrue(retained.fields().contains(new Field(Opcodes.GETSTATIC,client,"current")));
        var probeMethods=read(probe);var run=method(probeMethods,"run");
        assertTrue(run.at(client,"hasRetainedSession")<run.at(probe+"$Resources","<init>"));
        assertTrue(run.events.subList(run.at(probe+"$Resources","<init>"),run.at(probe,"initialize")).contains(new Field(Opcodes.PUTSTATIC,probe,"retained")),
                "Register the new probe owner after construction and before any fallible initialization");
        assertTrue(probeMethods.values().stream().mapToLong(c->c.count(DEPTH,"draw")).sum()>=1,
                "Actual readback must exercise the production depth-state owner, not only the matrix factory");
        var owner=method(read(probe+"$Resources"),"close");
        assertEquals(1,owner.count(DEPTH,"close"));
    }

    private static Code method(Map<String, Code> methods, String name) {
        var found = methods.entrySet().stream().filter(e -> e.getKey().startsWith(name + "(")).toList();
        assertEquals(1, found.size(), "Unique exact method required: " + name); return found.getFirst().getValue();
    }
    /** Three explicit policy constructors are mutually exclusive; an existing owner is never replaced. */
    private static void assertSingleConditionalFarOwner() throws IOException {
        var node=new ClassNode();try(var in=RollingMovingClientContractReviewTest.class.getClassLoader().getResourceAsStream(SESSION+".class")){
            assertNotNull(in);new ClassReader(in).accept(node,0);
        }
        var method=node.methods.stream().filter(m->m.name.equals("prepareFarCoverage")).findFirst().orElseThrow();
        var constructors=new HashSet<Integer>();JumpInsnNode existing=null,mode=null,distance=null;
        for(int i=0;i<method.instructions.size();i++){
            var instruction=method.instructions.get(i);
            if(instruction instanceof MethodInsnNode call&&call.owner.equals(FAR)&&call.name.equals("<init>"))constructors.add(i);
            if(distance==null&&instruction instanceof MethodInsnNode call&&call.owner.equals(SESSION)&&call.name.equals("distanceMode")){
                var next=instruction.getNext();while(next!=null&&next.getOpcode()<0)next=next.getNext();
                if(next instanceof JumpInsnNode branch&&branch.getOpcode()==Opcodes.IFEQ)distance=branch;
            }
            if(instruction instanceof FieldInsnNode field&&field.owner.equals(SESSION)&&field.getOpcode()==Opcodes.GETFIELD){
                var next=instruction.getNext();while(next!=null&&next.getOpcode()<0)next=next.getNext();
                if(next instanceof JumpInsnNode branch){if(field.name.equals("far")&&branch.getOpcode()==Opcodes.IFNONNULL)existing=branch;
                    if(field.name.equals("shaderMode")&&branch.getOpcode()==Opcodes.IFEQ)mode=branch;}
            }
        }
        assertEquals(3,constructors.size());assertNotNull(existing);assertNotNull(mode);assertNotNull(distance);
        assertTrue(java.util.Collections.disjoint(constructors,reachable(method,Map.of(existing,true))),"An existing far View must never be replaced");
        var distancePath=reachable(method,Map.of(existing,false,distance,false));
        var shaderPath=reachable(method,Map.of(existing,false,distance,true,mode,false));
        var ordinaryPath=reachable(method,Map.of(existing,false,distance,true,mode,true));
        var selected=new HashSet<Integer>();
        for(var path:List.of(distancePath,shaderPath,ordinaryPath)){
            var allocated=constructors.stream().filter(path::contains).toList();
            assertEquals(1,allocated.size(),"Each exact renderer policy may construct only one far View");
            assertTrue(selected.add(allocated.getFirst()),"Separate policies must retain their own exact constructor");
        }
        int distanceConstructor=constructors.stream().filter(distancePath::contains).findFirst().orElseThrow();
        var distanceCall=(MethodInsnNode)method.instructions.get(distanceConstructor);
        assertTrue(distanceCall.desc.endsWith("L"+FAR+"$DistanceConfig;)V"));
        var prior=distanceCall.getPrevious();while(prior!=null&&prior.getOpcode()<0)prior=prior.getPrevious();
        var config=assertInstanceOf(MethodInsnNode.class,prior);
        assertEquals(BASE+"lod/DhNativeDistanceBudget",config.owner);assertEquals("configuration",config.name);
    }
    private static Set<Integer> reachable(MethodNode method,Map<JumpInsnNode,Boolean> forced){
        var seen=new HashSet<Integer>();var pending=new ArrayDeque<Integer>();pending.add(0);
        while(!pending.isEmpty()){
            int index=pending.removeFirst();if(index<0||index>=method.instructions.size()||!seen.add(index))continue;
            var instruction=method.instructions.get(index);int opcode=instruction.getOpcode();
            for(var handler:method.tryCatchBlocks)if(method.instructions.indexOf(handler.start)<=index&&index<method.instructions.indexOf(handler.end))pending.add(method.instructions.indexOf(handler.handler));
            if(instruction instanceof JumpInsnNode branch&&forced.containsKey(branch))pending.add(forced.get(branch)?method.instructions.indexOf(branch.label):index+1);
            else if(instruction instanceof JumpInsnNode jump){pending.add(method.instructions.indexOf(jump.label));if(opcode!=Opcodes.GOTO)pending.add(index+1);}
            else if(opcode!=Opcodes.ATHROW&&!(opcode>=Opcodes.IRETURN&&opcode<=Opcodes.RETURN))pending.add(index+1);
        }return seen;
    }
    private static Map<String, Code> read(String owner) throws IOException {
        var methods = new LinkedHashMap<String, Code>();
        try (var stream = RollingMovingClientContractReviewTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            assertNotNull(stream, owner);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    var code = new Code(); assertNull(methods.put(name + descriptor, code));
                    return new MethodVisitor(Opcodes.ASM9) {
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean iface) {
                            code.events.add(new Call(owner, name, descriptor));
                        }
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            code.events.add(new Field(opcode, owner, name));
                        }
                        public void visitInsn(int opcode) { code.events.add(new Instruction(opcode)); }
                        public void visitInvokeDynamicInsn(String name,String descriptor,Handle bootstrap,Object... args) {
                            if(bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory")) for(var arg:args)
                                if(arg instanceof Handle h && h.getOwner().equals(SESSION)) code.callbacks.add(h.getName()+h.getDesc());
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return methods;
    }
    private record Call(String owner, String name, String descriptor) {}
    private record Field(int opcode, String owner, String name) {}
    private record Instruction(int opcode) {}
    private static final class Code {
        final List<Object> events = new ArrayList<>();
        final List<String> callbacks = new ArrayList<>();
        List<Call> calls() { return events.stream().filter(Call.class::isInstance).map(Call.class::cast).toList(); }
        List<Field> fields() { return events.stream().filter(Field.class::isInstance).map(Field.class::cast).toList(); }
        long count(String owner, String name) { return calls().stream().filter(c -> c.owner().equals(owner) && c.name().equals(name)).count(); }
        int at(String owner, String name) {
            for (int i = 0; i < events.size(); i++) if (events.get(i) instanceof Call c && c.owner().equals(owner) && c.name().equals(name)) return i;
            fail("Missing call " + owner + "." + name); return -1;
        }
        int lastAt(String owner,String name){for(int i=events.size()-1;i>=0;i--)if(events.get(i) instanceof Call c&&c.owner().equals(owner)&&c.name().equals(name))return i;fail("Missing call "+owner+"."+name);return -1;}
        int field(int opcode, String owner, String name) {
            for (int i = 0; i < events.size(); i++) if (events.get(i).equals(new Field(opcode, owner, name))) return i;
            fail("Missing field " + owner + "." + name); return -1;
        }
        int lastField(int opcode, String owner, String name) {
            for (int i = events.size() - 1; i >= 0; i--) if (events.get(i).equals(new Field(opcode, owner, name))) return i;
            fail("Missing last field " + owner + "." + name); return -1;
        }
    }
}
