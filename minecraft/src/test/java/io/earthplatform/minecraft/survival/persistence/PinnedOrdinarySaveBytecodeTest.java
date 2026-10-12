package io.earthplatform.minecraft.survival.persistence;

import java.io.*;
import java.lang.reflect.Modifier;
import java.util.*;
import net.minecraft.Util;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class PinnedOrdinarySaveBytecodeTest {
    MethodNode method(Class<?> type,String name,String descriptor) throws IOException {
        try(var input=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){
            assertNotNull(input);ClassNode node=new ClassNode();new ClassReader(input).accept(node,0);
            return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(descriptor)).findFirst().orElseThrow();
        }
    }
    List<MethodInsnNode> calls(MethodNode method){
        List<MethodInsnNode> calls=new ArrayList<>();for(var i:method.instructions)if(i instanceof MethodInsnNode call)calls.add(call);return calls;
    }
    MethodInsnNode unique(MethodNode method,String owner,String name,String descriptor){
        var found=calls(method).stream().filter(c->c.owner.equals(owner)&&c.name.equals(name)&&c.desc.equals(descriptor)).toList();
        assertEquals(1,found.size());return found.getFirst();
    }
    @Test void exactSaveAnchorsCatchBoundaryAndSingleReturnMatchPinnedContract() throws Exception {
        var save=method(PlayerDataStorage.class,"save","(Lnet/minecraft/world/entity/player/Player;)V");
        var serialize=unique(save,"net/minecraft/world/entity/player/Player","saveWithoutId","(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;");
        var write=unique(save,"net/minecraft/nbt/NbtIo","writeCompressed","(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V");
        var replace=unique(save,"net/minecraft/Util","safeReplaceFile","(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V");
        var event=unique(save,"net/neoforged/neoforge/event/EventHooks","firePlayerSavingEvent","(Lnet/minecraft/world/entity/player/Player;Ljava/io/File;Ljava/lang/String;)V");
        var caught=unique(save,"org/slf4j/Logger","warn","(Ljava/lang/String;Ljava/lang/Object;)V");
        assertTrue(save.instructions.indexOf(serialize)<save.instructions.indexOf(write));assertTrue(save.instructions.indexOf(write)<save.instructions.indexOf(replace));
        assertTrue(save.instructions.indexOf(replace)<save.instructions.indexOf(event));assertTrue(save.instructions.indexOf(event)<save.instructions.indexOf(caught));
        assertEquals(1,save.tryCatchBlocks.size());var handler=save.tryCatchBlocks.getFirst();assertEquals("java/lang/Exception",handler.type);
        assertTrue(save.instructions.indexOf(handler.start)<save.instructions.indexOf(serialize));
        assertTrue(save.instructions.indexOf(event)<save.instructions.indexOf(handler.end));
        assertTrue(save.instructions.indexOf(handler.handler)<save.instructions.indexOf(caught));
        int returns=0;for(var instruction:save.instructions)if(instruction.getOpcode()==Opcodes.RETURN)returns++;assertEquals(1,returns);
    }
    @Test void nativeVoidWrapperDiscardsTheOneBooleanWithFalseMoveFlag() throws Exception {
        var wrapper=method(Util.class,"safeReplaceFile","(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V");
        var call=unique(wrapper,"net/minecraft/Util","safeReplaceOrMoveFile","(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;Z)Z");
        List<Integer> opcodes=new ArrayList<>();for(var i:wrapper.instructions)if(i.getOpcode()>=0)opcodes.add(i.getOpcode());
        assertEquals(List.of(Opcodes.ALOAD,Opcodes.ALOAD,Opcodes.ALOAD,Opcodes.ICONST_0,Opcodes.INVOKESTATIC,Opcodes.POP,Opcodes.RETURN),opcodes);
        assertEquals(Opcodes.INVOKESTATIC,call.getOpcode());
    }
    @Test void publicHookFacadeCannotMintAnAttemptOrCompletion(){
        assertFalse(Modifier.isPublic(NativeOrdinarySaveObserver.class.getModifiers()));
        assertFalse(Modifier.isPublic(NativeOrdinarySaveObserver.Authority.class.getModifiers()));
        assertFalse(Modifier.isPublic(NativeOrdinarySaveObserver.Completion.class.getModifiers()));
        for(var constructor:NativeOrdinarySaveObserver.Completion.class.getDeclaredConstructors())assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        for(var method:NativeOrdinarySaveHooks.class.getDeclaredMethods())assertFalse(Set.of("save","begin","complete","succeeded").contains(method.getName()));
    }
}
