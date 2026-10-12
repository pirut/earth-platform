package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.*;
import java.util.Objects;

/** Complete arena mirror. Proposal permits preparation only; ALLOW and exact pair commit stay separate. */
final class RollingArenaAuthority implements AutoCloseable {
    private final RollingSessionDescriptor descriptor;
    private final long epoch, resources;
    private final String policy;
    private ArenaCertificate committed, proposed, historical;
    private String committedId, proposedId, historicalId, store, index, highestRoot;
    private long highestGeneration, highestIndexGeneration=-1, minimumEditRevision=-1;
    private CubePos editing;
    private boolean closed;
    RollingArenaAuthority(RollingSessionDescriptor descriptor,long epoch,long resources) {
        if(!descriptor.arenaMode()||epoch<=0||resources<=0)throw new IllegalArgumentException("Exact positive arena scope required");
        this.descriptor=descriptor;this.epoch=epoch;this.resources=resources;policy=RollingArenaQueryGuard.policy(descriptor);
    }
    synchronized void propose(ArenaCertificate value) {
        check();Objects.requireNonNull(value);RollingArenaQueryGuard.require(descriptor,value);
        if(value.epoch()!=epoch||value.resourceGeneration()!=resources||!value.world().equals(descriptor.world())
                ||store!=null&&!store.equals(value.storeIdentity())||index!=null&&!index.equals(value.indexIdentity()))throw new IllegalArgumentException("Foreign arena session/store");
        var query=value.queryAttestation();
        if(!policy.equals(query.policyIdentity())||query.snapshotGeneration()<highestIndexGeneration
                ||query.snapshotGeneration()==highestIndexGeneration&&!query.snapshotRootIdentity().equals(highestRoot))throw new IllegalArgumentException("Stale or ABA arena query");
        String id=value.identity();
        if(proposed!=null&&!id.equals(proposedId))throw new IllegalStateException("Another arena proposal is retained");
        if(id.equals(committedId)){proposed=committed;proposedId=id;return;}
        if(editing!=null&&id.equals(historicalId)){
            if(minimumEditRevision>historical.revision(editing))throw new IllegalArgumentException("Arena durability cannot roll back");
            proposed=historical;proposedId=id;return;
        }
        if(id.equals(proposedId))return;
        if(value.generation()<=highestGeneration)throw new IllegalArgumentException("Replayed arena generation");
        ArenaCertificate before=historical!=null?historical:committed;
        if(before!=null){
            boolean advanced=false;
            for(int i=0;i<64;i++){
                var old=before.dependencies().get(i);var next=value.dependencies().get(i);
                if(!old.position().equals(next.position()))throw new IllegalArgumentException("Arena dependency coordinate changed");
                if(old.position().equals(editing)){
                    if(next.revision()!=old.revision()&&next.revision()!=Math.incrementExact(old.revision()))throw new IllegalArgumentException("Arena edit revision must advance exactly once");
                    if(next.revision()<minimumEditRevision)throw new IllegalArgumentException("Arena durability cannot roll back");
                    advanced=next.revision()!=old.revision();
                }else if(next.revision()!=old.revision())throw new IllegalArgumentException("Unrelated arena durable revision changed");
            }
            if(advanced){
                if(query.snapshotGeneration()!=Math.incrementExact(before.queryAttestation().snapshotGeneration())
                        ||query.snapshotRootIdentity().equals(before.queryAttestation().snapshotRootIdentity()))throw new IllegalArgumentException("Applied edit lacks new complete arena content/index");
            }else if(!before.captureIdentity().equals(value.captureIdentity())||!before.queryAttestation().equals(query))throw new IllegalArgumentException("Movement/refusal changed arena content/index");
        }
        store=value.storeIdentity();index=value.indexIdentity();highestGeneration=value.generation();highestIndexGeneration=query.snapshotGeneration();highestRoot=query.snapshotRootIdentity();
        if(editing!=null)minimumEditRevision=Math.max(minimumEditRevision,value.revision(editing));proposed=value;proposedId=id;
    }
    synchronized void beginEdit(CubePos target){check();if(!descriptor.arenaDefinition().contains(target)||committed==null||editing!=null||proposed!=null&&proposed!=committed)throw new IllegalStateException("Arena edit requires one exact committed union");historical=committed;historicalId=committedId;committed=proposed=null;committedId=proposedId=null;editing=target;minimumEditRevision=historical.revision(target);}
    synchronized void cancelProposal(){check();proposed=null;proposedId=null;}
    synchronized void commit(String id){check();if(proposed==null||id==null||!id.equals(proposedId))throw new IllegalStateException("Exact prepared arena identity required");committed=proposed;committedId=id;proposed=historical=null;proposedId=historicalId=null;editing=null;minimumEditRevision=-1;}
    synchronized boolean isCurrent(String id){return !closed&&id!=null&&(id.equals(committedId)||id.equals(proposedId));}
    synchronized boolean canPresent(String id){return !closed&&id!=null&&(id.equals(committedId)||id.equals(proposedId)||id.equals(historicalId));}
    synchronized int retainedSets(){return(committed==null?0:1)+(proposed==null||proposed==committed?0:1)+(historical==null||historical==committed||historical==proposed?0:1);}
    private void check(){if(closed)throw new IllegalStateException("Arena authority closed");}
    @Override public synchronized void close(){closed=true;committed=proposed=historical=null;committedId=proposedId=historicalId=null;editing=null;}
}
