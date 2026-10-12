package io.earthplatform.minecraft.survival.persistence;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Own compiled shape tests. These neither construct an installed Token nor claim native execution. */
class NativeInstalledSavePinProfileTest {
    private static final String BASE="io/earthplatform/minecraft/survival/";
    private static final String OBS=BASE+"persistence/NativeOrdinarySaveObserver";
    private static final String PROFILE=OBS+"$PinProfile";
    private static final String TRANSFER=BASE+"persistence/NativeSessionTransferAdmission";
    private static final String TOKEN=BASE+"runtime/InstalledMarinSessionAdmission$Token";
    private static ClassNode node(String name) throws IOException {
        try(var input=NativeInstalledSavePinProfileTest.class.getResourceAsStream("/"+name+".class")){
            assertNotNull(input);var result=new ClassNode();new ClassReader(input).accept(result,0);return result;
        }
    }
    private static MethodNode method(ClassNode node,String name){
        var found=node.methods.stream().filter(m->m.name.equals(name)).toList();assertEquals(1,found.size());return found.getFirst();
    }
    private static List<MethodInsnNode> calls(MethodNode method){
        var result=new ArrayList<MethodInsnNode>();for(var op:method.instructions)if(op instanceof MethodInsnNode call)result.add(call);return result;
    }
    private static MethodInsnNode unique(MethodNode method,String owner,String name){
        var found=calls(method).stream().filter(c->c.owner.equals(owner)&&c.name.equals(name)).toList();assertEquals(1,found.size());return found.getFirst();
    }
    private static List<String> hashes(MethodNode method){
        var result=new ArrayList<String>();for(var op:method.instructions)if(op instanceof LdcInsnNode ldc&&ldc.cst instanceof String text&&text.matches("[a-f0-9]{64}"))result.add(text);return result;
    }
    @Test void credentialsAndAttemptProfileArePrivateFinalAndConstructorOnlyStores() throws Exception {
        var profile=node(PROFILE);var inner=profile.innerClasses.stream().filter(c->c.name.equals(PROFILE)).findFirst().orElseThrow();
        assertEquals(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL,inner.access);
        assertEquals(Set.of("token","gate"),new HashSet<>(profile.fields.stream().map(f->f.name).toList()));
        for(var field:profile.fields)assertEquals(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL,field.access);
        for(var m:profile.methods)assertTrue((m.access&Opcodes.ACC_PRIVATE)!=0);
        var constructor=method(profile,"<init>");assertEquals(1,calls(constructor).size());unique(constructor,"java/lang/Object","<init>");
        var attempt=node(OBS+"$Attempt");var field=attempt.fields.stream().filter(f->f.name.equals("profile")).findFirst().orElseThrow();
        assertEquals(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL,field.access);assertEquals("L"+PROFILE+";",field.desc);
    }
    @Test void activePublicationAndDuplicatePoisonStillPrecedeBindingAuthentication() throws Exception {
        var save=node(OBS).methods.stream().filter(m->m.name.equals("save")&&m.desc.contains(TOKEN)).findFirst().orElseThrow();
        var cas=calls(save).stream().filter(c->c.owner.equals("java/util/concurrent/atomic/AtomicReference")&&c.name.equals("compareAndSet")).findFirst().orElseThrow();
        var bind=unique(save,OBS+"$Attempt","bind");assertTrue(save.instructions.indexOf(cas)<save.instructions.indexOf(bind));
        assertTrue(calls(save).stream().anyMatch(c->c.owner.equals(OBS+"$Attempt")&&c.name.equals("poison")&&save.instructions.indexOf(c)<save.instructions.indexOf(bind)));
        assertTrue(calls(save).stream().noneMatch(c->c.owner.equals(TOKEN)));
        var bindMethod=method(node(OBS+"$Attempt"),"bind");unique(bindMethod,PROFILE,"requireSources");
    }
    @Test void authenticationPrecedesEveryExpectedSelectionAndCurrentChecksRepeatIt() throws Exception {
        var sources=method(node(PROFILE),"requireSources");var auth=unique(sources,PROFILE,"requireCurrent");
        var selected=calls(sources).stream().filter(c->c.owner.equals(PROFILE)&&c.name.equals("expected")).toList();assertEquals(7,selected.size());
        for(var select:selected)assertTrue(sources.instructions.indexOf(auth)<sources.instructions.indexOf(select));
        assertEquals(9,calls(sources).stream().filter(c->c.owner.equals(OBS)&&c.name.equals("requireBinary")).count());
        var current=method(node(PROFILE),"requireCurrent");unique(current,TOKEN,"requireOrdinarySave");
        assertTrue(calls(current).stream().noneMatch(c->c.owner.equals("java/lang/Boolean")));
        assertEquals(2,calls(method(node(OBS+"$Attempt"),"checkCurrent")).stream().filter(c->c.owner.equals(PROFILE)&&c.name.equals("requireCurrent")).count());
    }
    @Test void chooserDoesNoResourceReadHashFallbackOrCallback() throws Exception {
        for(String name:List.of(PROFILE,TRANSFER+"$PinProfile")){
            var chooser=method(node(name),"expected");assertTrue(calls(chooser).isEmpty());assertTrue(hashes(chooser).isEmpty());
            assertTrue((chooser.access&Opcodes.ACC_PRIVATE)!=0);
        }
        var verifier=method(node(OBS),"requireBinary");assertEquals(1,calls(verifier).stream().filter(c->c.name.equals("getResourceAsStream")).count());
        assertTrue(calls(verifier).stream().noneMatch(c->c.name.equals("expected")));
    }
    @Test void transferAuthenticatesBeforeSelectingEitherPrivateProfile() throws Exception {
        var require=method(node(TRANSFER),"require");var auth=unique(require,BASE+"persistence/NativeSessionExecutionGate","requireInstalledTransfer");
        int choices=0;for(var op:require.instructions)if(op instanceof FieldInsnNode field&&field.owner.equals(TRANSFER+"$PinProfile")){
            assertTrue(require.instructions.indexOf(auth)<require.instructions.indexOf(op));choices++;
        }
        assertEquals(2,choices);assertEquals(8,calls(require).stream().filter(c->c.owner.equals(TRANSFER+"$PinProfile")&&c.name.equals("expected")).count());
        unique(require,BASE+"persistence/NativeSessionCallbackAdmission","requireTransfer");unique(require,TRANSFER,"requireNonmatchingDimensionCriteria");
    }
    @Test void installedEnvelopeAuthenticatesBeforeItsInstalledOnlyTable() throws Exception {
        var envelope=node(BASE+"persistence/InstalledOrdinarySaveEnvelope");var init=method(envelope,"<init>");
        var auth=unique(init,TOKEN,"requireOrdinarySave");var pins=unique(init,envelope.name,"pinSources");assertTrue(init.instructions.indexOf(auth)<init.instructions.indexOf(pins));
        var hashes=hashes(method(envelope,"pinSources"));assertEquals(12,hashes.size());
        assertTrue(hashes.contains("0aaf4d99d16b08f670b1a541207224a57ba65215b944c64818bf621332473641"));
        assertFalse(hashes.contains("6ac775e84237f6bee99249e0c116a2ab90a8835d835fd76e52e39876433fc025"));
        assertEquals(16,hashes(method(node(PROFILE),"requireSources")).size());
        assertEquals(16,hashes(method(node(TRANSFER),"require")).size());
    }
    @Test void upstreamInstalledApprovalStillCannotBeMinted() throws Exception {
        var closure=node(BASE+"runtime/InstalledSessionCallbackClosure");var observe=method(closure,"observe");
        assertTrue(calls(observe).stream().noneMatch(c->c.owner.equals(closure.name+"$Approval")&&c.name.equals("<init>")));
        int throwsCount=0,returns=0;for(var op:observe.instructions){if(op.getOpcode()==Opcodes.ATHROW)throwsCount++;if(op.getOpcode()==Opcodes.ARETURN)returns++;}
        assertEquals(1,throwsCount);assertEquals(0,returns);
        assertTrue(hashes(method(node(BASE+"runtime/InstalledMarinSessionAdmission"),"prepare")).contains("ec7c90a8b6a771cfd53b5038f377a72314b9efbf2301ed1ce540723b7d19c6d2"));
    }
}
