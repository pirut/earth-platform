package io.earthplatform.minecraft.coregate;

import com.google.gson.JsonObject;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.lod.*;
import io.earthplatform.minecraft.network.*;
import io.earthplatform.minecraft.scene.*;
import io.earthplatform.minecraft.visual.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes extracted production host methods against the real body proof, replica, admission,
 * immutable snapshots and Source/capture. Native GPU completions and DH/transport are explicit
 * dependency fixtures, not installed rendering claims. A broken collision-proof control must fail
 * the same oracle. This supplements the actual-camera host transaction tests.
 */
class RollingDistanceBodyHostTest {
    private static final String BASE="io/earthplatform/minecraft/", CORE=BASE+"coregate/";
    private static final String SESSION=CORE+"RollingGateClient$Session", ROOT=CORE+"RollingGateClient";
    private static final String SELF=CORE+"RollingDistanceBodyHostTest";
    private static final String GENERATED=CORE+"DistanceBodyExecutableHostFixture";
    private static final String BINDING=CORE+"RollingDistanceBodyBinding$Binding";
    private static final ThreadLocal<Inputs> INPUTS=new ThreadLocal<>();
    private static final Map<Boolean,Class<?>> TYPES=new HashMap<>();
    private static final Set<String> METHODS=Set.of("prepareDistanceBody","validateCandidate","publishedNativeCurrent","installPair","checkPair","committed","closeBodyAliases","bodyAliasesReleased","requestVisual","captureBodyPalette","bodyPaletteCurrent","bodyResourcesCurrent");
    /** This suite runs in the plain shader partition, without the FML JUnit bootstrap listener. */
    @BeforeAll static void initializeVanillaRegistries(){
        if(net.neoforged.fml.loading.LoadingModList.get()==null)
            net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        if(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size()==0)
            for(var block:net.minecraft.core.registries.BuiltInRegistries.BLOCK)
                for(var state:block.getStateDefinition().getPossibleStates())net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.add(state);
        assertTrue(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size()>0,"Actual vanilla state IDs must exist before constructing the real proof fixture");
    }
    private static final class Inputs {
        final DistanceBodyTestFixture data;
        final RollingEditPresentation<RollingDistanceBodyBinding.History,DhPreparedLodView.PresentationTicket> presentation;
        boolean captureReady=true,requestReady=true,visualReady=true,planReady=true,cameraReady=true,farReady=true,farCommit=true,drained=true,tamper;
        int requests,farPreparations,pairMessages,collisionPublications,fences;
        NativeVisualStamp requestedStamp;
        Object host;boolean mutatePaletteDuringGuard;
        Map<CubePos,EngineCubeSnapshot> collided;
        final List<String> events=new ArrayList<>();
        Inputs(DistanceBodyTestFixture data){this.data=data;presentation=new RollingEditPresentation<>(new RollingEditPresentation.Driver<>(){
            public RollingDistanceBodyBinding.History captureNative(){return data.joined.retainPresentation();}
            public DhPreparedLodView.PresentationTicket captureFar(){throw new AssertionError("DH historical retention has separate real View tests");}
            public boolean nativeCurrent(RollingDistanceBodyBinding.History t){return t.isPresentable();}
            public boolean farCurrent(DhPreparedLodView.PresentationTicket t){return true;}
            public void closeNative(RollingDistanceBodyBinding.History t){t.close();}
            public void closeFar(DhPreparedLodView.PresentationTicket t){}
        });}
    }

    @Test void privateVisualWorkStartsBeforeFullEqualityButFarWaitsForBoth() throws Throwable {
        try(var data=new DistanceBodyTestFixture()){
            data.stage(DistanceBodyTestFixture.PIN);var in=new Inputs(data);in.visualReady=false;var host=host(in,false);put(host,"preparedFar",null);
            invoke(host,"prepareDistanceBody");assertEquals(0,in.farPreparations);
            assertTrue(getLong(host,"bodyEqualityCompletedComparisons")>0);
            invoke(host,"prepareDistanceBody");assertEquals(512000,getLong(host,"bodyEqualityCompletedComparisons"));assertEquals(0,in.farPreparations);
            long slices=getLong(host,"bodyEqualitySlices");invoke(host,"prepareDistanceBody");assertEquals(slices,getLong(host,"bodyEqualitySlices"),"Waiting for pages is not another equality slice");
            in.visualReady=true;invoke(host,"prepareDistanceBody");assertEquals(1,in.farPreparations);
            assertNotNull(get(host,"candidateBodyJoined"));assertEquals(0,in.pairMessages);assertEquals(0,in.collisionPublications);
        }finally{INPUTS.remove();}
    }
    @Test void absentQualificationCaptureSourceOrExactPlanCannotStartBodyProof() throws Throwable {
        for(String missing:List.of("source","capture","request","plan"))try(var data=new DistanceBodyTestFixture()){
            data.stage(DistanceBodyTestFixture.PIN);var in=new Inputs(data);var host=host(in,false);
            if(missing.equals("source"))put(host,"sourceVerified",false);
            if(missing.equals("capture"))in.captureReady=false;
            if(missing.equals("request"))in.requestReady=false;
            if(missing.equals("plan"))in.planReady=false;
            invoke(host,"prepareDistanceBody");assertNull(get(host,"candidateBody"));assertEquals(0,in.farPreparations);assertEquals(0,getLong(host,"bodyEqualityComparedCells"));
        }finally{INPUTS.remove();}
    }
    @Test void completeProofStillNeedsFreshPublicationAndActualNativeJoin() throws Throwable {
        try(var data=new DistanceBodyTestFixture()){
            data.stage(DistanceBodyTestFixture.PIN);data.join();var in=new Inputs(data);var host=host(in,false);adoptCandidate(host,data);
            assertEquals(true,invoke(host,"validateCandidate"));
            put(host,"publication",data.publication+1);assertThrows(IllegalStateException.class,()->invoke(host,"validateCandidate"));
            put(host,"publication",data.publication);put(host,"candidateBodyJoined",null);assertThrows(IllegalStateException.class,()->invoke(host,"validateCandidate"));
            put(host,"candidateBodyJoined",data.joined);data.invalidateResources();assertThrows(IllegalStateException.class,()->invoke(host,"validateCandidate"));
        }finally{INPUTS.remove();}
    }
    @Test void editHistoryNeverRegainsCurrentReadinessEvenWithTheOldVisualStillAlive() throws Throwable {
        try(var data=new DistanceBodyTestFixture()){
            data.stage(DistanceBodyTestFixture.PIN);data.join();data.install();var old=data.binding;var joined=data.joined;
            var history=data.retainPresentation();data.editOne();assertTrue(history.isPresentable());
            var in=new Inputs(data);var host=host(in,false);put(host,"candidateBody",old);put(host,"candidateBodyJoined",joined);put(host,"publication",old.publication());
            assertThrows(IllegalStateException.class,()->invoke(host,"validateCandidate"));assertTrue(history.isPresentable());
            assertEquals(0,in.pairMessages);assertEquals(0,in.collisionPublications);history.close();
        }finally{INPUTS.remove();}
    }
    @Test void pairInstallationAdvancesActualNativeAndFarBeforeReadyAndCollisionWaitsForCommitted() throws Throwable {
        try(var data=new DistanceBodyTestFixture()){
            data.stage(DistanceBodyTestFixture.PIN);data.join();var in=new Inputs(data);var host=host(in,false);adoptCandidate(host,data);
            invoke(host,"installPair");assertEquals(1,in.pairMessages);assertEquals(0,in.collisionPublications);
            assertTrue((boolean)get(host,"visible"));assertSame(data.binding,get(host,"publishedBody"));assertSame(data.visual,get(host,"publishedVisual"));
            invoke(host,"committed",data.publication,data.guard.resourceGeneration(),DistanceBodyTestFixture.HASH);
            assertEquals(1,in.collisionPublications);assertEquals(125,in.collided.size());assertFalse((boolean)get(host,"pending"));
            for(var snapshot:data.binding.snapshots())assertSame(snapshot,in.collided.get(snapshot.position()));
            assertTrue(in.events.indexOf("far-commit")<in.events.indexOf("native-retain"));assertTrue(in.events.indexOf("native-retain")<in.events.indexOf("pair-ready"));assertTrue(in.events.indexOf("pair-ready")<in.events.indexOf("collision"));
        }finally{INPUTS.remove();}
    }
    @Test void failedFarCommitCannotExposeMixedNativeOrSendPairReady() throws Throwable {
        try(var data=new DistanceBodyTestFixture()){
            data.stage(DistanceBodyTestFixture.PIN);data.join();var in=new Inputs(data);in.farCommit=false;var host=host(in,false);adoptCandidate(host,data);
            assertThrows(IllegalStateException.class,()->invoke(host,"installPair"));assertFalse((boolean)get(host,"visible"));assertFalse((boolean)get(host,"pairSent"));
            assertEquals(1,in.fences);assertEquals(0,in.pairMessages);assertEquals(0,in.collisionPublications);assertNull(get(host,"publishedVisual"));
        }finally{INPUTS.remove();}
    }
    @Test void snapshotSwapImmediatelyAfterRetirementCannotReachCollisionAndBrokenControlDoes() throws Throwable {
        for(boolean broken:new boolean[]{false,true})try(var data=new DistanceBodyTestFixture()){
            data.stage(DistanceBodyTestFixture.PIN);data.join();var in=new Inputs(data);in.tamper=true;var host=host(in,broken);adoptCandidate(host,data);invoke(host,"installPair");
            if(broken){invoke(host,"committed",data.publication,data.guard.resourceGeneration(),DistanceBodyTestFixture.HASH);assertEquals(1,in.collisionPublications,"The deliberately removed post-retirement proof admits a wrong object map");}
            else{assertThrows(IllegalStateException.class,()->invoke(host,"committed",data.publication,data.guard.resourceGeneration(),DistanceBodyTestFixture.HASH));assertEquals(0,in.collisionPublications);}
        }finally{INPUTS.remove();}
    }
    @Test void failedBodyAliasCleanupStaysReachableUntilSuccessfulRetry() throws Throwable {
        try(var data=new DistanceBodyTestFixture()){
            data.stage(DistanceBodyTestFixture.PIN);data.begin();var in=new Inputs(data);var host=host(in,false);put(host,"candidateBody",data.binding);
            data.binding.failCleanupForTest(()->{throw new IllegalStateException("injected body alias cleanup failure");});
            assertThrows(IllegalStateException.class,()->invoke(host,"closeBodyAliases"));assertSame(data.owner,get(host,"bodyOwner"));assertSame(data.binding,get(host,"candidateBody"));assertEquals(false,invoke(host,"bodyAliasesReleased"));
            data.binding.failCleanupForTest(()->{});invoke(host,"closeBodyAliases");assertNull(get(host,"bodyOwner"));assertNull(get(host,"candidateBody"));assertEquals(true,invoke(host,"bodyAliasesReleased"));invoke(host,"closeBodyAliases");
        }finally{INPUTS.remove();}
    }
    @Test void refusedOrNoOpEditStillMintsFreshVisualRequestForSameCapture() throws Throwable {
        try(var data=new DistanceBodyTestFixture()){
            data.stage(DistanceBodyTestFixture.PIN);var in=new Inputs(data);var host=host(in,false);
            assertEquals(true,invoke(host,"requestVisual"));var first=in.requestedStamp;String key=(String)get(host,"visualRequestKey");
            assertEquals(true,invoke(host,"requestVisual"));assertEquals(1,in.requests);
            put(host,"publication",data.publication+1);assertEquals(true,invoke(host,"requestVisual"));assertEquals(2,in.requests);
            assertNotEquals(key,get(host,"visualRequestKey"));assertEquals(first.visualGeneration()+1,in.requestedStamp.visualGeneration());
            assertEquals(first.captureIdentity(),in.requestedStamp.captureIdentity());assertEquals(first.durableRevision(),in.requestedStamp.durableRevision());
        }finally{INPUTS.remove();}
    }

    @Test void livePaletteChecksRejectMappingChangeAndRemainStickyAfterAba() throws Throwable {
        try(var data=new DistanceBodyTestFixture()){
            data.stage(DistanceBodyTestFixture.PIN);var in=new Inputs(data);var host=host(in,false);
            invoke(host,"captureBodyPalette");assertEquals(true,invoke(host,"bodyPaletteCurrent"));
            var ids=(int[])get(host,"bodyPaletteIds");int original=ids[0];ids[0]=-1;
            assertEquals(false,invoke(host,"bodyPaletteCurrent"));ids[0]=original;
            assertEquals(false,invoke(host,"bodyPaletteCurrent"),"Restoring IDs cannot revive a retired registry lease");
            invoke(host,"closeBodyAliases");assertNull(get(host,"bodyPaletteStates"));assertNull(get(host,"bodyPaletteIds"));
        }finally{INPUTS.remove();}
    }

    @Test void capsuleCallbackCannotChangeTheRegistryAfterTheOnlyPaletteCheck() throws Throwable {
        try(var data=new DistanceBodyTestFixture()){
            data.stage(DistanceBodyTestFixture.PIN);var in=new Inputs(data);var host=host(in,false);invoke(host,"captureBodyPalette");
            put(host,"shaderNativeContract",uninitialized(SceneNativeShaderContract.Capture.class));
            put(host,"shaderCarrier",uninitialized(io.earthplatform.compat.dh.shader.InstalledShaderCarrier.class));
            put(host,"visualCapsule",new NativeVisualCapsuleCapture.Sealed(null,(NativeVisualCapsuleCapture.Lease)uninitialized(NativeVisualCapsuleCapture.Lease.class),null,null));
            assertEquals(true,invoke(host,"bodyResourcesCurrent"));
            in.mutatePaletteDuringGuard=true;assertEquals(false,invoke(host,"bodyResourcesCurrent"));
            assertEquals(false,invoke(host,"bodyPaletteCurrent"));
        }finally{INPUTS.remove();}
    }

    private static Object host(Inputs in,boolean broken) throws Throwable {
        INPUTS.set(in);var type=type(broken);var host=type.getConstructor().newInstance();var d=in.data;
        for(String field:List.of("pending","allowed","preparedSent","sourceVerified"))put(host,field,true);
        put(host,"epoch",1L);put(host,"publication",d.publication);put(host,"digest",DistanceBodyTestFixture.HASH);put(host,"visualRequestKey","");
        put(host,"replica",d.replica);put(host,"guard",d.guard);put(host,"world",d.world);put(host,"palette",d.palette);put(host,"bodyOwner",d.owner);
        put(host,"profile",RollingSessionDescriptor.alpine(d.world,d.palette,io.earthplatform.geography.earth.EarthAlpineDefinition.ANCHOR_X,Math.toIntExact(io.earthplatform.geography.earth.EarthAlpineDefinition.GROUND_Y),io.earthplatform.geography.earth.EarthAlpineDefinition.ANCHOR_Z));
        put(host,"proposedCertificate",d.replica.candidateReady().replacementCertificate());put(host,"publishedCertificate",d.replica.candidateReady().replacementCertificate());put(host,"proposedReplacement",d.replacement);
        put(host,"proposedVisual",BodyNativeCompletionFixture.publication(d.plan,d.stamp,d.live::get));
        put(host,"visualStream",uninitialized(NativeVisualStream.class));put(host,"observedNativeProjection",new Matrix4f());
        put(host,"publishedPairIdentity",new RollingPublishedPairIdentity());put(host,"distancePresentation",in.presentation);put(host,"presentation",in.presentation);
        put(host,"candidate",new LinkedHashMap<>());put(host,"published",new LinkedHashMap<>());put(host,"owned",new HashSet<>());put(host,"nativeReuses",new IdentityHashMap<>());
        put(host,"nativeReuseQueue",new ArrayDeque<>());put(host,"nativeRebuildTargets",new ArrayList<>());
        put(host,"meshWork",new RollingMeshWorkQueue<>(new RollingMeshWorkQueue.Driver<Object,Object>(){
            public Object begin(CubePos p){throw new AssertionError("No body models");}public RollingMeshWorkQueue.StepStats step(Object b,int max,long nanos){throw new AssertionError("No body callbacks");}
            public Object finish(Object b){throw new AssertionError("No body upload");}public void closeBuild(Object b){}public void closeMesh(Object b){}
        }));
        var center=new RollingFarPublication.Center(0,0);put(host,"desiredFarCenter",center);put(host,"preparedFarCenter",center);put(host,"preparedCoverageGeneration",1L);put(host,"preparedFar",uninitialized(DhPreparedLodView.CoverageTicket.class));
        var frontier=new RollingVisualFrontier(1);var r=d.replica.candidateReady();assertTrue(frontier.metadata(1,d.publication,d.guard.resourceGeneration(),r.candidateCenter(),r.candidateCenter(),r.generation(),System.nanoTime()));put(host,"visualFrontier",frontier);
        in.host=host;return host;
    }
    private static void adoptCandidate(Object host,DistanceBodyTestFixture d) throws Exception {put(host,"candidateBody",d.binding);put(host,"candidateBodyJoined",d.joined);put(host,"proposedVisual",d.visual);}
    private static synchronized Class<?> type(boolean broken) throws Exception {
        if(TYPES.containsKey(broken))return TYPES.get(broken);var source=read(SESSION);String name=GENERATED+(broken?"Broken":"Good");
        var result=new ClassNode();result.version=source.version;result.access=Opcodes.ACC_PUBLIC;result.name=name;result.superName="java/lang/Object";
        for(var field:source.fields)if((field.access&Opcodes.ACC_STATIC)==0)result.fields.add(new FieldNode(Opcodes.ACC_PUBLIC,field.name,field.desc,null,null));
        for(var original:source.methods)if(METHODS.contains(original.name))result.methods.add(instrument(original,name,broken));
        var ctor=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));ctor.instructions.add(new InsnNode(Opcodes.RETURN));result.methods.add(ctor);
        var writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);result.accept(writer);var type=MethodHandles.lookup().defineClass(writer.toByteArray());TYPES.put(broken,type);return type;
    }
    private static MethodNode instrument(MethodNode original,String generated,boolean broken){
        var copy=new MethodNode(Opcodes.ACC_PUBLIC,original.name,original.desc,null,null);original.accept(copy);copy.access=Opcodes.ACC_PUBLIC;copy.localVariables=null;
        for(var node:copy.instructions.toArray()){
            if(node instanceof FrameNode){copy.instructions.remove(node);continue;}
            if(node instanceof FieldInsnNode f&&f.owner.equals(SESSION)){f.owner=generated;continue;}
            if(node instanceof InvokeDynamicInsnNode d){d.desc=d.desc.replace(SESSION,generated);for(int i=0;i<d.bsmArgs.length;i++)if(d.bsmArgs[i] instanceof Handle h&&h.getOwner().equals(SESSION))d.bsmArgs[i]=new Handle(h.getTag(),generated,h.getName(),h.getDesc().replace(SESSION,generated),h.isInterface());continue;}
            if(!(node instanceof MethodInsnNode c))continue;
            if(c.owner.equals(SESSION)){
                if(METHODS.contains(c.name)&&!(c.name.equals("requestVisual")&&original.name.equals("prepareDistanceBody"))){c.owner=generated;continue;}
                String name=switch(c.name){case "distanceMode","valid","bodyResourcesCurrent"->"yes";case "candidateCameraReady"->"camera";case "prepareReplacementInput"->"captureReady";case "requestVisual"->"requestReady";case "prepareVisual"->"visualReady";case "prepareFarCoverage"->"prepareFar";case "preparationStatus"->"status";case "activePresentation"->"presentation";case "publishedFarCoverageReady"->"farReady";case "closeNativeReuses"->"noOp";case "verifyArenaNativeRevisions"->"forbiddenArena";case "verifySetNativeRevisions"->"forbiddenSet";case "closeExcept"->"forbiddenMeshes";case "send"->"send";case "report"->"report";case "invalidateOwned"->"invalidate";default->throw new AssertionError("Unreviewed host call "+original.name+":"+c.name);};
                stub(c,name,"(Ljava/lang/Object;"+c.desc.substring(1));continue;
            }
            if(c.owner.equals(ROOT)&&c.name.equals("finishDistanceProgress")){stub(c,"finishProgress",c.desc);continue;}
            if(c.owner.equals(SESSION+"$WorkTiming")&&c.name.equals("record")){stub(c,"timing","(Ljava/lang/Object;J)J");continue;}
            if(c.owner.equals(CORE+"RollingFarPublication")&&c.name.equals("center")){stub(c,"center","(L"+CORE+"RollingSessionDescriptor;Lio/earthplatform/core/CubePos;)L"+CORE+"RollingFarPublication$Center;");continue;}
            if(c.owner.equals(BASE+"scene/NativeVisualGpuPiece")&&c.name.equals("completion")){stub(c,"pieceReceipt","(L"+BASE+"scene/NativeVisualGpuPiece;)L"+BASE+"scene/NativeVisualGpuPiece$Completion;");continue;}
            if(c.owner.equals(BASE+"scene/NativeVisualStream")){
                switch(c.name){case "drawPlan"->stub(c,"plan","(Ljava/lang/Object;)L"+BASE+"visual/EarthDistanceSurfacePlan;");case "progress"->stub(c,"progress","(Ljava/lang/Object;)L"+BASE+"scene/NativeVisualStream$Progress;");case "retainPresentation"->stub(c,"retainNative","(Ljava/lang/Object;L"+BASE+"visual/NativeVisualPublication;)V");case "finishRequest"->stub(c,"drained","(Ljava/lang/Object;)Z");case "request"->stub(c,"request","(Ljava/lang/Object;L"+BASE+"visual/NativeVisualStamp;Lio/earthplatform/core/CubePos;L"+BASE+"enginebridge/EarthReplacementCapture;)V");default->throw new AssertionError(c.name);}continue;
            }
            if(c.owner.equals(BASE+"scene/NativeVisualStream$Progress")&&c.name.equals("stamp")){stub(c,"stamp","(Ljava/lang/Object;)L"+BASE+"visual/NativeVisualStamp;");continue;}
            if(c.owner.equals(BASE+"lod/DhPreparedLodView")){
                switch(c.name){case "isPrepared"->stub(c,"prepared","(Ljava/lang/Object;L"+BASE+"lod/DhPreparedLodView$CoverageTicket;)Z");case "commitPrepared"->stub(c,"commitFar","(Ljava/lang/Object;L"+BASE+"lod/DhPreparedLodView$CoverageTicket;)Z");case "coverageReady"->stub(c,"coverageReady","(Ljava/lang/Object;JJJL"+BASE+"lod/DhLodLayout;)Z");case "replacementReady"->stub(c,"replacementReady","(Ljava/lang/Object;Ljava/lang/String;)Z");case "diagnostics"->stub(c,"diagnostics","(Ljava/lang/Object;)Lcom/google/gson/JsonObject;");case "physicalSummaryWorkPending"->stub(c,"notDrained","(Ljava/lang/Object;)Z");default->{}}continue;
            }
            if(c.owner.equals(CORE+"RollingReplacementAuthority")&&c.name.equals("commit")){stub(c,"replacementCommit","(Ljava/lang/Object;Ljava/lang/String;)V");continue;}
            if(c.owner.equals(CORE+"CoreCollisionBinding")&&c.name.equals("publish")){stub(c,"publishCollision","(Ljava/lang/Object;Lio/earthplatform/core/WorldIdentity;JL"+BASE+"network/EnginePaletteContext;Ljava/util/Map;)V");continue;}
            if(c.owner.equals(CORE+"RollingGateReplica")&&c.name.equals("commitStage")){copy.instructions.insert(c,new MethodInsnNode(Opcodes.INVOKESTATIC,SELF,"afterRetirement","()V",false));continue;}
            if(broken&&c.owner.equals(BINDING)&&c.name.equals("requireCommittedSnapshots")&&c.desc.equals("(Ljava/util/Map;)V")){stub(c,"omitCollisionProof","(Ljava/lang/Object;Ljava/util/Map;)V");continue;}
            // requestVisual-only resource access: immutable selected/source IDs are modeled, while
            // the real request-key expression, native stamp constructor and draining branches execute.
            if(c.owner.equals(BASE+"scene/SceneNativeShaderContract$Capture")&&c.name.equals("isCurrent")){stub(c,"yes","(Ljava/lang/Object;)Z");continue;}
            if(c.owner.equals("io/earthplatform/compat/dh/shader/InstalledShaderCarrier")&&c.name.equals("pipelineCurrent")){stub(c,"yes","(Ljava/lang/Object;)Z");continue;}
            if(c.owner.equals(BASE+"scene/NativeVisualCapsuleCapture$Lease")&&c.name.equals("requireCurrent")){stub(c,"resourceCallback","(Ljava/lang/Object;)V");continue;}
            if(c.owner.equals("io/earthplatform/compat/dh/shader/InstalledShaderCarrier")&&c.name.equals("admittedPipeline")){stub(c,"pipeline","(Ljava/lang/Object;)Lio/earthplatform/compat/dh/InstalledIrisRuntime$Lease;");continue;}
            if(c.owner.equals("io/earthplatform/compat/dh/InstalledIrisRuntime$Lease")){stub(c,c.name.equals("serverRenderDistanceLimit")?"zero":"two","(Ljava/lang/Object;)I");continue;}
            if(c.owner.equals(CORE+"RollingEarthPreparation$Prepared")&&c.name.equals("alpine")){stub(c,"alpine","(Ljava/lang/Object;)L"+CORE+"RollingAlpinePreparation$SourceEvidence;");continue;}
            if(c.owner.equals(CORE+"RollingAlpinePreparation$SourceEvidence")&&c.desc.equals("()Ljava/lang/String;")){stub(c,"hash","(Ljava/lang/Object;)Ljava/lang/String;");continue;}
            if(c.owner.equals(BASE+"scene/NativeVisualCapsuleCapture$Sealed")&&c.name.equals("capsule")){stub(c,"capsule","(Ljava/lang/Object;)L"+BASE+"scene/NativeVisualCapsule;");continue;}
            if(c.owner.equals(BASE+"scene/NativeVisualCapsule")&&c.name.equals("identity")){stub(c,"capsuleIdentity","(Ljava/lang/Object;)L"+BASE+"scene/NativeVisualCapsule$Identity;");continue;}
            if(c.owner.equals(BASE+"scene/NativeVisualCapsule$Identity")&&c.name.equals("bytesDigest")){stub(c,"hash","(Ljava/lang/Object;)Ljava/lang/String;");}
        }return copy;
    }
    private static void stub(MethodInsnNode c,String name,String desc){c.setOpcode(Opcodes.INVOKESTATIC);c.owner=SELF;c.name=name;c.desc=desc;c.itf=false;}
    private static ClassNode read(String name)throws Exception{try(var bytes=RollingDistanceBodyHostTest.class.getClassLoader().getResourceAsStream(name+".class")){assertNotNull(bytes);var n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}}
    private static Object uninitialized(Class<?> type)throws Exception{var u=Class.forName("sun.misc.Unsafe");var f=u.getDeclaredField("theUnsafe");f.setAccessible(true);return u.getMethod("allocateInstance",Class.class).invoke(f.get(null),type);}
    private static void put(Object host,String field,Object value)throws Exception{host.getClass().getField(field).set(host,value);}
    private static Object get(Object host,String field)throws Exception{return host.getClass().getField(field).get(host);}
    private static long getLong(Object host,String field)throws Exception{return ((Number)get(host,field)).longValue();}
    private static Object invoke(Object host,String name,Object...args)throws Throwable{var method=Arrays.stream(host.getClass().getMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow();try{return method.invoke(host,args);}catch(InvocationTargetException e){throw e.getCause();}}
    public static void resourceCallback(Object ignored){var in=INPUTS.get();if(in.mutatePaletteDuringGuard)try{((int[])get(in.host,"bodyPaletteIds"))[0]=-1;}catch(Exception e){throw new AssertionError(e);}}
    public static boolean yes(Object ignored){return true;}
    public static boolean camera(Object ignored,CubePos target){return INPUTS.get().cameraReady;}
    public static boolean captureReady(Object ignored){return INPUTS.get().captureReady;}
    public static boolean requestReady(Object ignored){return INPUTS.get().requestReady;}
    public static boolean visualReady(Object ignored){return INPUTS.get().visualReady;}
    public static boolean farReady(Object ignored){return INPUTS.get().farReady;}
    public static void prepareFar(Object ignored){INPUTS.get().farPreparations++;}
    public static String status(Object ignored,String value){return value;}
    public static RollingEditPresentation<?,?> presentation(Object ignored){return INPUTS.get().presentation;}
    public static void noOp(Object ignored){}
    public static void forbiddenArena(Object ignored,ArenaCertificate c){throw new AssertionError("Distance entered arena branch");}
    public static void forbiddenSet(Object ignored,ReplacementSetCertificate c){throw new AssertionError("Distance entered indexed branch");}
    public static void forbiddenMeshes(Object ignored,Set<?> meshes){throw new AssertionError("Distance entered legacy mesh publication");}
    public static void report(Object ignored,String stage){}
    public static void finishProgress(long epoch){}
    public static void send(Object ignored,RollingGateControlPayload p){if(p.kind()==RollingGateControlPayload.PAIR_READY){INPUTS.get().pairMessages++;INPUTS.get().events.add("pair-ready");}}
    public static void invalidate(Object host){try{put(host,"visible",false);put(host,"pairSent",false);((RollingPublishedPairIdentity)get(host,"publishedPairIdentity")).revoke();INPUTS.get().data.owner.revoke();INPUTS.get().fences++;}catch(Exception e){throw new AssertionError(e);}}
    public static long timing(Object ignored,long start){return 0;}
    public static RollingFarPublication.Center center(RollingSessionDescriptor profile,CubePos p){return new RollingFarPublication.Center(0,0);}
    public static EarthDistanceSurfacePlan plan(Object ignored){return INPUTS.get().planReady?INPUTS.get().data.plan:null;}
    public static NativeVisualStream.Progress progress(Object ignored){return null;}
    public static NativeVisualStamp stamp(Object ignored){return INPUTS.get().data.stamp;}
    @SuppressWarnings("unchecked") public static NativeVisualGpuPiece.Completion pieceReceipt(NativeVisualGpuPiece piece){
        try{var field=NativeVisualPublication.class.getDeclaredField("receipts");field.setAccessible(true);for(var receipt:(List<NativeVisualGpuPiece.Completion>)field.get(INPUTS.get().data.visual))if(receipt.physicalOwner()==piece)return receipt;throw new AssertionError("Missing modeled GPU completion owner");}catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    public static void retainNative(Object ignored,NativeVisualPublication visual){visual.requireCurrent();INPUTS.get().events.add("native-retain");}
    public static boolean drained(Object ignored){return INPUTS.get().drained;}
    public static boolean notDrained(Object ignored){return !INPUTS.get().drained;}
    public static void request(Object ignored,NativeVisualStamp stamp,CubePos center,EarthReplacementCapture capture){var in=INPUTS.get();in.requests++;in.requestedStamp=stamp;assertSame(in.data.capture,capture);}
    public static boolean prepared(Object ignored,DhPreparedLodView.CoverageTicket ticket){return INPUTS.get().farReady;}
    public static boolean commitFar(Object ignored,DhPreparedLodView.CoverageTicket ticket){INPUTS.get().events.add("far-commit");return INPUTS.get().farCommit;}
    public static boolean coverageReady(Object ignored,long generation,long x,long z,DhLodLayout layout){return INPUTS.get().farReady;}
    public static boolean replacementReady(Object ignored,String identity){return INPUTS.get().farReady;}
    public static JsonObject diagnostics(Object ignored){var j=new JsonObject();j.addProperty("pageTileOwners",1);j.addProperty("preparedVertexBytes",16);return j;}
    public static void replacementCommit(Object ignored,String identity){}
    public static void publishCollision(Object ignored,WorldIdentity world,long epoch,EnginePaletteContext palette,Map<CubePos,EngineCubeSnapshot> snapshots){var in=INPUTS.get();in.collided=snapshots;in.collisionPublications++;in.events.add("collision");}
    @SuppressWarnings("unchecked") public static void afterRetirement(){var in=INPUTS.get();if(!in.tamper)return;try{var f=RollingGateReplica.class.getDeclaredField("cubes");f.setAccessible(true);var cubes=(Map<CubePos,SceneCube>)f.get(in.data.replica);var old=cubes.get(DistanceBodyTestFixture.PIN).snapshot();var copy=in.data.snapshot(old.position(),old.leaseGeneration(),old.revision(),-1,-1);cubes.put(old.position(),new SceneCube(copy,in.data.palette));}catch(Exception e){throw new AssertionError(e);}}
    public static void omitCollisionProof(Object ignored,Map<?,?> wrongMap){}
    public static io.earthplatform.compat.dh.InstalledIrisRuntime.Lease pipeline(Object ignored){return null;}
    public static int two(Object ignored){return 2;}
    public static int zero(Object ignored){return 0;}
    public static RollingAlpinePreparation.SourceEvidence alpine(Object ignored){return null;}
    public static String hash(Object ignored){return DistanceBodyTestFixture.HASH;}
    public static NativeVisualCapsule capsule(Object ignored){return null;}
    public static NativeVisualCapsule.Identity capsuleIdentity(Object ignored){return null;}
}
