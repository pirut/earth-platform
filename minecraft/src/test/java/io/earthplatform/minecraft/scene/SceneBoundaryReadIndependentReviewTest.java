package io.earthplatform.minecraft.scene;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Minecraft getter/occlusion calls plus strict publication jobs. No synthetic mesh oracle or GL. */
class SceneBoundaryReadIndependentReviewTest {
    static final WorldIdentity WORLD=new WorldIdentity(8,"boundary-read-review","1","analytic","native");
    static EnginePaletteContext palette;
    @BeforeAll static void registry(){
        var b=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        b.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(b.freeze());
    }
    private static EngineCubeSnapshot snapshot(CubePos p,long lease,long revision,BlockPos hole){
        var section=EngineCubeSection.uniform(palette,Blocks.STONE.defaultBlockState());
        if(hole!=null)section.set(hole.getX()&15,hole.getY()&15,hole.getZ()&15,Blocks.AIR.defaultBlockState());
        return EngineCubeSnapshot.capture(p,WORLD,1,lease,revision,section);
    }
    private static BlockPos pos(CubePos p,int x,int y,int z){return new BlockPos(Math.toIntExact(p.minBlockX()+x),Math.toIntExact(p.minBlockY()+y),Math.toIntExact(p.minBlockZ()+z));}
    private static SceneCube cube(EngineCubeSnapshot s){return new SceneCube(s,palette);}
    private static void commit(CubeStreamAdmission gate,EngineCubeSnapshot s){
        var a=gate.admit(new CubeStreamAdmission.Header(WORLD,1,s.position(),s.leaseGeneration(),s.revision()));assertTrue(a.accepted());
        assertEquals(CubeStreamAdmission.Rejection.NONE,gate.commit(a.token(),()->{}));
    }
    @Test void all26SignedBoundaryHaloCategoriesRecordActualNativeAndCosmeticQueriesThenRejectChangedRead(){
        for(var pin:List.of(new CubePos(-1,-1,-1),new CubePos(4,100,5))) {
            int count=0;
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
                if(dx==0&&dy==0&&dz==0)continue;var rendered=pin.offset(dx,dy,dz);
                var boundary=pos(pin,dx<0?0:dx>0?15:7,dy<0?0:dy>0?15:7,dz<0?0:dz>0?15:7);
                var original=cube(snapshot(pin,1,0,null));var owner=cube(snapshot(rendered,2,0,null));
                var source=new SceneNeighborhood(Map.of(pin,original,rendered,owner));var recorder=new SceneReadFootprint.Recorder();
                var captured=new java.util.HashSet<CubePos>();
                var reads=source.tracking(c->{SceneReadFootprint.requireHaloOwner(rendered,c.snapshot().position());captured.add(c.snapshot().position());},recorder);
                assertSame(Blocks.STONE.defaultBlockState(),reads.getBlockState(boundary));reads.getFluidState(boundary);reads.getBlockEntity(boundary);reads.getModelData(boundary);
                assertEquals(15,reads.getBrightness(LightLayer.SKY,boundary));assertEquals(15,reads.getBrightness(LightLayer.BLOCK,boundary));assertEquals(11,reads.getRawBrightness(boundary,4));
                assertEquals(SceneNeighborhood.COSMETIC_GRASS_TINT,reads.getBlockTint(boundary,(b,x,z)->{throw new AssertionError("Cosmetic tint must not invent a source-biome sample");}));
                var mutable=new BlockPos.MutableBlockPos(boundary.getX(),boundary.getY(),boundary.getZ());reads.getBlockState(mutable);mutable.move(Direction.UP);
                var footprint=recorder.seal();assertEquals(Set.of(pin),captured);assertEquals(Set.of(pin),footprint.owners());assertEquals(1,footprint.positionCount());
                assertTrue(footprint.validate(Set.of(pin),source::replay).matches());
                var changed=new SceneNeighborhood(Map.of(pin,cube(snapshot(pin,1,1,boundary)),rendered,owner));
                assertFalse(footprint.validate(Set.of(pin),changed::replay).matches(),"Changed actual boundary input must rebuild: "+rendered);
                count++;
            }
            assertEquals(26,count);
        }
    }
    @Test void removingEitherFaceBoundaryExposesTheNeighborOwnedNativeFaceAndPoisonsOldBinding(){
        var pin=new CubePos(-1,-1,-1);
        for(var direction:Direction.values()) {
            var boundary=pos(pin,direction.getStepX()<0?0:direction.getStepX()>0?15:7,direction.getStepY()<0?0:direction.getStepY()>0?15:7,direction.getStepZ()<0?0:direction.getStepZ()>0?15:7);
            var neighborBlock=boundary.relative(direction);var neighbor=CubePos.fromBlock(neighborBlock.getX(),neighborBlock.getY(),neighborBlock.getZ());
            var gate=new CubeStreamAdmission(WORLD,1,2);var leases=gate.replaceInterest(List.of(pin,neighbor)).requested();var snapshots=new HashMap<CubePos,EngineCubeSnapshot>();
            for(var lease:leases){var s=snapshot(lease.position(),lease.generation(),0,null);commit(gate,s);snapshots.put(lease.position(),s);}
            try(var guard=new RenderPublicationGuard(gate)) {
                var oldPin=guard.prepare(snapshots.get(pin));var nativeOwner=guard.prepare(snapshots.get(neighbor));
                var old=new SceneNeighborhood(Map.of(pin,cube(snapshots.get(pin)),neighbor,cube(snapshots.get(neighbor))));
                var recorder=new SceneReadFootprint.Recorder();var reads=old.tracking(c->assertTrue(guard.isCurrent(c.snapshot().position().equals(pin)?oldPin:nativeOwner)),recorder);
                assertFalse(Block.shouldRenderFace(Blocks.STONE.defaultBlockState(),reads,neighborBlock,direction.getOpposite(),boundary));
                var footprint=recorder.seal();var replacement=snapshot(pin,snapshots.get(pin).leaseGeneration(),1,boundary);commit(gate,replacement);
                assertTrue(guard.isCurrent(nativeOwner));assertFalse(guard.isCurrent(oldPin));
                assertFalse(guard.drawIfCurrent(List.of(nativeOwner,oldPin),()->{throw new AssertionError("Stale halo must fence actual draw");}));
                var next=new SceneNeighborhood(Map.of(pin,cube(replacement),neighbor,cube(snapshots.get(neighbor))));
                assertFalse(footprint.validate(Set.of(pin),next::replay).matches());
                assertTrue(Block.shouldRenderFace(Blocks.STONE.defaultBlockState(),next,neighborBlock,direction.getOpposite(),boundary),"Revealed face belongs to "+neighbor);
                var fresh=guard.prepare(replacement);assertTrue(guard.isSameLiveLease(oldPin,fresh));
                assertFalse(guard.isCurrent(oldPin),"Fresh exact input comparison is not old-binding revival");
                assertTrue(guard.drawIfCurrent(List.of(nativeOwner,fresh),()->true));
                guard.requestInvalidation();assertFalse(guard.isCurrent(fresh));assertFalse(guard.isSameLiveLease(oldPin,fresh));
            } finally {gate.close();}
        }
    }
    @Test void unchangedObservedBoundaryMayReuseGeometryOnlyWithFreshExactLeaseAndRevisionJobs(){
        var pin=new CubePos(-1,-1,-1);var neighbor=pin.offset(-1,0,0);var boundary=pos(pin,0,7,7);var unobserved=pos(pin,15,15,15);
        var gate=new CubeStreamAdmission(WORLD,1,2);var leases=gate.replaceInterest(List.of(pin,neighbor)).requested();var snapshots=new HashMap<CubePos,EngineCubeSnapshot>();
        for(var l:leases){var s=snapshot(l.position(),l.generation(),0,null);commit(gate,s);snapshots.put(l.position(),s);}
        try(var guard=new RenderPublicationGuard(gate)) {
            var old=guard.prepare(snapshots.get(pin));var recorder=new SceneReadFootprint.Recorder();
            new SceneNeighborhood(Map.of(pin,cube(snapshots.get(pin)))).tracking(c->{},recorder).getBlockState(boundary);
            var footprint=recorder.seal();var revised=snapshot(pin,old.snapshot().leaseGeneration(),1,unobserved);commit(gate,revised);
            var next=new SceneNeighborhood(Map.of(pin,cube(revised)));var exact=footprint.validate(Set.of(pin),next::replay);
            assertTrue(exact.matches());assertEquals(1,exact.compared());assertFalse(guard.isCurrent(old));
            var fresh=guard.prepare(revised);assertTrue(guard.isSameLiveLease(old,fresh));assertFalse(guard.isCurrent(old));
            gate.replaceInterest(List.of(neighbor));var reenter=gate.replaceInterest(List.of(pin,neighbor)).requested().getFirst();
            var newLease=snapshot(pin,reenter.generation(),1,unobserved);commit(gate,newLease);var afterAba=guard.prepare(newLease);
            assertFalse(guard.isSameLiveLease(old,afterAba));assertFalse(guard.isSameLiveLease(fresh,afterAba));
            guard.invalidateResources();assertFalse(guard.isCurrent(afterAba));assertFalse(guard.isSameLiveLease(fresh,guard.prepare(newLease)));
        } finally {gate.close();}
    }
    @Test void missingBoundaryHaloCannotBeReplayedAsAirOrHiddenByModelLightingTintQueries(){
        var pin=new CubePos(-1,-1,-1);var neighbor=pin.offset(-1,0,0);var boundary=pos(pin,0,7,7);
        List<java.util.function.Consumer<SceneNeighborhood>> queries=List.of(n->n.getBlockState(boundary),n->n.getModelData(boundary),n->n.getBrightness(LightLayer.SKY,boundary),n->n.getRawBrightness(boundary,0),n->n.getBlockTint(boundary,(b,x,z)->0));
        for(var query:queries){var recorder=new SceneReadFootprint.Recorder();var missing=new SceneNeighborhood(Map.of(neighbor,cube(snapshot(neighbor,1,0,null))));
            assertThrows(SparseFacadeException.class,()->query.accept(missing.tracking(c->{},recorder)));assertThrows(IllegalStateException.class,recorder::seal);
        }
        var recorder=new SceneReadFootprint.Recorder();new SceneNeighborhood(Map.of(pin,cube(snapshot(pin,2,0,null)))).tracking(c->{},recorder).getBlockState(boundary);
        var sealed=recorder.seal();var absent=new SceneNeighborhood(Map.of(neighbor,cube(snapshot(neighbor,1,0,null))));
        assertThrows(SparseFacadeException.class,()->sealed.validate(Set.of(pin),absent::replay));
    }
}
