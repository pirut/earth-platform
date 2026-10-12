package io.earthplatform.minecraft.coregate;

import io.earthplatform.minecraft.enginebridge.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.coregate.ArenaHostReviewFixtures.*;

/** Repeated movement must not let equal-content READY copies become additional retained owners. */
class RollingArenaCaptureOwnershipIndependentTest {
    static RollingCubeActor.StageReady decode(RollingCubeActor.StageReady ready) {
        var b=new FriendlyByteBuf(Unpooled.buffer());try{RollingGateWire.writeReady(b,ready);return RollingGateWire.readReady(b,D.world(),1);}finally{b.release();}
    }
    @Test void repeatedUnchangedMovementRetainsTheOneExistingImmutableCaptureOwner(){
        var published=ready(first(),AIR);var current=published;String digest=RollingGateWire.manifestDigest(published);
        for(int movement=0;movement<32;movement++){
            var incoming=decode(published);assertNotSame(AIR,incoming.arenaCapture());
            current=RollingArenaCaptureOwner.canonicalize(incoming,AIR);
            assertSame(AIR,current.arenaCapture());assertSame(incoming.arenaCertificate(),current.arenaCertificate());assertEquals(digest,RollingGateWire.manifestDigest(current));
        }
        assertSame(published.arenaCapture(),current.arenaCapture());
    }
    @Test void changedContentRetainsItsDistinctNewOwnerAndCannotBorrowOldBytes(){
        var target=A.positions().getLast();var changed=AIR.withMaterial(31,159,31,ArenaCapture.DIORITE).compact();var next=ready(certificate(2,2,65,Map.of(target,1L),changed),changed);var incoming=decode(next);
        var canonical=RollingArenaCaptureOwner.canonicalize(incoming,AIR);assertSame(incoming,canonical);assertSame(incoming.arenaCapture(),canonical.arenaCapture());assertNotSame(AIR,canonical.arenaCapture());assertEquals(ArenaCapture.DIORITE,canonical.arenaCapture().materialAt(31,159,31));assertEquals(ArenaCapture.AIR,AIR.materialAt(31,159,31));
        assertThrows(IllegalArgumentException.class,()->canonical.arenaCertificate().verifyCapture(AIR));
    }
    @Test void noOpDurableRevisionMayReuseContentWithoutRegressingCertificateOrManifest(){
        var old=ready(first(),AIR);var target=A.positions().getLast();var next=ready(certificate(2,2,65,Map.of(target,1L),AIR),AIR);var incoming=decode(next);assertNotSame(AIR,incoming.arenaCapture());
        var canonical=RollingArenaCaptureOwner.canonicalize(incoming,AIR);assertSame(AIR,canonical.arenaCapture());assertSame(incoming.arenaCertificate(),canonical.arenaCertificate());assertEquals(1,canonical.arenaCertificate().revision(target));assertEquals(2,canonical.arenaCertificate().queryAttestation().snapshotGeneration());assertEquals(RollingGateWire.manifestDigest(next),RollingGateWire.manifestDigest(canonical));assertNotEquals(RollingGateWire.manifestDigest(old),RollingGateWire.manifestDigest(canonical));
    }
    @Test void missingArenaOrForgedCertificateCapturePairCannotBeCanonicalized(){
        var valid=ready(first(),AIR);assertSame(valid,RollingArenaCaptureOwner.canonicalize(valid,null));
        var legacy=new RollingCubeActor.StageReady(D.world(),1,1,A.origin(),valid.versions());assertThrows(IllegalArgumentException.class,()->RollingArenaCaptureOwner.canonicalize(legacy,AIR));
        var changed=AIR.withMaterial(1,129,1,ArenaCapture.STONE);assertThrows(IllegalArgumentException.class,()->ready(first(),changed));
    }
    @Test void clientCanonicalizesBeforeReplicaRetentionAndBeforeConstructingFarInput()throws Exception{
        try(var in=getClass().getClassLoader().getResourceAsStream("io/earthplatform/minecraft/coregate/RollingGateClient$Session.class")){
            assertNotNull(in);var type=new ClassNode();new ClassReader(in).accept(type,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);var method=type.methods.stream().filter(m->m.name.equals("ready")).findFirst().orElseThrow();var calls=new ArrayList<String>();for(var i:method.instructions)if(i instanceof MethodInsnNode call)calls.add(call.owner+"."+call.name);
            int canonical=calls.indexOf("io/earthplatform/minecraft/coregate/RollingArenaCaptureOwner.canonicalize"),replica=calls.indexOf("io/earthplatform/minecraft/coregate/RollingGateReplica.acceptReady"),far=calls.indexOf("io/earthplatform/minecraft/coregate/RollingGateClient$Session.acceptArena");assertTrue(canonical>=0);assertTrue(canonical<replica);assertTrue(replica<far);
        }
    }
}
