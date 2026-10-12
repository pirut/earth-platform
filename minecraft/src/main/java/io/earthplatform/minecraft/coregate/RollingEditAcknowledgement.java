package io.earthplatform.minecraft.coregate;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import java.util.List;
import java.util.Objects;
/** Pure reconstruction of a new pair manifest after an exact durable or safely refused edit. */
final class RollingEditAcknowledgement {
    private RollingEditAcknowledgement(){}
    static RollingCubeActor.StageReady ready(RollingCubeActor.StageReady prior,CubePos focus,
            List<RollingCubeActor.CubeVersion> versions,RollingCubeActor.EditAck ack){
        Objects.requireNonNull(prior);Objects.requireNonNull(focus);Objects.requireNonNull(ack);
        var request=ack.request();
        if(!prior.world().equals(request.world())||prior.epoch()!=request.epoch()||!focus.equals(request.position())
                ||prior.replacementSet()!=null&&!focus.equals(prior.activeFocus()))throw new IllegalArgumentException("Edit acknowledgement scope mismatch");
        if(ack.status()==RollingCubeActor.EditStatus.SAVE_FAILED||ack.status()==RollingCubeActor.EditStatus.CLOSING)
            throw new IllegalStateException("Uncertain durability must remain fenced");
        if(versions.size()!=prior.versions().size())throw new IllegalArgumentException("Edit changed manifest owner count");
        boolean seen=false;
        for(int i=0;i<versions.size();i++){
            var old=prior.versions().get(i);var next=versions.get(i);
            if(!old.position().equals(next.position())||old.leaseGeneration()!=next.leaseGeneration())throw new IllegalArgumentException("Edit changed native lease");
            long expected=old.revision();
            if(old.position().equals(focus)){
                seen=true;if(old.leaseGeneration()!=request.leaseGeneration()||old.revision()!=request.expectedRevision())throw new IllegalArgumentException("Stale edit acknowledgement");
                if(ack.applied()){expected=Math.incrementExact(expected);if(ack.revision()!=expected)throw new IllegalArgumentException("Unexpected durable revision");}
            }
            if(next.revision()!=expected)throw new IllegalArgumentException("Edit changed an unacknowledged revision");
        }
        if(!seen)throw new IllegalArgumentException("Focused native cube absent");
        var set=prior.replacementSet()!=null&&!ack.applied()?prior.replacementSet():ack.replacementSet();
        var captures=prior.replacementSet()!=null&&!ack.applied()?prior.replacementCaptures():ack.replacementCaptures();
        return new RollingCubeActor.StageReady(prior.world(),prior.epoch(),prior.generation(),prior.candidateCenter(),versions,
                prior.earthReplacementCapture()!=null&&!ack.applied()?prior.replacementCertificate():ack.replacementCertificate(),prior.pinned(),set==null?null:focus,set,captures,
                prior.arenaCertificate()!=null&&!ack.applied()?prior.arenaCertificate():ack.arenaCertificate(),
                prior.arenaCertificate()!=null&&!ack.applied()?prior.arenaCapture():ack.arenaCapture(),
                prior.earthReplacementCapture()!=null&&!ack.applied()?prior.earthReplacementCapture():ack.earthReplacementCapture());
    }
}
