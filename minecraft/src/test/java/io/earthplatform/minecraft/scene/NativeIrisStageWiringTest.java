package io.earthplatform.minecraft.scene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.InjectionPoint;
import org.spongepowered.asm.mixin.injection.points.BeforeInvoke;
import static org.junit.jupiter.api.Assertions.*;

/** Production bytecode checks only. These do not apply mixins or establish GL restoration. */
class NativeIrisStageWiringTest {
    record Call(String method,String owner,String name){}
    record Code(List<Call> calls,List<String> literals,List<String> annotations){}
    private static Code read(String suffix)throws IOException{
        var calls=new ArrayList<Call>();var literals=new ArrayList<String>();var annotations=new ArrayList<String>();
        String name="io/earthplatform/minecraft/"+suffix;
        try(var in=getClassLoader().getResourceAsStream(name+".class")){
            assertNotNull(in,name);new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public AnnotationVisitor visitAnnotation(String descriptor,boolean visible){annotations.add(descriptor);return annotation(annotations);}
                @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){return new MethodVisitor(Opcodes.ASM9){
                    @Override public void visitMethodInsn(int op,String owner,String method,String desc,boolean iface){calls.add(new Call(n,owner,method));}
                    @Override public void visitLdcInsn(Object v){if(v instanceof String text)literals.add(text);}
                    @Override public AnnotationVisitor visitAnnotation(String desc,boolean visible){annotations.add(desc);return annotation(annotations);}
                };}
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }return new Code(calls,literals,annotations);
    }
    private static ClassLoader getClassLoader(){return NativeIrisStageWiringTest.class.getClassLoader();}
    private static AnnotationVisitor annotation(List<String> values){return new AnnotationVisitor(Opcodes.ASM9){
        @Override public void visit(String name,Object value){values.add(name+"="+value);}
        @Override public AnnotationVisitor visitArray(String name){values.add(name);return annotation(values);}
        @Override public AnnotationVisitor visitAnnotation(String name,String descriptor){values.add(descriptor);return annotation(values);}
    };}
    private static boolean calls(Code code,String name){return code.calls.stream().anyMatch(c->c.name.equals(name));}
    @Test void hookTargetsSodiumPostManagedLayerBoundaryRatherThanAnAfterLevelEvent()throws Exception{
        var hook=read("mixin/NativeIrisTerrainLayerMixin");
        assertTrue(hook.annotations.stream().anyMatch(v->v.contains("renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDD")));
        assertTrue(hook.annotations.stream().anyMatch(v->v.contains("PlatformLevelRenderHooks;getInstance()")));
        assertTrue(hook.annotations.contains("require=1"));assertTrue(calls(hook,"dispatch"));
        assertFalse(hook.literals.stream().anyMatch(v->v.contains("AFTER_LEVEL")));
    }
    @Test void dispatchUsesExactOwnedCarrierAndActualShadowStateWithTransientExpiration()throws Exception{
        var code=read("scene/NativeIrisShaderStages");
        assertTrue(calls(code,"owns"));assertTrue(calls(code,"nativeLayer"));assertTrue(calls(code,"areShadowsCurrentlyBeingRendered"));
        assertTrue(calls(code,"hasRetainedCleanup"));assertTrue(calls(code,"getShaderMap"));assertTrue(calls(code,"getLightTexture"));
        assertFalse(calls(code,"getWhitePixel"));assertFalse(calls(code,"setProjectionMatrix"));
        var context=read("scene/NativeIrisShaderStages$Context");assertTrue(calls(context,"fromTerrainRenderType"));assertTrue(calls(context,"isFinite"));
    }
    @Test void materialMutationRevokesBeforeAllSevenSettersWithoutConsumingReloadRequired()throws Exception{
        var code=read("mixin/NativeIrisMaterialLifecycleMixin");
        for(String setter:List.of("setBlockStateIds","setBlockTypeIds","setAmbientOcclusionLevel","setDisableDirectionalShading","setUseSeparateAo","setVertexFormat","setVoxelizeLightBlocks"))
            assertTrue(code.annotations.stream().anyMatch(v->v.endsWith("="+setter)),setter);
        assertTrue(code.annotations.contains("value=HEAD"));assertTrue(code.annotations.contains("require=7"));
        assertTrue(calls(code,"invalidateLifecycle"));assertTrue(calls(code,"invalidateAdmitted"));assertFalse(calls(code,"clearReloadRequired"));
    }
    @Test void stateSnapshotUsesRealTouchedBindingsAndPreservesSeparateCacheRepresentation()throws Exception{
        var code=read("scene/NativeIrisState");
        for(String call:List.of("getMaxTextureUnits","earthplatform$bindings","earthplatform$unit","getActiveImages","earthplatform$binding","glGetIntegeri","glGetIntegeri_v"))assertTrue(calls(code,call),call);
        assertTrue(code.literals.stream().anyMatch(v->v.contains("active Iris state owner")));
    }
    @Test void restorationCoversIrisBindingsAndPendingOwnerRatherThanOnlyVanillaState()throws Exception{
        var code=read("scene/NativeIrisState");
        for(String call:List.of("_glBindFramebuffer","viewport","bindSamplerToUnit","bindImageTexture","glBindTexture","glBlendFuncSeparatei","glColorMaski","earthplatform$isMainBound","earthplatform$normalTexture","earthplatform$specularTexture","setCurrentAlphaTest","earthplatform$binding","finish"))assertTrue(calls(code,call),call);
        assertTrue(code.calls.stream().anyMatch(c->c.owner.endsWith("NativeShaderCleanupOwner")&&c.name.equals("capture")));
    }
    @Test void setupDoesNotReplaceFboDepthRangeFogOrLightmapWithDiagnostics()throws Exception{
        var code=read("scene/NativeIrisShaderStages$1");
        var setup=code.calls.stream().filter(c->c.method.equals("setup")).toList();
        for(String forbidden:List.of("_glBindFramebuffer","glClear","glDepthRange","setShaderFogStart","setShaderFogEnd"))assertFalse(setup.stream().anyMatch(c->c.name.equals(forbidden)),forbidden);
        assertTrue(setup.stream().anyMatch(c->c.name.equals("setShaderTexture")));assertTrue(setup.stream().anyMatch(c->c.name.equals("disableCull")));
    }
    @Test void retainedOuterSnapshotCoversBaseDerivedAndDistinctGpuCpuUniformState()throws Exception{
        var code=read("scene/NativeIrisState");
        for(String name:List.of("ModelViewMat","ProjMat","ChunkOffset","FogShape","ModelViewMatInverse","ProjMatInverse","NormalMat"))assertTrue(code.literals.contains(name),name);
        var uniform=read("scene/NativeIrisState$UniformValue");
        for(String method:List.of("glGetUniformfv","glGetUniformiv","glUniformMatrix4fv","glUniform1iv","earthplatform$dirty","getFloatBuffer","getIntBuffer"))assertTrue(calls(uniform,method),method);
    }
    @Test void boundedGlValidationAndExactSamplerMapArePartOfTheRetainedOwner()throws Exception{
        var code=read("scene/NativeIrisState");assertTrue(calls(code,"earthplatform$samplerMap"));assertTrue(calls(code,"check"));
        assertTrue(code.literals.stream().anyMatch(v->v.startsWith("Pre-existing GL errors before native state capture")));
        assertTrue(code.literals.stream().anyMatch(v->v.startsWith("Pre-existing GL errors at native restoration entry")));
        assertTrue(code.literals.stream().anyMatch(v->v.contains("Native restoration GL errors")));
        var samplers=read("scene/NativeIrisState$SamplerValues");assertTrue(calls(samplers,"containsKey"));assertTrue(calls(samplers,"remove"));assertFalse(calls(samplers,"copyOf"));
    }
    @Test void actualPinnedInvokePriorityRuleRejectsLowerAndEqualButAdmits1001()throws Exception{
        // Execute the real library method, not a reimplementation of its comparison.
        InjectionPoint point=new InjectionPoint(){
            @Override public boolean find(String descriptor,InsnList instructions,java.util.Collection<AbstractInsnNode> nodes){return false;}
        };
        assertSame(InjectionPoint.class,BeforeInvoke.class.getMethod("checkPriority",int.class,int.class).getDeclaringClass());
        assertFalse(point.checkPriority(1000,900));assertFalse(point.checkPriority(1000,1000));
        assertTrue(point.checkPriority(1000,1001));assertFalse(point.checkPriority(1001,1001));
        var hook=read("mixin/NativeIrisTerrainLayerMixin");assertTrue(hook.annotations.contains("priority=1001"));
    }
    private static ClassNode upstream(String name)throws Exception{
        try(var in=getClassLoader().getResourceAsStream(name+".class")){
            assertNotNull(in,name);var node=new ClassNode();new ClassReader(in).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return node;
        }
    }
    private static MethodNode method(ClassNode type,String name){return type.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static int callIndex(MethodNode method,String name){
        for(int i=0;i<method.instructions.size();i++)if(method.instructions.get(i) instanceof MethodInsnNode call&&call.name.equals(name))return i;
        throw new AssertionError("Missing "+method.name+" -> "+name);
    }
    @Test void pinnedApplicatorCompletesAllMainMergesBeforePreparingAnyInjector()throws Exception{
        String base="org/spongepowered/asm/mixin/transformer/MixinApplicatorStandard";
        var phases=method(upstream(base+"$ApplicatorPass"),"$values");var phaseNames=new ArrayList<String>();
        for(var instruction:phases.instructions)if(instruction instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETSTATIC&&field.owner.equals(base+"$ApplicatorPass"))phaseNames.add(field.name);
        assertEquals(List.of("MAIN","INJECT_PREPARE","ACCESSOR","INJECT_PREINJECT","INJECT_APPLY"),phaseNames);
        var applicator=upstream(base);var dispatch=method(applicator,"applyMixin");
        TableSwitchInsnNode cases=null;for(var instruction:dispatch.instructions)if(instruction instanceof TableSwitchInsnNode table){cases=table;break;}
        assertNotNull(cases);assertEquals(0,cases.min);assertEquals(4,cases.max);
        int main=dispatch.instructions.indexOf(cases.labels.get(0));int prepare=dispatch.instructions.indexOf(cases.labels.get(1));int accessors=dispatch.instructions.indexOf(cases.labels.get(2));
        assertTrue(main<callIndex(dispatch,"applyMethods")&&callIndex(dispatch,"applyMethods")<prepare);
        assertTrue(prepare<callIndex(dispatch,"prepareInjections")&&callIndex(dispatch,"prepareInjections")<accessors);
        // Verify the bytecode loop nesting: the phase loop encloses the complete context
        // iterator loop which calls applyMixin. This is why early-sorting1001 still sees
        // Sodium's1000 overwrite when injection preparation begins.
        var apply=method(applicator,"apply");int invocation=callIndex(apply,"applyMixin");
        record Loop(int start,int end){}
        var enclosing=new ArrayList<Loop>();
        for(int i=0;i<apply.instructions.size();i++)if(apply.instructions.get(i) instanceof JumpInsnNode jump&&jump.getOpcode()==Opcodes.GOTO){
            int start=apply.instructions.indexOf(jump.label);if(start<invocation&&invocation<i)enclosing.add(new Loop(start,i));
        }
        assertTrue(enclosing.size()>=3,"Expected nested phase, injector-order, and complete mixin-context loops");
        Loop contextLoop=enclosing.stream().min(java.util.Comparator.comparingInt(v->v.end()-v.start())).orElseThrow();
        Loop phaseLoop=enclosing.stream().filter(v->{for(int i=v.start();i<v.end();i++)if(apply.instructions.get(i) instanceof IincInsnNode)return true;return false;}).findFirst().orElseThrow();
        assertTrue(callIndex(apply,"values")<phaseLoop.start());
        assertTrue(phaseLoop.start()<contextLoop.start()&&contextLoop.end()<phaseLoop.end());
        boolean visitsContext=false;
        for(int i=contextLoop.start();i<invocation;i++)if(apply.instructions.get(i) instanceof TypeInsnNode type&&type.getOpcode()==Opcodes.CHECKCAST&&type.desc.endsWith("/MixinTargetContext"))visitsContext=true;
        assertTrue(visitsContext,"applyMixin must be inside the complete mixin-context iterator");
    }
}
