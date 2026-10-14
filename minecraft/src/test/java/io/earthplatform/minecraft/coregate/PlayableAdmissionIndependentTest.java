package io.earthplatform.minecraft.coregate;

import io.earthplatform.compat.PlayableProfile;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Model arithmetic tests plus compiled call-site entry-fence checks; not a live launch receipt. */
class PlayableAdmissionIndependentTest {
    @Test void profileRejectsAliasUnicodeEscapeInlineCommentAndExtraSettings(){
        for(String text:new String[]{"profile=finite-arena-shader-v2","profile=finite-arena-shader-v1 # enabled","profile=finite-arena-shader-v1\nshader=Photon","profile=\\u0066inite-arena-shader-v1","profile=finite-arena-shader-v1\nprofile=finite-arena-shader-v1","profile=finite-arena-shader-v1\u0000"})assertEquals(PlayableProfile.State.BLOCKED,PlayableProfile.parse(text.getBytes(StandardCharsets.UTF_8)).state(),text);
        assertTrue(PlayableProfile.parse("# restart required\r\nprofile=finite-arena-shader-v1\r\n".getBytes(StandardCharsets.UTF_8)).selected());
    }
    static int[] quad(Direction face){
        int axis=face.getAxis()==Direction.Axis.X?0:face.getAxis()==Direction.Axis.Y?1:2;float plane=face.getAxisDirection()==Direction.AxisDirection.POSITIVE?1:0;int[] vertices=new int[32];int[] order={0,1,3,2};
        for(int v=0;v<4;v++){int bit=0;for(int a=0;a<3;a++)vertices[v*8+a]=Float.floatToRawIntBits(a==axis?plane:(order[v]>>bit++)&1);}
        return vertices;
    }
    @Test void profileSelectionIsAStaticRestartRequiredLoadRatherThanDynamicJvmFlags()throws Exception{
        var loaded=read("io/earthplatform/compat/PlayableProfile$Loaded");var value=loaded.fields.stream().filter(f->f.name.equals("VALUE")).findFirst().orElseThrow();assertTrue((value.access&Opcodes.ACC_FINAL)!=0);assertTrue((value.access&Opcodes.ACC_STATIC)!=0);var init=loaded.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow();assertTrue(calls(init).contains("read"));assertFalse(calls(method("io/earthplatform/compat/PlayableProfile","selection")).contains("getProperty"));
    }
    private static ClassNode read(String name)throws Exception{try(var in=PlayableAdmissionIndependentTest.class.getClassLoader().getResourceAsStream(name+".class")){assertNotNull(in,name);var node=new ClassNode();new ClassReader(in).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return node;}}
    private static MethodNode method(String owner,String name)throws Exception{return read(owner).methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static List<String> calls(MethodNode m){var out=new ArrayList<String>();for(var i:m.instructions)if(i instanceof MethodInsnNode call)out.add(call.name);return out;}
    private static List<String> fields(MethodNode m){var out=new ArrayList<String>();for(var i:m.instructions)if(i instanceof FieldInsnNode f)out.add(f.name);return out;}
    private static List<String> strings(MethodNode m){var out=new ArrayList<String>();for(var i:m.instructions)if(i instanceof LdcInsnNode l&&l.cst instanceof String s)out.add(s);return out;}
    private static void assertBefore(MethodNode m,String check,String action){var calls=calls(m);assertTrue(calls.indexOf(check)>=0,check);assertTrue(calls.indexOf(action)>calls.indexOf(check),check+" must precede "+action);}
}
