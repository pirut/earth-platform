package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.shaders.Uniform;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real pinned Uniform storage plus draw-path wiring; no GL context or upload is invoked. */
class SceneNativeDepthUniformTest {
    @Test void chunkOffsetUsesTheRealFloatVectorBuffer(){
        try(var uniform=new Uniform("ChunkOffset",Uniform.UT_FLOAT3,3,null)){
            assertNull(uniform.getIntBuffer());
            uniform.set(1.25f,-19.5f,4096.0f);
            SceneNativeDepthFixture.clearChunkOffset(uniform);
            assertEquals(0.0f,uniform.getFloatBuffer().get(0));
            assertEquals(0.0f,uniform.getFloatBuffer().get(1));
            assertEquals(0.0f,uniform.getFloatBuffer().get(2));
        }
    }
    @Test void absentOptionalChunkOffsetIsAllowed(){assertDoesNotThrow(()->SceneNativeDepthFixture.clearChunkOffset(null));}
    @Test void actualDrawCallsTypedHelperAndHelperCannotCallIntegerOverload()throws IOException{
        List<String> calls=new ArrayList<>();
        try(var input=SceneNativeDepthFixture.class.getResourceAsStream("SceneNativeDepthFixture.class")){
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){
                    return new MethodVisitor(Opcodes.ASM9){
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf){
                            calls.add(name+":"+owner+"."+method+descriptor);
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        assertTrue(calls.contains("draw:io/earthplatform/minecraft/scene/SceneNativeDepthFixture.clearChunkOffset(Lcom/mojang/blaze3d/shaders/Uniform;)V"));
        assertTrue(calls.contains("clearChunkOffset:com/mojang/blaze3d/shaders/Uniform.set(FFF)V"));
        assertFalse(calls.stream().anyMatch(c->c.contains("com/mojang/blaze3d/shaders/Uniform.set(III)V")));
    }
    @Test void allSnapshotRestoreSetterOverloadsRemainExplicitlyTyped()throws IOException{
        var calls=new ArrayList<String>();
        try(var input=SceneNativeDepthFixture.class.getResourceAsStream("SceneNativeDepthFixture$ShaderUniforms.class")){
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){
                    return new MethodVisitor(Opcodes.ASM9){
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf){
                            if(owner.equals("com/mojang/blaze3d/shaders/Uniform")&&(method.equals("set")||method.equals("setSafe")))calls.add(name+":"+method+descriptor);
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        assertEquals(List.of("restore:set([F)V","restore:setSafe(IIII)V"),calls);
    }
    @Test void everySupportedIntegerRestoreShapeUsesRealIntegerStorage(){
        for(int type=Uniform.UT_INT1;type<=Uniform.UT_INT4;type++)try(var u=new Uniform("integer",type,type+1,null)){
            assertNull(u.getFloatBuffer());
            u.setSafe(3,5,7,11);
            for(int i=0;i<=type;i++)assertEquals(new int[]{3,5,7,11}[i],u.getIntBuffer().get(i));
        }
    }
    @Test void floatAndMatrixArrayRestoreShapesUseRealFloatStorage(){
        for(int type=Uniform.UT_FLOAT1;type<=Uniform.UT_MAT4;type++){
            int count=type<Uniform.UT_MAT2?type-Uniform.UT_FLOAT1+1:(type-Uniform.UT_MAT2+2)*(type-Uniform.UT_MAT2+2);
            try(var u=new Uniform("floating",type,count,null)){
                assertNull(u.getIntBuffer());float[] values=new float[count];for(int i=0;i<count;i++)values[i]=i+.25f;
                u.set(values);for(int i=0;i<count;i++)assertEquals(values[i],u.getFloatBuffer().get(i));
            }
        }
    }

}
