package io.earthplatform.minecraft.lod;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.lod.DhArenaTestFixtures.*;

/** Equal local capture bytes never authorize an old, different surrounding terrain policy. */
class DhArenaPolicyRevisionTest {
    static final String OLD_SOURCE="499dbb4e528c7f215fb730b67761e8b944c8236d8d0e84ed1ae87a41a9610da9";
    static final String OLD_LAYOUT="arena-4096-v1:square-radius=4096:center-align=128:snap=256:rings=[Ring[radiusBlocks=512, cellSize=32, tileSize=512], Ring[radiusBlocks=1024, cellSize=64, tileSize=1024], Ring[radiusBlocks=4096, cellSize=128, tileSize=4096]]:fine=1:collar=1:fine-tile=32:native-y=[-11008,8864):full-xyz-clip=true:complete-footprint-required=true";
    static final String ORIGINAL_CAPTURE="65b7f28e0822fc1ba1084944421100cb3079c981c2f3dc061d27fa09db931247";

    static ArenaCertificate certificateFor(ArenaCapture capture,WorldIdentity world,String source,String layout) {
        var prior=certificate(capture,1,0);
        var policy=new RollingCubeActor.ReplacementPolicy(prior.providerIdentity(),prior.materialPolicy(),prior.detailPolicy(),prior.resourceGeneration(),RollingEditPolicy.FULL_CUBE_V1);
        String scope=ArenaQueryAttestation.policyIdentity(world,prior.paletteIdentity(),policy,DEF,source,layout);
        var entries=DEF.positions().stream().map(p->new ArenaReplacementIndex.Entry(p,0,scope,capture.contentIdentity())).toList();
        var q=prior.queryAttestation();
        var query=new ArenaQueryAttestation(q.storeIdentity(),q.indexIdentity(),q.snapshotGeneration(),q.snapshotRootIdentity(),DEF.identity(),layout,scope,ArenaReplacementIndex.resultIdentity(entries),8);
        return new ArenaCertificate(world,prior.epoch(),prior.resourceGeneration(),prior.storeIdentity(),prior.indexIdentity(),prior.providerIdentity(),prior.paletteIdentity(),prior.materialPolicy(),prior.detailPolicy(),prior.generation(),DEF,prior.dependencies(),capture.contentIdentity(),query);
    }

    @Test void localArenaCaptureAndSeamCellsStayExactWhileSourceAndLayoutChange() {
        var c=capture();assertEquals(ORIGINAL_CAPTURE,c.contentIdentity());
        assertNotEquals(OLD_SOURCE,SOURCE.identity());assertNotEquals(OLD_LAYOUT,DhLodLayout.ARENA_4096.identity());
        for(int x:new int[]{15,16})for(int y:new int[]{143,144})for(int z:new int[]{15,16})assertEquals(ArenaTerrainSource.STONE,c.materialAt(x,y,z));
        assertEquals(ArenaTerrainSource.AIR,SOURCE.materialAt(8,145,12));
        assertEquals(ArenaTerrainSource.STONE,SOURCE.materialAt(8,144,12));
    }

    @Test void completeOldSourceCertificateCannotAuthorizeIdenticalArenaCapture() {
        var c=capture();var g=new Guard();
        var oldWorld=new WorldIdentity(WORLD.seed(),WORLD.generatorId(),WORLD.generatorVersion(),OLD_SOURCE,WORLD.blockPaletteVersion());
        var cert=certificateFor(c,oldWorld,OLD_SOURCE,DhLodLayout.ARENA_4096.identity());
        cert.verifyCapture(c);
        assertThrows(IllegalArgumentException.class,()->new DhArenaInput(SOURCE,cert,c,g));assertEquals(0,g.calls);
    }

    @Test void completeOldLayoutCertificateCannotAuthorizeCurrentSource() {
        var c=capture();var g=new Guard();var cert=certificateFor(c,WORLD,SOURCE.identity(),OLD_LAYOUT);cert.verifyCapture(c);
        assertThrows(IllegalArgumentException.class,()->new DhArenaInput(SOURCE,cert,c,g));assertEquals(0,g.calls);
    }

    @Test void prospectiveAdmissionReceiptBindsNewSourceAndLayoutIndependentlyOfCapture() {
        var c=capture();var r=new DhArenaAdmission(SOURCE).assess(c);assertTrue(r.admitted());
        r.verify(c,SOURCE.identity(),DhLodLayout.ARENA_4096.identity());
        assertThrows(IllegalArgumentException.class,()->r.verify(c,OLD_SOURCE,DhLodLayout.ARENA_4096.identity()));
        assertThrows(IllegalArgumentException.class,()->r.verify(c,SOURCE.identity(),OLD_LAYOUT));
        var in=input(new Guard(),1,0,c);assertEquals(SOURCE.identity(),in.data().source().identity());
        assertEquals(DhLodLayout.ARENA_4096.identity(),in.certificate().queryAttestation().layoutIdentity());
    }
}
