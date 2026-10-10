package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCollisionLevelAccess;
import io.earthplatform.minecraft.enginebridge.NativeCollisionView;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.scene.SceneCommands;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/** Collision policy only. The unavailable boundary never enters terrain, rendering or storage. */
final class CoreCollisionBinding implements BlockGetter, io.earthplatform.minecraft.enginebridge.NativeCollisionMotionFence, io.earthplatform.minecraft.enginebridge.NativePlayerVoidSafety {
    static final String OPT_IN = "earthplatform.experimental.coreCollisionGate";
    private final Level level;
    private final CorePlayerSafetyLease safety;
    private final Thread owner=Thread.currentThread();
    private volatile NativeCollisionView current;
    private AtomicBoolean frame;
    private boolean detached;
    CoreCollisionBinding(Level level) {this(level,null,0);}
    CoreCollisionBinding(Level level,java.util.UUID player,long epoch) {
        safety=player==null?null:new CorePlayerSafetyLease(player,epoch);
        if (!Boolean.getBoolean(OPT_IN) || !level.dimension().equals(SceneCommands.CARRIER))
            throw new IllegalArgumentException("Explicit collision carrier required");
        this.level = level;
        var access = (NativeCollisionLevelAccess) level;
        var old = access.earthplatform$coreCollisionView();
        if (old != null && (!(old instanceof CoreCollisionBinding b) || b.current != null))
            throw new IllegalStateException("Another live collision owner holds this Level");
        if (!access.earthplatform$replaceCoreCollisionView(old, this))
            throw new IllegalStateException("Collision ownership changed while attaching");
    }
    synchronized void publish(WorldIdentity world, long epoch, EnginePaletteContext palette,
                              Map<CubePos, EngineCubeSnapshot> snapshots) {
        requireOwner();if (detached) throw new IllegalStateException("Collision owner detached");
        var nextFrame = new AtomicBoolean(true);
        var next = new NativeCollisionView(world, epoch, palette, snapshots, nextFrame::get);
        fence();
        frame = nextFrame;
        current = next;
    }
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Collision publication requires its game owner thread");}
    synchronized void fence() {
        requireOwner();
        if (frame != null) frame.set(false);
        var old = current; current = null; frame = null;
        if (old != null) old.close();
    }
    synchronized void detach() {
        requireOwner();
        fence();
        if (detached) return;
        var access = (NativeCollisionLevelAccess) level;
        if (!access.earthplatform$replaceCoreCollisionView(this, null))
            throw new IllegalStateException("Cannot detach a different collision owner");
        detached = true;
        if(safety!=null)safety.close();
    }
    boolean safetyOwnedBy(java.util.UUID player){return !detached&&safety!=null&&safety.ownedBy(player);}
    @Override public boolean preventsStockVoidCheck(java.util.UUID player,net.minecraft.world.phys.AABB body){return !detached&&safety!=null&&safety.contains(player,body);}
    @Override public boolean blocksPlayerMovement() { return !ready(); }
    int residentOwners() { var view = current; return view == null ? 0 : view.residentOwners(); }
    String boundaryStatus() { var view=current; if(view==null)return "collision frame unavailable";var last=view.counters().lastBoundary();return last==null?"known sparse collision box":"blocked unknown: "+last.reason(); }
    boolean ready() { var view = current; return view != null && view.isCurrent(); }
    @Override public BlockState getBlockState(BlockPos pos) {
        var view = current;
        return view == null ? Blocks.BARRIER.defaultBlockState() : view.getBlockState(pos);
    }
    @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public int getHeight() { return 19872; }
    @Override public int getMinBuildHeight() { return -11008; }
}
