package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCubeActor;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.scene.SceneCube;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.*;

/** CPU-only client publication contract used by the actual client, independently testable without GL. */
public final class CoreGateReplica implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final CoreGateProfile profile;private final WorldIdentity world;private final long epoch;private final EnginePaletteContext palette;
    private final CubeStreamAdmission admission;private final Map<CubePos,SceneCube> cubes=new LinkedHashMap<>();
    private boolean paused,closed,replacementAccepted;private long ackRevision=-1,barrierBase=-1;
    public CoreGateReplica(WorldIdentity world,long epoch,EnginePaletteContext palette,List<CubePos> positions,List<Long> leases,List<CubePos> targets){
        this(world,epoch,palette,positions,leases,targets,CoreGateProfile.BASE);
    }
    public CoreGateReplica(WorldIdentity world,long epoch,EnginePaletteContext palette,List<CubePos> positions,List<Long> leases,List<CubePos> targets,CoreGateProfile profile){
        this.profile=Objects.requireNonNull(profile);
        if(epoch<=0||!positions.equals(profile.owners())||!targets.equals(profile.meshTargets())||leases.size()!=125)
            throw new IllegalArgumentException("Invalid bounded fixed3D replica metadata");
        this.world=Objects.requireNonNull(world);this.epoch=epoch;this.palette=Objects.requireNonNull(palette);
        admission=new CubeStreamAdmission(world,epoch,125);var actual=admission.replaceInterest(positions).requested();
        for(int i=0;i<actual.size();i++)if(actual.get(i).generation()!=leases.get(i))throw new IllegalArgumentException("Authoritative lease mismatch");
    }
    public CubeStreamAdmission admission(){check();return admission;}
    /** Read-only map view: mutable state stays on its client owner. */
    public Map<CubePos,SceneCube> cubes(){check();return Collections.unmodifiableMap(cubes);}
    public void accept(EngineCubeSnapshot snapshot){
        check();Objects.requireNonNull(snapshot);if(cubes.containsKey(snapshot.position())){if(!paused)throw new IllegalStateException("Replacement lacks durable edit barrier");if(!snapshot.position().equals(profile.target())||replacementAccepted||snapshot.revision()!=Math.incrementExact(barrierBase))throw new IllegalArgumentException("Replacement does not match this single-edit barrier");}
        var result=admission.admit(new CubeStreamAdmission.Header(snapshot.world(),snapshot.epoch(),snapshot.position(),snapshot.leaseGeneration(),snapshot.revision()));
        if(!result.accepted())throw new IllegalArgumentException("Core cube rejected: "+result.rejection());
        final SceneCube cube;
        try{cube=new SceneCube(snapshot,palette,profile.renderEnvelope());}catch(RuntimeException e){admission.cancel(result.token());throw e;}
        if(admission.commit(result.token(),()->cubes.put(snapshot.position(),cube))!=CubeStreamAdmission.Rejection.NONE)throw new IllegalStateException("Cube commit rejected");
        if(paused)replacementAccepted=true;
    }
    public void beginEdit(){check();if(paused||cubes.size()!=125)throw new IllegalStateException("Edit barrier needs a complete known box");barrierBase=revision();replacementAccepted=false;paused=true;}
    public void acknowledge(String status,long revision){check();if(!paused)throw new IllegalStateException("Ack without barrier");
        var result=NativeCubeActor.EditStatus.valueOf(status);
        if(result==NativeCubeActor.EditStatus.APPLIED){if(!replacementAccepted||revision!=Math.incrementExact(barrierBase)||revision()!=revision)throw new IllegalStateException("Durable ack precedes exact snapshot");ackRevision=revision;}
        if(result==NativeCubeActor.EditStatus.SAVE_FAILED)throw new IllegalStateException("Failed durability leaves replica fenced");
        if(result!=NativeCubeActor.EditStatus.APPLIED&&replacementAccepted)throw new IllegalStateException("Changed snapshot lacks successful acknowledgement");
        paused=false;
    }
    public boolean paused(){check();return paused;}public long acknowledgedRevision(){check();return ackRevision;}
    public long revision(){check();var cube=cubes.get(profile.target());return cube==null?-1:cube.snapshot().revision();}
    private void check(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Replica owner thread required");if(closed)throw new IllegalStateException("Replica closed");}
    public void close(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Replica owner required");if(closed)return;closed=true;paused=true;admission.close();cubes.clear();}
}
