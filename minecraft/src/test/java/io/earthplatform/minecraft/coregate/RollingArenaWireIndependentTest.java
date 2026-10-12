package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.coregate.ArenaHostReviewFixtures.*;

class RollingArenaWireIndependentTest {
    @Test void exactArenaRoundTripUsesOwnVersionAndPreservesFollowingBody(){
        var value=ready(first(),AIR);var b=new FriendlyByteBuf(Unpooled.buffer());try{RollingGateWire.writeReady(b,value);assertEquals(4,b.getUnsignedByte(0));assertTrue(b.readableBytes()+17<RollingGatePayload.MAX_BYTES);assertTrue(b.readableBytes()<20000);b.writeLong(982);var decoded=RollingGateWire.readReady(b,D.world(),1);assertEquals(value.arenaCertificate(),decoded.arenaCertificate());assertEquals(AIR.contentIdentity(),decoded.arenaCapture().contentIdentity());assertEquals(RollingGateWire.manifestDigest(value),RollingGateWire.manifestDigest(decoded));assertFalse(decoded.pinned());assertNull(decoded.activeFocus());assertEquals(982,b.readLong());assertFalse(b.isReadable());}finally{b.release();}
    }
    @Test void decodedCaptureOwnsOnePackedArrayAndOldPlusNewFitsTwentyFourKilobytes()throws Exception{
        var b=new FriendlyByteBuf(Unpooled.buffer());try{RollingArenaWire.write(b,first(),AIR);var decoded=RollingArenaWire.read(b,D.world(),1);assertEquals(11664,AIR.declaredBytes());assertEquals(23328,AIR.declaredBytes()+decoded.capture().declaredBytes());assertTrue(AIR.declaredBytes()+decoded.capture().declaredBytes()<=24000);
            var packed=ArenaCapture.class.getDeclaredField("packed");packed.setAccessible(true);assertNotSame(packed.get(AIR),packed.get(decoded.capture()));var base=ArenaCapture.class.getDeclaredField("base");base.setAccessible(true);assertNull(base.get(decoded.capture()));
            assertEquals(1,Arrays.stream(ArenaCapture.class.getDeclaredFields()).filter(f->f.getType()==byte[].class).count());
        }finally{b.release();}
    }
    @Test void corruptPackedMaterialOrCaptureDigestNeverProducesReady(){
        for(int replacement:new int[]{1,3}){var b=new FriendlyByteBuf(Unpooled.buffer());try{RollingGateWire.writeReady(b,ready(first(),AIR));b.setByte(b.writerIndex()-1,replacement);assertThrows(IllegalArgumentException.class,()->RollingGateWire.readReady(b,D.world(),1));}finally{b.release();}}
    }
    @Test void missingTailAndWrongArenaWireVersionFailClosed(){
        var b=new FriendlyByteBuf(Unpooled.buffer());try{RollingGateWire.writeReady(b,ready(first(),AIR));int full=b.writerIndex();for(int cut:new int[]{1,11663,11664}){var shortBody=new FriendlyByteBuf(b.slice(0,full-cut));assertThrows(RuntimeException.class,()->RollingGateWire.readReady(shortBody,D.world(),1));}b.setByte(0,3);assertThrows(IllegalArgumentException.class,()->RollingGateWire.readReady(b,D.world(),1));}finally{b.release();}
    }
    @Test void fullCatalogueCertificateAndPackedBodyAreBothMandatory(){
        var c=first();var empty=new FriendlyByteBuf(Unpooled.buffer(0,0));try{assertThrows(IllegalArgumentException.class,()->RollingArenaWire.write(empty,null,AIR));}finally{empty.release();}var changed=AIR.withMaterial(1,129,1,ArenaCapture.STONE);assertThrows(IllegalArgumentException.class,()->c.verifyCapture(changed));assertThrows(IllegalArgumentException.class,()->new RollingCubeActor.StageReady(D.world(),1,1,A.origin(),ready(c,AIR).versions(),null,false,null,null,List.of(),c,null));
    }
    @Test void everyArenaMetadataStageRejectsNativePinAcquisitionBeforeAdmission(){
        for(var center:List.of(A.origin(),A.origin().offset(10,0,0))){
            var plan=RollingCubePlan.create(A.origin(),center,center,false,true);long lease=65;var old=new ArrayList<RollingCubeActor.CubeLease>();var byPosition=new TreeMap<CubePos,RollingCubeActor.CubeLease>();for(var p:plan.committedOwners()){var l=new RollingCubeActor.CubeLease(p,lease++);old.add(l);byPosition.put(p,l);}for(var p:plan.addedOwners())byPosition.put(p,new RollingCubeActor.CubeLease(p,lease++));var candidate=plan.nextCommittedOwners().stream().map(byPosition::get).toList();
            var m=new RollingCubeActor.StageMetadata(D.world(),1,2,false,A.origin(),center,center,old,candidate,plan.addedOwners(),plan.retiredOwners(),plan.candidateTargets(),false,true,A.origin());
            assertThrows(IllegalArgumentException.class,()->RollingGateWire.validateMetadata(m));
        }
    }
    @Test void arenaManifestBindsCompleteRevisionCaptureAndPublicationScope(){
        var old=ready(first(),AIR);var target=A.positions().getLast();var changed=AIR.withMaterial(31,159,31,ArenaCapture.DIORITE).compact();var next=ready(certificate(2,2,65,Map.of(target,1L),changed),changed);assertNotEquals(RollingGateWire.manifestDigest(old),RollingGateWire.manifestDigest(next));
        var fresh=ready(certificate(2,1,65,Map.of(),AIR),AIR);assertNotEquals(RollingGateWire.manifestDigest(old),RollingGateWire.manifestDigest(fresh));
    }
}
