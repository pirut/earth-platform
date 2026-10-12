package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Metadata-only issuer. Historical capture leases certify content, never residency or edit permission. */
final class NativeArenaAuthority {
    private final WorldIdentity world; private final long epoch;
    private final ArenaDefinition definition; private final String palette;
    private final RollingCubeActor.ReplacementPolicy policy; private final ArenaRevisionLedger ledger;
    private List<RollingCubeActor.CubeVersion> dependencies;
    private String captureIdentity;
    private ArenaCertificate issued,committed,editBase;
    private CubePos editing;
    private long generation=(java.util.UUID.randomUUID().getMostSignificantBits()&0x3fffffffffffffffL)+1;
    NativeArenaAuthority(WorldIdentity world,long epoch,ArenaDefinition definition,String palette,RollingCubeActor.ReplacementPolicy policy,ArenaRevisionLedger ledger){this.world=world;this.epoch=epoch;this.definition=definition;this.palette=palette;this.policy=policy;this.ledger=ledger;}
    synchronized void bootstrap(List<RollingCubeActor.CubeVersion> versions,ArenaCapture capture)throws IOException{
        if(dependencies!=null||issued!=null||committed!=null)throw new IllegalStateException("Arena reconstruction seals only once");
        if(!versions.stream().map(RollingCubeActor.CubeVersion::position).toList().equals(definition.dependencies()))throw new IllegalArgumentException("Complete canonical64 reconstruction required");
        ledger.verifyCapture(capture,versions);dependencies=List.copyOf(versions);captureIdentity=capture.contentIdentity();
    }
    synchronized ArenaCertificate prepare(){
        if(dependencies==null||editing!=null)return null;var query=ledger.attestation();if(query==null){revoke();return null;}
        if(committed!=null){if(query.equals(committed.queryAttestation()))return committed;revoke();return null;}
        if(issued==null){issued=new ArenaCertificate(world,epoch,policy.resourceGeneration(),ledger.storeIdentity(),ledger.indexIdentity(),policy.providerIdentity(),palette,policy.materialPolicy(),policy.detailPolicy(),generation,definition,dependencies,captureIdentity,query);generation=Math.incrementExact(generation);}return issued;
    }
    synchronized boolean canCommit(String identity){return identity!=null&&issued!=null&&identity.equals(issued.identity())&&editing==null&&ledger.queryCurrent(issued.queryAttestation());}
    synchronized boolean commit(String identity){if(!canCommit(identity))return false;committed=issued;return true;}
    synchronized boolean current(String identity){return committed!=null&&Objects.equals(identity,committed.identity())&&editing==null&&ledger.queryCurrent(committed.queryAttestation());}
    synchronized ArenaCertificate committed(){return committed;}
    synchronized void beforeEdit(CubePos position){if(!definition.contains(position)||committed==null||editing!=null)throw new IllegalStateException("Exact committed arena required before edit");editBase=committed;editing=position;revoke();}
    synchronized void completeEdit(RollingCubeActor.CubeVersion changed,ArenaCapture capture)throws IOException{
        if(editBase==null||!changed.position().equals(editing)||changed.revision()!=Math.addExact(editBase.revision(editing),1))throw new IllegalStateException("Exact pending arena transaction required");
        var updated=editBase.dependencies().stream().map(v->v.position().equals(changed.position())?changed:v).toList();ledger.verifyCapture(capture,updated);
        // Construct and validate a complete candidate before changing the historical content bindings.
        var checked=new ArenaCertificate(world,epoch,policy.resourceGeneration(),ledger.storeIdentity(),ledger.indexIdentity(),policy.providerIdentity(),palette,policy.materialPolicy(),policy.detailPolicy(),generation,definition,updated,capture.contentIdentity(),Objects.requireNonNull(ledger.attestation()));checked.verifyCapture(capture);
        dependencies=updated;captureIdentity=capture.contentIdentity();editing=null;editBase=null;issued=checked;committed=null;generation=Math.incrementExact(generation);
    }
    synchronized void revoke(){issued=null;committed=null;}
}
