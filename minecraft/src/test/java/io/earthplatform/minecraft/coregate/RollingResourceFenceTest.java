package io.earthplatform.minecraft.coregate;

import java.io.IOException;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pinned controller bytecode regression: the walking fence must not turn its player spectator. */
class RollingResourceFenceTest {
    @Test void resourceControlClosesActorWithoutSwitchingWalkingPlayerOutOfMotionFence() throws IOException {
        var stopArguments=new ArrayList<Integer>();
        try(var stream=RollingGateCommands.class.getResourceAsStream("RollingGateCommands.class")){
            assertNotNull(stream);new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                    if(!name.equals("control"))return null;
                    return new MethodVisitor(Opcodes.ASM9){int previous=-1;
                        @Override public void visitInsn(int opcode){previous=opcode;}
                        @Override public void visitVarInsn(int opcode,int variable){previous=opcode;}
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean iface){
                            if(owner.endsWith("/RollingGateCommands")&&method.equals("stop")){
                                assertTrue(desc.endsWith("ServerPlayer;Z)V"),"Resource stop must explicitly preserve the walking mode");stopArguments.add(previous);
                            }previous=opcode;
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        assertEquals(java.util.List.of(Opcodes.ICONST_0),stopArguments);
    }
}
