package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArenaCertificateTest {
    @TempDir Path dir;
    final ArenaDefinition arena=ArenaDefinition.FIXED;
    final WorldIdentity world=new WorldIdentity(42,"arena-test","v1","source-v1","palette-v1");
    final RollingCubeActor.ReplacementPolicy policy=new RollingCubeActor.ReplacementPolicy("source-v1","opaque-v1","arena-v1",1,RollingEditPolicy.FULL_CUBE_V1);
    final String palette="a".repeat(64),scope="b".repeat(64),layout="layout-v1";
    List<RollingCubeActor.CubeVersion> versions(){var result=new ArrayList<RollingCubeActor.CubeVersion>();for(int i=0;i<64;i++)result.add(new RollingCubeActor.CubeVersion(arena.dependencies().get(i),i+1,0));return List.copyOf(result);}
    ArenaCertificate certificate(ArenaRevisionLedger ledger,ArenaCapture capture,List<RollingCubeActor.CubeVersion> versions,long epoch){return new ArenaCertificate(world,epoch,1,ledger.storeIdentity(),ledger.indexIdentity(),policy.providerIdentity(),palette,policy.materialPolicy(),policy.detailPolicy(),1,arena,versions,capture.contentIdentity(),ledger.attestation());}
    @Test void allEightRevisionsDeriveFromOneCanonicalSixtyFourVector()throws Exception{
        var capture=ArenaCaptureTest.filled(0);var ledger=ArenaRevisionLedger.open(dir,arena,scope,layout);ledger.sealCapture(capture);var versions=versions();var c=certificate(ledger,capture,versions,1);for(var p:arena.positions())assertEquals(0,c.revision(p));c.verifyCapture(capture);assertEquals(8,c.queryAttestation().resultCount());assertThrows(IllegalArgumentException.class,()->c.revision(new CubePos(2,8,0)));
        assertThrows(IllegalArgumentException.class,()->certificate(ledger,capture,versions.subList(0,63),1));
        var duplicate=new ArrayList<>(versions);duplicate.set(0,duplicate.get(1));assertThrows(IllegalArgumentException.class,()->certificate(ledger,capture,duplicate,1));
        var lease=new ArrayList<>(versions);lease.set(0,new RollingCubeActor.CubeVersion(lease.get(0).position(),2,0));assertThrows(IllegalArgumentException.class,()->certificate(ledger,capture,lease,1));
        var halo=new ArrayList<>(versions);halo.set(0,new RollingCubeActor.CubeVersion(halo.get(0).position(),1,1));assertThrows(IllegalArgumentException.class,()->certificate(ledger,capture,halo,1));
        var modified=capture.withMaterial(0,128,0,2);assertThrows(IllegalArgumentException.class,()->c.verifyCapture(modified));assertThrows(IllegalArgumentException.class,()->certificate(ledger,modified,versions,1));
        var restart=certificate(ledger,capture,versions.stream().map(v->new RollingCubeActor.CubeVersion(v.position(),v.leaseGeneration()+64,v.revision())).toList(),2);assertEquals(c.durableIdentity(),restart.durableIdentity());assertNotEquals(c.identity(),restart.identity());
    }
    @Test void issuerRequiresExactCurrentCompleteQueryAndRevokesHistoricalPublication()throws Exception{
        var capture=ArenaCaptureTest.filled(0);var ledger=ArenaRevisionLedger.open(dir,arena,scope,layout);ledger.sealCapture(capture);var authority=new NativeArenaAuthority(world,1,arena,palette,policy,ledger);authority.bootstrap(versions(),capture);var initial=authority.prepare();assertNotNull(initial);assertFalse(authority.current(initial.identity()));assertTrue(authority.canCommit(initial.identity()));assertFalse(authority.commit("f".repeat(64)));assertTrue(authority.commit(initial.identity()));assertTrue(authority.current(initial.identity()));
        var next=capture.withMaterial(31,159,31,2);var position=CubePos.fromBlock(31,159,31);authority.beforeEdit(position);assertFalse(authority.canCommit(initial.identity()));assertFalse(authority.current(initial.identity()));ledger.begin(position,0,next);assertNull(ledger.attestation());ledger.confirm(position,1);authority.completeEdit(new RollingCubeActor.CubeVersion(position,1000,1),next);var issued=authority.prepare();assertNotEquals(initial.identity(),issued.identity());assertEquals(1,issued.revision(position));for(var p:arena.positions())if(!p.equals(position))assertEquals(0,issued.revision(p));assertFalse(authority.commit(initial.identity()));assertTrue(authority.commit(issued.identity()));assertTrue(authority.current(issued.identity()));
    }
    @Test void positiveAdmissionCannotBeReusedForOtherContentDefinitionSourceOrLayout(){var c=ArenaCaptureTest.filled(0);var r=new ArenaAdmission.Receipt(true,"",arena.identity(),c.contentIdentity(),"source-v1",layout);r.verify(c,"source-v1",layout);assertThrows(IllegalArgumentException.class,()->r.verify(c.withMaterial(0,128,0,2),"source-v1",layout));assertThrows(IllegalArgumentException.class,()->r.verify(c,"source-v2",layout));assertThrows(IllegalArgumentException.class,()->r.verify(c,"source-v1","other-layout"));}
}
