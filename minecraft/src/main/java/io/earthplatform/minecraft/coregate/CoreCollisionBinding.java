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
final class CoreCollisionBinding implements BlockGetter, io.earthplatform.minecraft.enginebridge.NativeCollisionMotionFence, io.earthplatform.minecraft.enginebridge.NativePlayerVoidSafety, io.earthplatform.minecraft.enginebridge.NativePlayerMotionGate {
    static final String OPT_IN = "earthplatform.experimental.coreCollisionGate";
    private final Level level;
    private final CorePlayerSafetyLease safety;
    private final RollingSessionDescriptor descriptor;
    private final Thread owner=Thread.currentThread();
    private volatile NativeCollisionView current;
    private AtomicBoolean frame;
    private boolean detached;
    private long frameSequence;
    private RollingMotionGuard motion;
    private net.minecraft.world.entity.player.Player motionPlayer;
    private Object motionConnection;
    private boolean issuingCorrection;
    private net.minecraft.world.entity.player.Player clientMotionPlayer;
    private java.util.function.BiPredicate<net.minecraft.world.phys.AABB,net.minecraft.world.phys.Vec3> clientMotion;
    /** Prediction-only intersection. Authoritative server corrections must not be refused by this predicate. */
    void clientMotion(net.minecraft.world.entity.player.Player player,java.util.function.BiPredicate<net.minecraft.world.phys.AABB,net.minecraft.world.phys.Vec3> permit){
        requireOwner();if(!level.isClientSide||player.level()!=level||safety==null||!safety.ownedBy(player.getUUID()))throw new IllegalArgumentException("Exact client player required");
        clientMotionPlayer=player;clientMotion=java.util.Objects.requireNonNull(permit);
    }
    private java.util.function.BiPredicate<net.minecraft.world.phys.AABB,net.minecraft.world.phys.Vec3> visualMotion = (body, delta) -> true;
    /** Host may intersect a separately server-validated visual domain; body authority never mints it. */
    void visualMotion(java.util.function.BiPredicate<net.minecraft.world.phys.AABB,net.minecraft.world.phys.Vec3> permit){requireOwner();visualMotion=java.util.Objects.requireNonNull(permit);}
    void attachMotion(net.minecraft.server.level.ServerPlayer p) {
        requireOwner();if(descriptor==null||motion!=null||!safety.ownedBy(p.getUUID())||p.level()!=level)throw new IllegalStateException("Invalid motion attachment");
        var e=descriptor.bodyEnvelope();motionPlayer=p;motionConnection=p.connection;
        motion=new RollingMotionGuard(safety.epoch(),new net.minecraft.world.phys.AABB(e.minX(),e.minY(),e.minZ(),e.maxX(),e.maxY(),e.maxZ()),pose(p),descriptor.initialCenter());
    }
    RollingMotionGuard motion(){return motion;}
    RollingMotionGuard.Pose pose(net.minecraft.world.entity.player.Player p){return new RollingMotionGuard.Pose(p.getBoundingBox(),p.position(),p.isSpectator(),p.noPhysics,motion==null?null:motion.authority());}
    RollingMotionGuard.Pose pose(net.minecraft.world.entity.player.Player p,RollingMotionGuard.Authority a){return new RollingMotionGuard.Pose(p.getBoundingBox(),p.position(),p.isSpectator(),p.noPhysics,a);}
    RollingMotionGuard.Authority authority(long generation,long publication,String digest,long resources){return new RollingMotionGuard.Authority(safety.epoch(),generation,publication,digest,frameSequence,resources,descriptor.sourceFingerprint());}
    boolean certify(RollingMotionGuard.Pose pose){
        var view=current;return view!=null&&pose.authority()!=null&&pose.authority().frame()==frameSequence&&pose.authority().epoch()==safety.epoch()&&pose.authority().source().equals(descriptor.sourceFingerprint())&&knownBody(view,pose.body(),pose.spectator()&&pose.noPhysics());
    }
    /** At most 12 body cells; NativeCollisionView bounds its audited one-cell collar reads. No decoding/IO. */
    static boolean knownBody(NativeCollisionView view,net.minecraft.world.phys.AABB body,boolean occupiedSpectator){
        if(!RollingMotionGuard.finiteBody(body))return false;return knownBox(view,body,occupiedSpectator,12);
    }
    /** Publication-boundary certificate only: materialize at most eight body/collar sections, never125. */
    static boolean preparedBodyKnown(WorldIdentity world,long epoch,EnginePaletteContext palette,Map<CubePos,EngineCubeSnapshot> snapshots,net.minecraft.world.phys.AABB body,boolean occupiedSpectator){
        if(!RollingMotionGuard.finiteBody(body))return false;
        int x0=(int)Math.floor(body.minX)-1,y0=(int)Math.floor(body.minY)-1,z0=(int)Math.floor(body.minZ)-1;
        int x1=(int)Math.ceil(body.maxX),y1=(int)Math.ceil(body.maxY),z1=(int)Math.ceil(body.maxZ);
        var selected=new java.util.HashMap<CubePos,EngineCubeSnapshot>(8);
        for(int x=CubePos.section(x0);x<=CubePos.section(x1);x++)for(int y=CubePos.section(y0);y<=CubePos.section(y1);y++)for(int z=CubePos.section(z0);z<=CubePos.section(z1);z++){
            var key=new CubePos(x,y,z);var snapshot=snapshots.get(key);if(snapshot!=null)selected.put(key,snapshot);
        }
        if(selected.size()>8)throw new IllegalStateException("Body certificate section bound exceeded");
        try(var view=new NativeCollisionView(world,epoch,palette,selected,()->selected.entrySet().stream().allMatch(e->snapshots.get(e.getKey())==e.getValue()))){return knownBody(view,body,occupiedSpectator);}
    }
    private static boolean knownBox(NativeCollisionView view,net.minecraft.world.phys.AABB box,boolean occupied,int limit){
        if(view==null||!view.isCurrent())return false;
        int minX=(int)Math.floor(box.minX),minY=(int)Math.floor(box.minY),minZ=(int)Math.floor(box.minZ);
        int maxX=(int)Math.ceil(box.maxX)-1,maxY=(int)Math.ceil(box.maxY)-1,maxZ=(int)Math.ceil(box.maxZ)-1;
        long count=(long)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1);if(count<1||count>limit)return false;
        for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++)for(int z=minZ;z<=maxZ;z++){
            var read=view.read(new BlockPos(x,y,z));if(!read.known()||!occupied&&!read.state().isAir())return false;
        }
        return view.isCurrent();
    }
    @Override public boolean ownsMotion(net.minecraft.world.entity.player.Player p){return !detached&&p.level()==level&&(level.isClientSide?clientMotion!=null&&p==clientMotionPlayer:motion!=null&&safety.ownedBy(p.getUUID()));}
    boolean exactMotionOwner(net.minecraft.world.entity.player.Player p){return p==motionPlayer&&p instanceof net.minecraft.server.level.ServerPlayer sp&&sp.connection==motionConnection;}
    @Override public boolean admitMotion(net.minecraft.world.entity.player.Player p,net.minecraft.world.phys.Vec3 delta,boolean packet){
        requireOwner();if(!ownsMotion(p))return false;
        if(level.isClientSide)return ready()&&RollingMotionGuard.bounded(p.getBoundingBox(),delta)&&clientMotion.test(p.getBoundingBox(),delta);
        if(!exactMotionOwner(p))return false;
        return motion.admit(pose(p),delta,this::certify,packet)&&visualMotion.test(p.getBoundingBox(),delta);
    }
    @Override public boolean admitPosition(net.minecraft.world.entity.player.Player p,net.minecraft.world.phys.Vec3 target){
        requireOwner();if(!exactMotionOwner(p))return false;if(issuingCorrection)return RollingMotionGuard.sameBody(p.getBoundingBox().move(target.subtract(p.position())),motion.accepted().body());
        var delta=target.subtract(p.position());if(delta.lengthSqr()==0)return true;
        if(!admitMotion(p,delta,false))return false;
        // Vanilla's collided Entity.move vector is clear here. A later absMoveTo cannot tunnel through a block.
        // Spectators may cross known solids, but never an unknown cell. <= 6*7*6 = 252 swept cells.
        return knownBox(current,p.getBoundingBox().minmax(p.getBoundingBox().move(delta)),p.isSpectator()&&p.noPhysics,252);
    }
    @Override public boolean admitBody(net.minecraft.world.entity.player.Player p,net.minecraft.world.phys.AABB body){
        requireOwner();if(!exactMotionOwner(p)||!RollingMotionGuard.finiteBody(body))return false;
        // Pose changes may expand the body without moving feet. They need the same frontier permit.
        if(Math.abs((body.minX+body.maxX)/2-p.getX())>1e-7||Math.abs(body.minY-p.getY())>1e-7||Math.abs((body.minZ+body.maxZ)/2-p.getZ())>1e-7)return false;
        return motion.admitBody(new RollingMotionGuard.Pose(body,p.position(),p.isSpectator(),p.noPhysics,motion.authority()),this::certify);
    }
    @Override public boolean admitTeleport(net.minecraft.world.entity.player.Player p,net.minecraft.world.phys.Vec3 target){
        requireOwner();return exactMotionOwner(p)&&(issuingCorrection||!motion.correctionPending()&&admitPosition(p,target));
    }
    @Override public void observeMotion(net.minecraft.world.entity.player.Player p){
        requireOwner();if(level.isClientSide)return;if(ownsMotion(p)&&(!exactMotionOwner(p)||!motion.observe(pose(p),this::certify)))motion.fail("Uncertified authoritative position or mode");
    }
    @Override public void correctMotion(net.minecraft.world.entity.player.Player p){
        requireOwner();if(!(p instanceof net.minecraft.server.level.ServerPlayer sp)||!ownsMotion(p)||!exactMotionOwner(p)||motion.correctionPending())return;
        var anchor=motion.accepted();issuingCorrection=true;
        try{p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);p.resetFallDistance();sp.connection.teleport(anchor.feet().x,anchor.feet().y,anchor.feet().z,p.getYRot(),p.getXRot());if(!motion.correctionPending())motion.fail("Position correction was refused");}
        finally{issuingCorrection=false;}
    }
    @Override public void correctionSent(net.minecraft.world.entity.player.Player p,int id){requireOwner();if(ownsMotion(p)&&exactMotionOwner(p)&&issuingCorrection)motion.correctionSent(id,System.nanoTime());}
    @Override public void correctionAcknowledged(net.minecraft.world.entity.player.Player p,int id,boolean settled){requireOwner();if(ownsMotion(p)&&exactMotionOwner(p))motion.acknowledge(id,pose(p),settled);}
    @Override public boolean correctionOutstanding(net.minecraft.world.entity.player.Player p){return !level.isClientSide&&ownsMotion(p)&&motion.correctionPending();}

    CoreCollisionBinding(Level level) {this(level,null,0);}
    CoreCollisionBinding(Level level,java.util.UUID player,long epoch) {this(level,player==null?null:new CorePlayerSafetyLease(player,epoch),null);}
    CoreCollisionBinding(Level level,java.util.UUID player,long epoch,RollingSessionDescriptor descriptor) {
        this(level,new CorePlayerSafetyLease(player,epoch,descriptor),descriptor);
    }
    private CoreCollisionBinding(Level level,CorePlayerSafetyLease safety,RollingSessionDescriptor descriptor) {
        this.descriptor=descriptor;
        this.safety=safety;
        if (!(Boolean.getBoolean(OPT_IN)||io.earthplatform.compat.dh.EarlyPlayableAdmission.enabled()) || !level.dimension().equals(SceneCommands.CARRIER))
            throw new IllegalArgumentException("Explicit collision carrier required");
        this.level = level;
        var access = (NativeCollisionLevelAccess) level;
        var old = access.earthplatform$coreCollisionView();
        if (old != null && (!(old instanceof CoreCollisionBinding b) || b.current != null))
            throw new IllegalStateException("Another live collision owner holds this Level");
        if (!access.earthplatform$replaceCoreCollisionView(old, this))
            throw new IllegalStateException("Collision ownership changed while attaching");
        // The replacement is attached first on this same owner thread; no unowned body interval.
        // Only an already-fenced zero-resident predecessor is eligible above.
        if(old instanceof CoreCollisionBinding previous){
            previous.detached=true;if(previous.motion!=null)previous.motion.fail("exact zero-owner boundary replaced");
            if(previous.safety!=null)previous.safety.close();
        }
    }
    synchronized void publish(WorldIdentity world, long epoch, EnginePaletteContext palette,
                              Map<CubePos, EngineCubeSnapshot> snapshots) {
        requireOwner();if (detached) throw new IllegalStateException("Collision owner detached");
        if(descriptor!=null){descriptor.requirePreparedWorld(world);if(epoch!=safety.epoch())throw new IllegalArgumentException("Collision safety epoch mismatch");}
        var nextFrame = new AtomicBoolean(true);
        var next = new NativeCollisionView(world, epoch, palette, snapshots, nextFrame::get);
        fence();
        frameSequence++;
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
        if(motion!=null)motion.fail("detached");
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
        if(descriptor!=null&&descriptor.arenaMode()){
            var bounds=descriptor.bodyEnvelope();
            if(pos.getX()<bounds.minX()||pos.getX()>=bounds.maxX()||pos.getY()<bounds.minY()||pos.getY()>=bounds.maxY()||pos.getZ()<bounds.minZ()||pos.getZ()>=bounds.maxZ())return Blocks.BARRIER.defaultBlockState();
        }
        var view = current;
        return view == null ? Blocks.BARRIER.defaultBlockState() : view.getBlockState(pos);
    }
    @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public int getHeight() { return 19872; }
    @Override public int getMinBuildHeight() { return -11008; }
}
