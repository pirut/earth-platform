package io.earthplatform.minecraft.coregate;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiled host ordering/authority contracts supplement the pure adverse identity traces. */
class RollingPublishedPairWiringTest {
    private static final String HOST="io/earthplatform/minecraft/coregate/RollingGateClient";
    private static final String IDENTITY="io/earthplatform/minecraft/coregate/RollingPublishedPairIdentity";
    private static ClassNode read(String suffix)throws Exception{
        try(var stream=RollingPublishedPairWiringTest.class.getClassLoader().getResourceAsStream(HOST+suffix+".class")){
            assertNotNull(stream);var node=new ClassNode();new ClassReader(stream).accept(node,0);return node;
        }
    }
    private static MethodNode method(String suffix,String name)throws Exception{return read(suffix).methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static MethodNode method(String name)throws Exception{return method("$Session",name);}
    private static List<AbstractInsnNode> instructions(MethodNode m){return java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false).toList();}
    private static List<MethodInsnNode> calls(MethodNode m){return instructions(m).stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();}
    private static boolean call(MethodNode m,String name){return calls(m).stream().anyMatch(c->c.name.equals(name));}
    private static long identityCalls(MethodNode m,String name){return calls(m).stream().filter(c->c.owner.equals(IDENTITY)&&c.name.equals(name)).count();}
    private static boolean reads(MethodNode m,String name){return instructions(m).stream().anyMatch(i->i instanceof FieldInsnNode f&&f.name.equals(name)&&f.getOpcode()==Opcodes.GETFIELD);}
    private static int index(MethodNode m,Predicate<AbstractInsnNode> p){for(int i=0;i<m.instructions.size();i++)if(p.test(m.instructions.get(i)))return i;return -1;}
    private static int callIndex(MethodNode m,String ownerSuffix,String name){return index(m,i->i instanceof MethodInsnNode c&&c.owner.endsWith(ownerSuffix)&&c.name.equals(name));}
    private static int writeIndex(MethodNode m,String name){return index(m,i->i instanceof FieldInsnNode f&&f.name.equals(name)&&f.getOpcode()==Opcodes.PUTFIELD);}
    private static int trueWriteIndex(MethodNode m,String name){return index(m,i->{if(!(i instanceof FieldInsnNode f)||!f.name.equals(name)||f.getOpcode()!=Opcodes.PUTFIELD)return false;var previous=i.getPrevious();while(previous!=null&&previous.getOpcode()<0)previous=previous.getPrevious();return previous!=null&&previous.getOpcode()==Opcodes.ICONST_1;});}
    private static boolean handle(MethodNode m,String ownerSuffix,String name){return instructions(m).stream().filter(InvokeDynamicInsnNode.class::isInstance).map(InvokeDynamicInsnNode.class::cast).flatMap(d->java.util.Arrays.stream(d.bsmArgs)).anyMatch(a->a instanceof Handle h&&h.getOwner().endsWith(ownerSuffix)&&h.getName().equals(name));}

    @Test void committedIdentityHasOneSessionOwnerAndOnlyRealInstallationCanPublish()throws Exception{
        var session=read("$Session");var field=session.fields.stream().filter(f->f.name.equals("publishedPairIdentity")).findFirst().orElseThrow();
        assertEquals("L"+IDENTITY+";",field.desc);assertNotEquals(0,field.access&Opcodes.ACC_FINAL);
        assertEquals(1,session.methods.stream().mapToLong(m->identityCalls(m,"publish")).sum());
        assertEquals(1,identityCalls(method("installPair"),"publish"));
        assertEquals(Set.of("invalidateOwned","close"),session.methods.stream().filter(m->identityCalls(m,"revoke")>0).map(m->m.name).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void installedDigestPublishesAfterFarNativeAndMapInstallationBeforeVisibilityOrReceipt()throws Exception{
        var m=method("installPair");int publish=callIndex(m,"RollingPublishedPairIdentity","publish");assertTrue(publish>=0);
        for(var prerequisite:List.of(new String[]{"DhPreparedLodView","commitPrepared"},new String[]{"DhPreparedLodView","coverageReady"},
                new String[]{"RollingEditPresentation","retire"},new String[]{"SceneMesh$Reuse","commit"},
                new String[]{"RollingGateClient$Session","closeNativeReuses"},new String[]{"RollingGateClient$Session","closeExcept"},
                new String[]{"Map","putAll"},new String[]{"DhPreparedLodView","diagnostics"})){
            int before=callIndex(m,prerequisite[0],prerequisite[1]);assertTrue(before>=0&&before<publish,String.join(".",prerequisite));
        }
        assertTrue(writeIndex(m,"publishedPairPublication")<publish);
        assertTrue(trueWriteIndex(m,"visible")>publish);assertTrue(trueWriteIndex(m,"pairSent")>publish);
        assertTrue(callIndex(m,"RollingGateClient$Session","send")>publish);
        var prior=m.instructions.get(publish).getPrevious();assertInstanceOf(FieldInsnNode.class,prior);assertEquals("digest",((FieldInsnNode)prior).name);
        assertTrue(reads(m,"allowed"));assertTrue(reads(m,"preparedSent"));assertTrue(reads(m,"pairSent"));assertTrue(call(m,"validateCandidate"));
    }
    @Test void candidateMetadataReadinessAllowanceAndFallbackCannotRelabelPublishedGeometry()throws Exception{
        for(String name:List.of("metadata","ready","allow","checkPair","committed","ack","requestReplacementFallback","replacementFallback","beginEdit")){
            var m=method(name);assertEquals(0,identityCalls(m,"publish"),name);assertEquals(0,identityCalls(m,"revoke"),name);
        }
        assertTrue(writeIndex(method("metadata"),"digest")>=0);assertTrue(writeIndex(method("ready"),"digest")>=0);
        assertTrue(writeIndex(method("replacementFallback"),"digest")>=0);
    }
    @Test void framePinsInstalledCurrentOrImmutableHistoricalIdentityAndNeverCandidateDigest()throws Exception{
        var m=method("shaderFrame");assertFalse(reads(m,"digest"));assertEquals(1,identityCalls(m,"digest"));
        assertTrue(call(m,"manifestDigest"));assertTrue(reads(m,"visible"));assertTrue(reads(m,"shaderPreparedFrame"));
        int update=callIndex(m,"RollingGateClient$Session","updatePreparedPair"),pin=writeIndex(m,"shaderFrameDigest");
        assertTrue(update>=0&&pin>update);assertEquals(1,instructions(m).stream().filter(i->i instanceof FieldInsnNode f&&f.name.equals("shaderFrameDigest")&&f.getOpcode()==Opcodes.PUTFIELD).count());
        // The actual-frame comparison jumps over preparation/pinning when its serial is unchanged.
        assertTrue(instructions(m).stream().anyMatch(i->i instanceof JumpInsnNode j&&j.getOpcode()==Opcodes.IFEQ&&m.instructions.indexOf(i)<update&&m.instructions.indexOf(j.label)>pin));
        assertTrue(callIndex(m,"InstalledShaderCarrier$Frame","<init>")>pin);
    }
    @Test void currentAndHistoricalPresentationChecksKeepAllExistingIndependentAuthorities()throws Exception{
        var m=method("shaderPresentationCurrent");assertFalse(reads(m,"digest"));assertEquals(1,identityCalls(m,"digest"));
        for(String name:List.of("valid","invalidationRequested","isCurrent","frozen","manifestDigest","current","publishedFarCoverageReady","publishedNativeCurrent"))assertTrue(call(m,name),name);
        var nativeCurrent=method("publishedNativeCurrent");
        for(String name:List.of("distanceMode","isEmpty","allMatch","publication","joined","isPublishedCurrent","exactReady","requirePrepared"))assertTrue(call(nativeCurrent,name),name);
        assertTrue(handle(nativeCurrent,"SceneMesh","isCurrent"),"Legacy currentness retains strict mesh validation and a nonempty map");
        for(String name:List.of("publishedBody","publishedBodyJoined","publishedPairPublication","publishedVisual","replacementInput"))assertTrue(reads(nativeCurrent,name),name);
        assertTrue(call(m,"activePresentation"));var presentation=method("activePresentation");
        assertTrue(call(presentation,"distanceMode"));assertTrue(reads(presentation,"distancePresentation"));assertTrue(reads(presentation,"presentation"));
        assertTrue(reads(m,"shaderFrameDigest"));assertTrue(reads(m,"shaderFrameHistorical"));assertTrue(reads(m,"visible"));
        assertTrue(call(method("shaderNative"),"shaderPresentationCurrent"));assertTrue(call(method("shaderFar"),"shaderPresentationCurrent"));
    }
    @Test void editStampCapturesCommittedDigestBeforeCandidateIsClearedAndKeepsCollisionFence()throws Exception{
        var m=method("beginEdit");assertFalse(reads(m,"digest"));assertEquals(1,identityCalls(m,"digest"));
        int digest=callIndex(m,"RollingPublishedPairIdentity","digest"),stamp=callIndex(m,"RollingEditPresentation$Stamp","<init>"),capture=callIndex(m,"RollingEditPresentation","capture");
        assertTrue(digest>=0&&stamp>digest&&capture>stamp);assertTrue(writeIndex(m,"digest")>capture);
        assertTrue(call(m,"publishedFarCoverageReady"));assertTrue(call(m,"ready"));assertTrue(call(m,"fenceCollision"));
    }
    @Test void invalidationAndCloseRevokeBeforeAnyFallibleCleanup()throws Exception{
        for(String name:List.of("invalidateOwned","close")){
            var m=method(name);int revoke=callIndex(m,"RollingPublishedPairIdentity","revoke");assertTrue(revoke>=0,name);
            assertTrue(revoke<callIndex(m,"RollingReplacementAuthority","close"));
            assertTrue(revoke<callIndex(m,"RollingGateClient$Session","closeReplacementCapture"));
            assertTrue(revoke<callIndex(m,"RollingEditPresentation","invalidate"));
            assertTrue(call(m,"fenceCollision"));assertTrue(instructions(m).stream().anyMatch(i->i.getOpcode()==Opcodes.MONITORENTER));
        }
        assertTrue(call(method("invalidate"),"invalidateOwned"));
        assertTrue(read("$Resources").methods.stream().anyMatch(m->call(m,"invalidate")));
    }
    @Test void protocolStillRequiresExactCandidateAndStaleAllowanceRemainsFailClosed()throws Exception{
        var check=method("checkPair");assertTrue(reads(check,"digest"));assertEquals(0,identityCalls(check,"digest"));
        for(String name:List.of("valid","resourceGeneration","equals","isEmpty","invalidationRequested"))assertTrue(call(check,name),name);
        var allow=method("allow");assertTrue(call(allow,"checkPair"));assertTrue(call(allow,"isPrepared"));assertTrue(call(allow,"fenceCollision"));
        var committed=method("committed");assertTrue(call(committed,"checkPair"));assertTrue(call(committed,"retainsOwners"));assertTrue(reads(committed,"digest"));
        assertTrue(call(committed,"publish")); // Existing authoritative collision publication, not the identity holder.
        assertEquals(0,identityCalls(committed,"publish"));
        var handle=method("","handle");assertTrue(call(handle,"allow"));assertTrue(call(handle,"closeCurrent"));
        assertTrue(handle.tryCatchBlocks.stream().anyMatch(b->"java/lang/RuntimeException".equals(b.type)));
    }
    @Test void installationAndFramePinningRetainExistingHostFence()throws Exception{
        for(String name:List.of("shaderFrame","render"))assertTrue(instructions(method(name)).stream().anyMatch(i->i.getOpcode()==Opcodes.MONITORENTER),name);
        var callers=read("$Session").methods.stream().filter(m->call(m,"installPair")).map(m->m.name).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("updatePreparedPair"),callers);
        assertEquals(Set.of("shaderFrame","render"),read("$Session").methods.stream().filter(m->call(m,"updatePreparedPair")).map(m->m.name).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void diagnosticsExposeCommittedIdentityBesideUnchangedProtocolCandidate()throws Exception{
        var m=method("report");var labels=instructions(m).stream().filter(LdcInsnNode.class::isInstance).map(LdcInsnNode.class::cast).map(i->i.cst).toList();
        assertTrue(labels.contains("publishedPairDigest"));assertTrue(labels.contains("publishedPairPublication"));assertTrue(labels.contains("pairPublicationDigest"));
        assertTrue(reads(m,"digest"));assertEquals(1,identityCalls(m,"digest"));
    }
}
