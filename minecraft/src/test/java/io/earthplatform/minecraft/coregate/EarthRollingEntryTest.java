package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCollisionView;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.enginebridge.NativeEarthCubeProvider;
import io.earthplatform.minecraft.enginebridge.NativeRayEditPlanner;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Actual accepted source + vanilla collision/ray CPU contracts; no GUI or live entity claim. */
@Timeout(30)
class EarthRollingEntryTest {
    private static final long EPOCH=31, X=9_674_936L, Z=-3_112_472L;
    private static EnginePaletteContext palette;
    private static RollingSessionDescriptor descriptor;
    private static Map<CubePos,EngineCubeSnapshot> snapshots;
    @BeforeAll static void prepare() {
        var biomes=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        biomes.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
            .getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(biomes.freeze());
        var source=NativeEarthCubeProvider.prepare(palette,true);var w=source.identity();
        var world=new WorldIdentity(w.seed(),w.generatorId(),w.generatorVersion()+"/"+RollingGateFixture.VERSION,w.datasetVersion(),w.blockPaletteVersion());
        int ground=Math.toIntExact(source.surfaceSampler().surfaceY(X,Z));assertEquals(7506,ground);
        descriptor=RollingSessionDescriptor.earth(world,palette,X,ground,Z);
        var codec=new NativeCubeCodec(world,palette);var result=new HashMap<CubePos,EngineCubeSnapshot>();long lease=1;
        for(var p:RollingCubePlan.stationary(descriptor.pinCenter(),descriptor.center()).currentOwners())
            result.put(p,codec.materialize(p,source.load(p,new CancellationToken()).join(),EPOCH,lease++,0));
        snapshots=Map.copyOf(result);assertEquals(125,snapshots.size());
    }
    private static final class Access implements CollisionGetter {
        final NativeCollisionView view;final WorldBorder border=new WorldBorder();
        Access(NativeCollisionView view){this.view=view;}
        public BlockGetter getChunkForCollisions(int x,int z){return view;}
        public BlockState getBlockState(BlockPos p){return view.getBlockState(p);}
        public FluidState getFluidState(BlockPos p){return view.getFluidState(p);}
        public BlockEntity getBlockEntity(BlockPos p){return view.getBlockEntity(p);}
        public int getHeight(){return view.getHeight();}public int getMinBuildHeight(){return view.getMinBuildHeight();}
        public WorldBorder getWorldBorder(){return border;}
        public List<VoxelShape> getEntityCollisions(Entity entity,AABB box){return List.of();}
    }
    @Test void measuredSpawnBodyIsClearAndNativeSolverLandsOnExactSourceTop() {
        var body=descriptor.walkEntryBody();assertEquals(7509,body.minY);assertTrue(descriptor.containsBody(body));
        try(var view=new NativeCollisionView(descriptor.world(),EPOCH,palette,snapshots,()->true)){
            var access=new Access(view);assertTrue(access.noCollision(body));
            var shapes=new ArrayList<VoxelShape>();
            new BlockCollisions<>(access,null,body.expandTowards(0,-8,0),false,(p,s)->s).forEachRemaining(shapes::add);
            double dy=Shapes.collide(Direction.Axis.Y,body,shapes,-8);
            assertEquals(-2,dy,1e-12);var landed=body.move(0,dy,0);
            assertEquals(7507,landed.minY);assertTrue(access.noCollision(landed));
            assertFalse(access.noCollision(landed.move(0,-.01,0)));
            assertTrue(view.isCurrent());assertEquals(0,view.counters().boundaryReads());
        }
    }
    @Test void ordinaryPitch31PlacementAndRemovalStayInsideOriginalPinAndAvoidBody() {
        for(double feetY:new double[]{7509,7507}){
            var body=descriptor.walkEntryBody().move(0,feetY-descriptor.entry().y,0);
            var eye=new Vec3(descriptor.entry().x,feetY+1.62f,descriptor.entry().z);
            for(var action:NativeRayEditPlanner.Action.values()){
                var result=NativeRayEditPlanner.plan(descriptor.world(),EPOCH,palette,snapshots,eye,
                    Vec3.directionFromRotation(31,0),8,action,descriptor.target(),body);
                var plan=assertInstanceOf(NativeRayEditPlanner.Plan.class,result,()->"feet="+feetY+", action="+action+", result="+result);
                var r=plan.request();assertTrue(RollingCubePlan.isEditableInterior(descriptor.target(),r.x(),r.y(),r.z()));
                assertEquals(descriptor.target(),r.position());assertTrue(plan.isCurrent(snapshots));
                assertTrue(eye.distanceTo(plan.hit().getLocation())<=8);
                if(action==NativeRayEditPlanner.Action.PLACE_DIORITE){
                    assertFalse(body.intersects(new AABB(r.x(),r.y(),r.z(),r.x()+1,r.y()+1,r.z()+1)));
                    assertEquals(Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState()),r.stateId());
                }
                System.out.printf("EARTH_ENTRY_RAY feet=%.1f action=%s hit=%s face=%s target=%d,%d,%d reach=%.6f%n",
                    feetY,action,plan.hit().getBlockPos(),plan.hit().getDirection(),r.x(),r.y(),r.z(),eye.distanceTo(plan.hit().getLocation()));
            }
        }
    }
    @Test void oldGroundedPitch60IsCorrectlyRejectedForBodyOverlap() {
        var body=descriptor.walkEntryBody().move(0,-2,0);
        var eye=new Vec3(descriptor.entry().x,7507+1.62f,descriptor.entry().z);
        var result=NativeRayEditPlanner.plan(descriptor.world(),EPOCH,palette,snapshots,eye,
            Vec3.directionFromRotation(60,0),8,NativeRayEditPlanner.Action.PLACE_DIORITE,descriptor.target(),body);
        assertEquals(NativeRayEditPlanner.RejectionReason.TARGET_BODY_OVERLAP,
            assertInstanceOf(NativeRayEditPlanner.Rejected.class,result).reason());
        var withoutBody=assertInstanceOf(NativeRayEditPlanner.Plan.class,NativeRayEditPlanner.plan(
            descriptor.world(),EPOCH,palette,snapshots,eye,Vec3.directionFromRotation(60,0),8,
            NativeRayEditPlanner.Action.PLACE_DIORITE,descriptor.target(),null));
        var r=withoutBody.request();assertTrue(body.intersects(new AABB(r.x(),r.y(),r.z(),r.x()+1,r.y()+1,r.z()+1)));
    }
}
