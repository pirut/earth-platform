package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.ReplacementSetCertificate;
import io.earthplatform.minecraft.enginebridge.ReplacementWorkspaces;
import java.util.Objects;

/**
 * Bounded owner-independent mirror of the server's complete two-workspace authority.
 * Certificate values never retain snapshots, provider callbacks or a Minecraft Level.
 * A proposal grants private preparation only. The owner still needs exact server ALLOW,
 * paired publication and capture equality before activating it.
 */
final class RollingReplacementSetAuthority implements AutoCloseable {
    private final WorldIdentity world;
    private final long epoch, resources;
    private final ReplacementWorkspaces workspaces;
    private final String provider, palette, material, detail, queryPolicy, queryLayout;
    private long highestIndexGeneration=-1;
    private String highestIndexRoot;
    private String store, index;
    private ReplacementSetCertificate committed, proposed, historical;
    private String committedId, proposedId, historicalId;
    private CubePos editing;
    private long highestGeneration, minimumEditRevision=-1;
    private boolean closed;

    RollingReplacementSetAuthority(WorldIdentity world, long epoch, long resources,
            ReplacementWorkspaces workspaces, String provider, String palette, String material, String detail) {
        this(world,epoch,resources,workspaces,provider,palette,material,detail,null,null);
    }
    RollingReplacementSetAuthority(WorldIdentity world, long epoch, long resources,
            ReplacementWorkspaces workspaces, String provider, String palette, String material, String detail,
            String queryPolicy, String queryLayout) {
        if((queryPolicy==null)!=(queryLayout==null))throw new IllegalArgumentException("Complete indexed mirror policy required");
        this.queryPolicy=queryPolicy;this.queryLayout=queryLayout;
        this.world=Objects.requireNonNull(world);this.workspaces=Objects.requireNonNull(workspaces);
        if(epoch<=0||resources<=0)throw new IllegalArgumentException("Positive replacement-set scope required");
        this.epoch=epoch;this.resources=resources;this.provider=Objects.requireNonNull(provider);
        this.palette=Objects.requireNonNull(palette);this.material=Objects.requireNonNull(material);this.detail=Objects.requireNonNull(detail);
    }

    synchronized void propose(ReplacementSetCertificate value) {
        check();Objects.requireNonNull(value);
        if(!world.equals(value.world())||epoch!=value.epoch()||resources!=value.resourceGeneration()
                ||!workspaces.equals(value.workspaces())||!provider.equals(value.providerIdentity())
                ||!palette.equals(value.paletteIdentity())||!material.equals(value.materialPolicy())||!detail.equals(value.detailPolicy())
                ||store!=null&&!store.equals(value.storeIdentity())||index!=null&&!index.equals(value.indexIdentity()))
            throw new IllegalArgumentException("Replacement-set scope differs from the session");
        var query=value.queryAttestation();
        if(queryPolicy==null?query!=null:query==null||!query.completeCatalogue()||!queryPolicy.equals(query.policyIdentity())||!queryLayout.equals(query.layoutIdentity()))
            throw new IllegalArgumentException("Exact COMPLETE indexed mirror scope required");
        if(query!=null){
            query.verifyRegions(value.regions());
            if(query.snapshotGeneration()<highestIndexGeneration || query.snapshotGeneration()==highestIndexGeneration&&!query.snapshotRootIdentity().equals(highestIndexRoot))
                throw new IllegalArgumentException("Stale or ABA indexed snapshot proposal");
        }
        String id=value.identity();
        if(proposed!=null&&!id.equals(proposedId))throw new IllegalStateException("Another exact private proposal is already retained");
        if(id.equals(committedId)){proposed=committed;proposedId=committedId;return;}
        // A refused prospective edit did not alter storage. Its exact historical set may be restored.
        if(editing!=null&&id.equals(historicalId)){if(minimumEditRevision>historical.region(editing).revision())throw new IllegalArgumentException("Durable edit authority cannot roll back after cancellation");proposed=historical;proposedId=historicalId;return;}
        if(id.equals(proposedId))return;
        if(value.generation()<=highestGeneration)throw new IllegalArgumentException("Replayed replacement-set generation");
        if(proposed!=null&&proposed!=committed)throw new IllegalStateException("Only one private set proposal is allowed");
        ReplacementSetCertificate before=historical!=null?historical:committed;
        if(before!=null){
            if(editing==null&&query!=null&&!query.equals(before.queryAttestation()))throw new IllegalArgumentException("Non-edit changed COMPLETE index authority");
            if(editing!=null&&query!=null){
                long oldRevision=before.region(editing).revision(),nextRevision=value.region(editing).revision();
                var previousQuery=before.queryAttestation();
                if(nextRevision==oldRevision){if(!query.equals(previousQuery))throw new IllegalArgumentException("Refused edit changed index authority");}
                else if(nextRevision!=Math.incrementExact(oldRevision)||query.snapshotGeneration()!=Math.incrementExact(previousQuery.snapshotGeneration())||query.snapshotRootIdentity().equals(previousQuery.snapshotRootIdentity()))throw new IllegalArgumentException("Durable edit requires exactly one fresh indexed generation");
            }
            for(var old:before.regions()){
                var next=value.region(old.position());
                if(editing==null){
                    if(!old.captureIdentity().equals(next.captureIdentity())||!sameRevisions(old,next))
                        throw new IllegalArgumentException("Unedited workspace content or durable dependencies changed");
                    continue;
                }
                boolean editedDependencyAdvanced=false;
                for(int i=0;i<old.dependencies().size();i++){
                    var a=old.dependencies().get(i);var b=next.dependencies().get(i);
                    if(!a.position().equals(b.position()))throw new IllegalArgumentException("Edit changed a dependency coordinate");
                    if(a.position().equals(editing)){
                        if(b.revision()!=a.revision()&&b.revision()!=Math.incrementExact(a.revision()))
                            throw new IllegalArgumentException("Edit dependency must retain or advance exactly one revision");
                        editedDependencyAdvanced=b.revision()!=a.revision();
                    }else if(a.revision()!=b.revision())throw new IllegalArgumentException("Edit changed an unrelated durable revision");
                }
                if(editing.equals(old.position())){
                    if(next.revision()<minimumEditRevision)
                        throw new IllegalArgumentException("Durable edit authority cannot roll back");
                }else if(next.revision()!=old.revision())throw new IllegalArgumentException("Neighbor refresh cannot advance its own durable revision");
                if(!old.captureIdentity().equals(next.captureIdentity())
                        &&(!editedDependencyAdvanced||!editing.equals(old.position())&&!workspaces.isAdjacent()))
                    throw new IllegalArgumentException("Changed capture lacks the exact edited dependency advance");
            }
        }
        if(store==null){store=value.storeIdentity();index=value.indexIdentity();}
        if(query!=null){highestIndexGeneration=query.snapshotGeneration();highestIndexRoot=query.snapshotRootIdentity();}
        highestGeneration=value.generation();if(editing!=null)minimumEditRevision=Math.max(minimumEditRevision,value.region(editing).revision());proposed=value;proposedId=id;
    }

    /** Called only after retaining the immutable historical native/far presentation. */
    synchronized void beginEdit(CubePos focus) {
        check();if(!workspaces.contains(focus))throw new IllegalArgumentException("Unknown edit workspace");
        if(committed==null||editing!=null||proposed!=null&&proposed!=committed)
            throw new IllegalStateException("Edit requires one committed complete set");
        historical=committed;historicalId=committedId;committed=null;committedId=null;proposed=null;proposedId=null;editing=focus;minimumEditRevision=historical.region(focus).revision();
    }

    synchronized void cancelProposal(){check();proposed=null;proposedId=null;}

    /** Far commit and historical presentation retirement must happen before this activation. */
    synchronized void commit(String identity){
        check();if(identity==null||proposed==null||!identity.equals(proposedId))
            throw new IllegalStateException("Exact complete prepared replacement set required");
        committed=proposed;committedId=proposedId;proposed=null;proposedId=null;historical=null;historicalId=null;editing=null;minimumEditRevision=-1;
    }
    synchronized boolean isCurrent(String identity){return !closed&&identity!=null&&(identity.equals(committedId)||identity.equals(proposedId));}
    synchronized boolean canPresent(String identity){return !closed&&identity!=null&&(identity.equals(committedId)||identity.equals(proposedId)||identity.equals(historicalId));}
    synchronized ReplacementSetCertificate committed(){return committed;}
    synchronized int retainedSets(){return (committed==null?0:1)+(proposed==null||proposed==committed?0:1)+(historical==null||historical==committed||historical==proposed?0:1);}
    private static boolean sameRevisions(ReplacementSetCertificate.Region a,ReplacementSetCertificate.Region b){
        for(int i=0;i<a.dependencies().size();i++)if(!a.dependencies().get(i).position().equals(b.dependencies().get(i).position())
                ||a.dependencies().get(i).revision()!=b.dependencies().get(i).revision())return false;
        return true;
    }
    private void check(){if(closed)throw new IllegalStateException("Replacement-set mirror closed");}
    @Override public synchronized void close(){closed=true;committed=proposed=historical=null;committedId=proposedId=historicalId=null;editing=null;minimumEditRevision=-1;highestIndexGeneration=-1;highestIndexRoot=null;}
}
