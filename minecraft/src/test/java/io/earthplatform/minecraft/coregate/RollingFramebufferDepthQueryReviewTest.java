package io.earthplatform.minecraft.coregate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/** Independent pure callback and actual classfile checks; no OpenGL context or readback claim. */
class RollingFramebufferDepthQueryReviewTest {
    private static final String OWNER="io/earthplatform/minecraft/coregate/RollingDepthComposition";
    private static final String DRIVER=OWNER+"$GlDriver";
    private record Query(int attachment,int parameter) {}
    private static final class Queries implements IntBinaryOperator {
        final List<Query> calls=new ArrayList<>();final int expectedAttachment,type,bits;
        Queries(int expectedAttachment,int type,int bits){this.expectedAttachment=expectedAttachment;this.type=type;this.bits=bits;}
        public int applyAsInt(int attachment,int parameter){
            assertEquals(expectedAttachment,attachment);calls.add(new Query(attachment,parameter));
            if(calls.size()==1){assertEquals(GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE,parameter);return type;}
            assertEquals(2,calls.size(),"No unbounded or repeated query");assertNotEquals(GL_NONE,type,"Absent attachment must never receive a size query");
            assertEquals(GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE,parameter);return bits;
        }
    }
    private static void check(int binding,int attachment,int type,int bits){
        var queries=new Queries(attachment,type,bits);assertEquals(bits,RollingDepthComposition.framebufferDepthBits(binding,queries));
        assertEquals(List.of(new Query(attachment,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE),new Query(attachment,GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE)),queries.calls);
    }

    @Test void defaultFramebufferQueriesDepthBufferRatherThanFboAttachment(){
        check(0,GL_DEPTH,GL_FRAMEBUFFER_DEFAULT,24);check(0,GL_DEPTH,GL_FRAMEBUFFER_DEFAULT,32);
    }
    @Test void namedTextureAndRenderbufferDepthSizesAreReturnedWithoutAssumingTwentyFourBits(){
        for(int binding:new int[]{1,17,Integer.MAX_VALUE})for(int type:new int[]{GL_TEXTURE,GL_RENDERBUFFER})for(int bits:new int[]{16,24,32})
            check(binding,GL_DEPTH_ATTACHMENT,type,bits);
    }
    @Test void unsignedFramebufferNamesRepresentedByNegativeIntsRemainNamedFbos(){
        for(int binding:new int[]{-1,-17,Integer.MIN_VALUE})check(binding,GL_DEPTH_ATTACHMENT,GL_TEXTURE,32);
    }
    @Test void absentDefaultAndNamedDepthBuffersStopAfterObjectTypeAndReportZero(){
        for(int binding:new int[]{0,1,17,Integer.MAX_VALUE,-1,Integer.MIN_VALUE}){
            int attachment=binding==0?GL_DEPTH:GL_DEPTH_ATTACHMENT;var queries=new Queries(attachment,GL_NONE,99);
            assertEquals(0,RollingDepthComposition.framebufferDepthBits(binding,queries));
            assertEquals(List.of(new Query(attachment,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)),queries.calls);
        }
    }
    @Test void presentAttachmentWithZeroDepthStorageReturnsActualZero(){
        check(0,GL_DEPTH,GL_FRAMEBUFFER_DEFAULT,0);check(37,GL_DEPTH_ATTACHMENT,GL_TEXTURE,0);
    }
    @Test void nullAndQueryFailuresAreNotConvertedToInventedDepthPrecision(){
        assertThrows(NullPointerException.class,()->RollingDepthComposition.framebufferDepthBits(0,null));
        for(int failureAt:new int[]{1,2}){
            var failure=new IllegalStateException("query "+failureAt);int[] calls={0};
            assertSame(failure,assertThrows(IllegalStateException.class,()->RollingDepthComposition.framebufferDepthBits(-1,(attachment,parameter)->{
                assertEquals(GL_DEPTH_ATTACHMENT,attachment);if(++calls[0]==failureAt)throw failure;return GL_TEXTURE;
            })));
            assertEquals(failureAt,calls[0]);
        }
    }
    @Test void actualGlCapturePassesDrawBindingAndTheCallbackUsesDrawFramebufferAttachmentQuery()throws IOException{
        var methods=read(DRIVER);var capture=methods.get("capture()L"+OWNER+"$State;");assertNotNull(capture);
        int get=capture.indexCall("glGetInteger","(I)I");assertEquals(GL_DRAW_FRAMEBUFFER_BINDING,capture.code.get(get-1));
        int helper=capture.indexCall("framebufferDepthBits","(ILjava/util/function/IntBinaryOperator;)I");assertTrue(get<helper);
        assertEquals(1,capture.callbacks.size());var callback=capture.callbacks.getFirst();assertEquals(DRIVER,callback.getOwner());
        var lambda=methods.get(callback.getName()+callback.getDesc());assertNotNull(lambda);assertEquals("(II)I",callback.getDesc());
        int query=lambda.indexCall("glGetFramebufferAttachmentParameteri","(III)I");
        assertEquals(GL_DRAW_FRAMEBUFFER,lambda.code.get(query-3));
        assertEquals(new Local(Opcodes.ILOAD,0),lambda.code.get(query-2));assertEquals(new Local(Opcodes.ILOAD,1),lambda.code.get(query-1));
        var call=(Call)lambda.code.get(query);assertEquals("org/lwjgl/opengl/GL30",call.owner());
        assertEquals(1,lambda.calls().size());
    }
    @Test void legacyDepthBitsEnumIsAbsentAndCaptureRemainsReadOnlyWhileRestoreRechecksSameAttachment()throws IOException{
        for(String owner:List.of(OWNER,DRIVER))for(var entry:read(owner).entrySet())
            assertFalse(entry.getValue().code.contains(GL_DEPTH_BITS),"Forbidden removed GL_DEPTH_BITS in "+owner+"."+entry.getKey());
        var methods=read(DRIVER);var capture=methods.get("capture()L"+OWNER+"$State;");
        for(var call:capture.calls())if(call.owner().startsWith("org/lwjgl/opengl/"))
            assertTrue(call.name().startsWith("glGet")||call.name().equals("glIsEnabled"),"State capture must not mutate GL: "+call);
        var restore=methods.get("restore(L"+OWNER+"$State;)V");assertNotNull(restore);
        assertEquals(1,restore.calls().stream().filter(c->c.owner().equals(DRIVER)&&c.name().equals("capture")).count());
        assertTrue(restore.indexCall("capture","()L"+OWNER+"$State;")<restore.indexCall("equals","(Ljava/lang/Object;)Z"));
    }
    private record Call(String owner,String name,String descriptor){}
    private record Local(int opcode,int variable){}
    private static final class Code {
        final List<Object> code=new ArrayList<>();final List<Handle> callbacks=new ArrayList<>();
        List<Call> calls(){return code.stream().filter(Call.class::isInstance).map(Call.class::cast).toList();}
        int indexCall(String name,String descriptor){for(int i=0;i<code.size();i++)if(code.get(i) instanceof Call c&&c.name().equals(name)&&c.descriptor().equals(descriptor))return i;throw new AssertionError("Missing "+name+descriptor);}
    }
    private static Map<String,Code> read(String owner)throws IOException{
        var methods=new LinkedHashMap<String,Code>();
        try(var stream=RollingFramebufferDepthQueryReviewTest.class.getClassLoader().getResourceAsStream(owner+".class")){
            assertNotNull(stream,owner);new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9){
                public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                    var code=new Code();methods.put(name+descriptor,code);return new MethodVisitor(Opcodes.ASM9){
                        public void visitIntInsn(int opcode,int operand){code.code.add(operand);}
                        public void visitLdcInsn(Object value){code.code.add(value);}
                        public void visitVarInsn(int opcode,int variable){code.code.add(new Local(opcode,variable));}
                        public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean iface){code.code.add(new Call(owner,name,descriptor));}
                        public void visitInvokeDynamicInsn(String name,String descriptor,Handle bootstrap,Object... args){
                            if(bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory"))for(var arg:args)if(arg instanceof Handle h)code.callbacks.add(h);
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        return methods;
    }
}
