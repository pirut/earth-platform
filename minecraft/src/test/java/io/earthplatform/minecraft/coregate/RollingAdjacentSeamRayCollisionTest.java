package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tested GUI aim and actual vanilla collision across the shared cube face; no GUI claim. */
class RollingAdjacentSeamRayCollisionTest {
    static EnginePaletteContext palette;
    static RollingSessionDescriptor descriptor;
    static Map<CubePos,EngineCubeSnapshot> snapshots;
    static Map<CubePos,ImmutableCube> blocks;
    static final long EPOCH=91;
    @BeforeAll static void prepare() {
        RollingAdjacentWorkspaceDescriptorTest.setup();palette=RollingAdjacentWorkspaceDescriptorTest.palette;
        descriptor=RollingSessionDescriptor.analytic(CoreGateProfile.BASE,palette,RollingEditPolicy.FULL_CUBE_V1,true,true);
        var provider=RollingGateFixture.provider(palette,CoreGateProfile.BASE);var codec=new NativeCubeCodec(descriptor.world(),palette);
        var ss=new HashMap<CubePos,EngineCubeSnapshot>();var bb=new HashMap<CubePos,ImmutableCube>();long lease=1;
        for(var p:RollingCubePlan.stationary(descriptor.center(),descriptor.center()).currentOwners()) {
            var cube=provider.load(p,new CancellationToken()).join();bb.put(p,cube);ss.put(p,codec.materialize(p,cube,EPOCH,lease++,0));
        }
        snapshots=Map.copyOf(ss);blocks=Map.copyOf(bb);
    }
    static AABB body(double x,double z) { return new AABB(x-.3,129,z-.3,x+.3,130.8,z+.3); }
    @Test void pitch31OrdinaryRaysReachBothSidesOfSeamAndRejectWrongFocus() {
        for(int x:new int[]{15,16}) {
            var focus=CubePos.fromBlock(x,128,15);var eye=new Vec3(x+.5,129+1.62f,12.5);var body=body(x+.5,12.5);
            for(var action:NativeRayEditPlanner.Action.values()) {
                var result=NativeRayEditPlanner.plan(descriptor.world(),EPOCH,palette,snapshots,eye,Vec3.directionFromRotation(31,0),8,action,focus,body);
                var plan=assertInstanceOf(NativeRayEditPlanner.Plan.class,result);var request=plan.request();
                assertEquals(x,request.x());assertEquals(15,request.z());assertEquals(action==NativeRayEditPlanner.Action.REMOVE?128:129,request.y());
                assertTrue(plan.isCurrent(snapshots));assertTrue(descriptor.permitsEdit(focus,request.x(),request.y(),request.z()));
                var rejected=NativeRayEditPlanner.plan(descriptor.world(),EPOCH,palette,snapshots,eye,Vec3.directionFromRotation(31,0),8,action,focus.equals(descriptor.workspaces().original())?descriptor.workspaces().second():descriptor.workspaces().original(),body);
                assertEquals(NativeRayEditPlanner.RejectionReason.OUTSIDE_EDITABLE_CUBE,assertInstanceOf(NativeRayEditPlanner.Rejected.class,rejected).reason());
                System.out.println("ADJACENT_SEAM_AIM x="+(x+.5)+" feet=129 z=12.5 yaw=0 pitch=31 action="+action+" target="+request.x()+","+request.y()+","+request.z());
            }
        }
    }
    @Test void exactSeamExcavationChangesVanillaCollisionAcrossBothCubeOwners() {
        var codec=new NativeCubeCodec(descriptor.world(),palette);var changed=new HashMap<>(snapshots);
        int air=Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        for(int x:new int[]{15,16}) {
            var p=CubePos.fromBlock(x,128,15);var old=snapshots.get(p);
            var next=blocks.get(p).withEdits(Map.of(CubePos.index(Math.floorMod(x,16),0,15),air));
            changed.put(p,codec.materialize(p,next,EPOCH,old.leaseGeneration(),1));
        }
        var body=body(16,15.5);
        try(var old=new NativeCollisionView(descriptor.world(),EPOCH,palette,snapshots,()->true);
            var current=new NativeCollisionView(descriptor.world(),EPOCH,palette,changed,()->true)) {
            assertEquals(0,drop(old,body),1e-12);assertEquals(-1,drop(current,body),1e-12);
            assertEquals(0,current.counters().boundaryReads());assertTrue(new Access(current).noCollision(body.move(0,-1,0)));
            assertFalse(new Access(current).noCollision(body.move(0,-1.01,0)));
        }
    }
    @Test void missingEitherSeamCubeDoesNotBecomeTraversableAir() {
        for(var absent:descriptor.workspaces().positions()) {
            var missing=new HashMap<>(snapshots);missing.remove(absent);
            try(var current=new NativeCollisionView(descriptor.world(),EPOCH,palette,missing,()->true)) {
                assertEquals(0,drop(current,body(16,15.5)),1e-12);
                assertTrue(current.counters().boundaryReads()>0);
            }
        }
    }
    static double drop(NativeCollisionView view,AABB body) {
        var shapes=new ArrayList<VoxelShape>();new BlockCollisions<>(new Access(view),null,body.expandTowards(0,-2,0),false,(p,s)->s).forEachRemaining(shapes::add);
        return Shapes.collide(Direction.Axis.Y,body,shapes,-2);
    }
    static final class Access implements CollisionGetter {
        final NativeCollisionView view;final WorldBorder border=new WorldBorder();Access(NativeCollisionView view){this.view=view;}
        public BlockGetter getChunkForCollisions(int x,int z){return view;}public BlockState getBlockState(BlockPos p){return view.getBlockState(p);}
        public FluidState getFluidState(BlockPos p){return view.getFluidState(p);}public BlockEntity getBlockEntity(BlockPos p){return view.getBlockEntity(p);}
        public int getHeight(){return view.getHeight();}public int getMinBuildHeight(){return view.getMinBuildHeight();}public WorldBorder getWorldBorder(){return border;}
        public List<VoxelShape> getEntityCollisions(Entity entity,AABB box){return List.of();}
    }
}
