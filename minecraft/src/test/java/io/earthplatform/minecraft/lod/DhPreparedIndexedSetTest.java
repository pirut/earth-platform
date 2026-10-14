package io.earthplatform.minecraft.lod;

import io.earthplatform.minecraft.enginebridge.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Complete index authority uses existing two-region geometry and strict prepare/ALLOW semantics. */
@Timeout(30)
class DhPreparedIndexedSetTest {
    static final long MIN=ReplacementQueryAttestation.DOMAIN_MIN,MAX=ReplacementQueryAttestation.DOMAIN_MAX;
    static final String POLICY="b".repeat(64);
    static ReplacementSetCertificate base(){return DhPreparedAdjacentSetTest.certificate(DhPreparedAdjacentSetTest.captures(),1,0,0);}
    static ReplacementQueryAttestation query(long generation,String root,String layout,long min,long max){
        var c=base();var entries=c.regions().stream().map(r->new PagedReplacementIndex.Entry(r.position(),r.revision(),POLICY,r.captureIdentity())).toList();
        return new ReplacementQueryAttestation(c.storeIdentity(),c.indexIdentity(),generation,root,min,min,min,max,max,max,
                layout,POLICY,PagedReplacementIndex.resultIdentity(entries),2);
    }
    static ReplacementQueryAttestation query(long generation,String root){return query(generation,root,DhLodLayout.LONG_4096.identity(),MIN,MAX);}
    static ReplacementSetCertificate cert(ReplacementQueryAttestation q){var c=base();return new ReplacementSetCertificate(c.world(),c.epoch(),c.resourceGeneration(),c.storeIdentity(),c.indexIdentity(),c.providerIdentity(),c.paletteIdentity(),c.materialPolicy(),c.detailPolicy(),c.generation(),c.regions(),q);}
    @Test void resultDigestCountAndIndexMismatchCannotMintAnInput(){
        var q=query(1,"d".repeat(64));
        assertThrows(IllegalArgumentException.class,()->cert(new ReplacementQueryAttestation(q.storeIdentity(),q.indexIdentity(),1,q.snapshotRootIdentity(),MIN,MIN,MIN,MAX,MAX,MAX,q.layoutIdentity(),POLICY,"0".repeat(64),2)));
        assertThrows(IllegalArgumentException.class,()->new ReplacementQueryAttestation(q.storeIdentity(),q.indexIdentity(),1,q.snapshotRootIdentity(),MIN,MIN,MIN,MAX,MAX,MAX,q.layoutIdentity(),POLICY,q.resultDigest(),3));
        assertThrows(IllegalArgumentException.class,()->cert(new ReplacementQueryAttestation(q.storeIdentity(),"0".repeat(64),1,q.snapshotRootIdentity(),MIN,MIN,MIN,MAX,MAX,MAX,q.layoutIdentity(),POLICY,q.resultDigest(),2)));
    }

}
