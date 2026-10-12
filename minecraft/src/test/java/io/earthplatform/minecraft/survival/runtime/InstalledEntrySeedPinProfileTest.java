package io.earthplatform.minecraft.survival.runtime;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original-resource selection shape only; no private token or callback Approval is constructed. */
class InstalledEntrySeedPinProfileTest {
    private static final String OWNER="io/earthplatform/minecraft/survival/runtime/InstalledMarinSessionAdmission";
    private static final String CLOSURE="io/earthplatform/minecraft/survival/runtime/InstalledSessionCallbackClosure";
    private static ClassNode node(String name) throws IOException {
        try(var in=InstalledEntrySeedPinProfileTest.class.getResourceAsStream("/"+name+".class")){
            assertNotNull(in);var node=new ClassNode();new ClassReader(in).accept(node,0);return node;
        }
    }
    private static MethodNode method(ClassNode node,String name){return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static List<MethodInsnNode> calls(MethodNode method){var result=new ArrayList<MethodInsnNode>();for(var i:method.instructions)if(i instanceof MethodInsnNode c)result.add(c);return result;}
    private static Set<String> hashes(MethodNode method){var result=new HashSet<String>();for(var i:method.instructions)if(i instanceof LdcInsnNode c&&c.cst instanceof String s&&s.matches("[a-f0-9]{64}"))assertTrue(result.add(s));return result;}
    @Test void prepareAndSeedUseExactlyNineInstalledOriginalDigests() throws Exception {
        var owner=node(OWNER);
        assertEquals(Set.of("ec7c90a8b6a771cfd53b5038f377a72314b9efbf2301ed1ce540723b7d19c6d2","f8193992da15902eadb8a77681b1d9ee4ab3bed175ab7029d46061fa6486ba15","26a16a6055bb148e1ffbe0d73bf74c38b710046a96f50600f3ae6b38a618bf5a","0aaf4d99d16b08f670b1a541207224a57ba65215b944c64818bf621332473641","a01831a60461ab3b356c4706eb6e606026e272f7db1226a8681192c43ef64405"),hashes(method(owner,"prepare")));
        assertEquals(Set.of("19b42e51848fd27df3683dacf694baa5126ee9f0665f12afdc65f82493a1e5a3","b5eac29d33c7536e1dfd5a66bfbf59f95bf92f8a2ce9f9d86eeddcd1f0feb36e","ba99c2f7c0d1d64411441cbb79304c85882ea2e167d1ec2b0a706ae03267db3b","d618c2e8d162c5a161e2bcea641a40d26b349fd05cdf39d99c333f11acede548"),hashes(method(owner,"readNativeSeed")));
    }
    @Test void onePrivateResourceVerifierHasOnlyTheTwoFixedCallers() throws Exception {
        var owner=node(OWNER);var verifier=method(owner,"requireBinary");assertEquals(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC|Opcodes.ACC_SYNCHRONIZED,verifier.access);
        assertEquals(1,calls(verifier).stream().filter(c->c.name.equals("getResourceAsStream")).count());assertTrue(hashes(verifier).isEmpty());
        var callers=new HashSet<String>();int count=0;for(var m:owner.methods)for(var call:calls(m))if(call.owner.equals(OWNER)&&call.name.equals("requireBinary")){callers.add(m.name);count++;}
        assertEquals(Set.of("prepare","readNativeSeed"),callers);assertEquals(9,count);
    }
    @Test void seedStillUsesActualSavedOptionsAndExactPrivateZoomField() throws Exception {
        var seed=method(node(OWNER),"readNativeSeed");
        assertTrue(calls(seed).stream().anyMatch(c->c.owner.equals("net/minecraft/world/level/biome/BiomeManager")&&c.name.equals("obfuscateSeed")&&c.desc.equals("(J)J")));
        assertTrue(calls(seed).stream().anyMatch(c->c.name.equals("getSeed")&&c.desc.equals("()J")));
        assertTrue(calls(seed).stream().anyMatch(c->c.name.equals("getDeclaredField")));
        assertTrue(Arrays.stream(seed.instructions.toArray()).anyMatch(i->i instanceof LdcInsnNode c&&c.cst.equals("biomeZoomSeed")));
    }
    @Test void approvalStillRefusesBeforeTokenConstructionCanComplete() throws Exception {
        var prepare=method(node(OWNER),"prepare");var observation=calls(prepare).stream().filter(c->c.owner.equals(CLOSURE)&&c.name.equals("observe")).findFirst().orElseThrow();
        var constructor=calls(prepare).stream().filter(c->c.owner.equals(OWNER+"$Token")&&c.name.equals("<init>")).findFirst().orElseThrow();assertTrue(prepare.instructions.indexOf(observation)<prepare.instructions.indexOf(constructor));
        var observe=method(node(CLOSURE),"observe");assertEquals(1,Arrays.stream(observe.instructions.toArray()).filter(i->i.getOpcode()==Opcodes.ATHROW).count());
        assertEquals(0,Arrays.stream(observe.instructions.toArray()).filter(i->i.getOpcode()==Opcodes.ARETURN).count());
        assertTrue((method(node(OWNER+"$Token"),"<init>").access&Opcodes.ACC_PRIVATE)!=0);
    }
}
