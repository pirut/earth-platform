package io.earthplatform.compat.dh;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact pinned Photon used-uniform correction. No fake test claims actual GPU acceptance. */
class InstalledIrisProjectionContractTest {
    public static final class Program {
        public int projectionUniform = -1; // Valid for Photon: this generic uniform is unused.
        public int modelViewUniform = 31;
        int usedLocation = 17, calls; boolean fail; String lastName;
        public int tryGetUniformLocation2(CharSequence name) {
            calls++; lastName = name.toString();
            if (fail) throw new IllegalStateException("program no longer linked");
            return "dhProjection".contentEquals(name) ? usedLocation : -1;
        }
    }
    private static int lookup(Program p) throws ReflectiveOperationException {
        return ReflectiveIrisAccess.photonProjectionLocation(p, Program.class.getMethod("tryGetUniformLocation2", CharSequence.class));
    }
    @Test void exactUsedUniformSucceedsWhenGenericProjectionIsLegitimatelyInactive() throws Exception {
        Program p=new Program(); assertEquals(17,lookup(p)); assertEquals("dhProjection",p.lastName);
        assertEquals(-1,p.projectionUniform); assertEquals(31,p.modelViewUniform); assertEquals(1,p.calls);
        assertEquals(17,new InstalledIrisRuntime.BorrowedPass(p,43,7,53,false,true,17,31).projectionLocation());
        p.usedLocation=0;assertEquals(0,lookup(p));
    }
    @Test void missingUsedUniformNeverFallsBackToActiveGenericUniform() {
        Program p=new Program();p.usedLocation=-1;p.projectionUniform=18;
        assertTrue(assertThrows(IllegalStateException.class,()->lookup(p)).getMessage().contains("dhProjection"));
        assertEquals("dhProjection",p.lastName);assertEquals(1,p.calls);
        assertThrows(IllegalStateException.class,()->new InstalledIrisRuntime.BorrowedPass(p,43,7,53,false,true,-1,31));
        assertThrows(IllegalStateException.class,()->new InstalledIrisRuntime.BorrowedPass(p,43,7,53,false,true,17,-1));
    }
    @Test void lookupIsOnExactCurrentProgramAndDoesNotCacheAnOldProgramsLocation() throws Exception {
        Program a=new Program(),b=new Program();b.usedLocation=25;
        assertEquals(17,lookup(a));assertEquals(25,lookup(b));a.usedLocation=19;assertEquals(19,lookup(a));
        assertEquals(2,a.calls);assertEquals(1,b.calls);
        a.fail=true;var failure=assertThrows(InvocationTargetException.class,()->lookup(a));
        assertEquals("program no longer linked",failure.getCause().getMessage());
    }
    @Test void exactUnchangedPhotonUsesDhProjectionAndTransformedModelView() throws Exception {
        Path pack=Path.of(System.getProperty("earthplatform.installedShaderTestPack"));
        assertEquals(InstalledIrisRuntime.PHOTON_SHA256,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pack))));
        try(var zip=new ZipFile(pack.toFile())) {
            for(String name:List.of("shaders/program/dh_terrain.vsh","shaders/program/dh_water.vsh")) {
                String source=new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(source.contains("uniform mat4 dhProjection;"));
                assertTrue(source.contains("vec4 clip_pos = dhProjection * vec4(pos, 1.0);"));
                assertTrue(source.contains("pos = transform(gl_ModelViewMatrix, pos);"));
                assertFalse(source.contains("gl_ProjectionMatrix"));assertFalse(source.contains("gl_ModelViewProjectionMatrix"));
            }
        }
    }
    @Test void compiledResolverUsesNamedLookupAndRetainsMandatoryModelViewField() throws Exception {
        var node=own("io/earthplatform/compat/dh/ReflectiveIrisAccess");
        var pass=method(node,"pass");
        assertTrue(calls(pass).stream().anyMatch(m->m.name.equals("photonProjectionLocation")));
        assertTrue(java.util.Arrays.stream(pass.instructions.toArray()).anyMatch(i->i instanceof FieldInsnNode f&&f.name.equals("modelView")));
        assertFalse(java.util.Arrays.stream(pass.instructions.toArray()).anyMatch(i->i instanceof FieldInsnNode f&&f.name.equals("projection")));
        var lookup=method(node,"photonProjectionLocation");
        assertTrue(java.util.Arrays.stream(lookup.instructions.toArray()).anyMatch(i->i instanceof LdcInsnNode l&&"dhProjection".equals(l.cst)));
        assertTrue(calls(lookup).stream().anyMatch(m->m.owner.equals("java/lang/reflect/Method")&&m.name.equals("invoke")));
        assertFalse(calls(lookup).stream().anyMatch(m->m.name.startsWith("glUniform")||m.name.startsWith("setUniform")));
    }
    @Test void actualUniformReadbackAndDepthCornerChecksRemainInBackendBeforeDraw() throws Exception {
        var node=own("io/earthplatform/compat/dh/shader/IrisShaderFarBackend");
        var depth=method(node,"validateDepth");
        assertEquals(2,calls(depth).stream().filter(m->m.name.equals("glGetUniformfv")).count());
        assertTrue(calls(depth).stream().anyMatch(m->m.owner.endsWith("DhShaderDepthContract")&&m.name.equals("verify")));
        assertTrue(calls(depth).stream().anyMatch(m->m.name.equals("projectionLocation")));
        assertTrue(calls(depth).stream().anyMatch(m->m.name.equals("modelViewLocation")));
        var inPass=calls(method(node,"inPass"));
        int validate=-1,draw=-1;
        for(int i=0;i<inPass.size();i++){if(inPass.get(i).name.equals("validateDepth"))validate=i;if(inPass.get(i).owner.equals("java/lang/Runnable")&&inPass.get(i).name.equals("run"))draw=i;}
        assertTrue(validate>=0&&draw>validate);
    }
    private static ClassNode own(String name) throws Exception {
        try(var in=InstalledIrisProjectionContractTest.class.getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(in);var node=new ClassNode();new ClassReader(in.readAllBytes()).accept(node,0);return node;
        }
    }
    private static MethodNode method(ClassNode node,String name){return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static List<MethodInsnNode> calls(MethodNode method){var list=new ArrayList<MethodInsnNode>();for(var i:method.instructions)if(i instanceof MethodInsnNode m)list.add(m);return list;}
}
