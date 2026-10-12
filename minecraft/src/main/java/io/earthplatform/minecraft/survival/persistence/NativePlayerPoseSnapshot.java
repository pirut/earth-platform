package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;

/** Immutable full-coordinate transition data. This value does not itself permit teleport or mode restoration. */
public record NativePlayerPoseSnapshot(ResourceLocation dimension, double x, double y, double z,
        float yaw, float pitch, double velocityX, double velocityY, double velocityZ, float fallDistance, boolean onGround,
        GameType gameMode, GameType previousGameMode, AbilityState abilities) {
    private static final double POSITION_LIMIT = 29_999_984D, VELOCITY_LIMIT = 10D;
    public record AbilityState(boolean invulnerable, boolean flying, boolean mayFly, boolean instantBuild,
            boolean mayBuild, float flyingSpeed, float walkingSpeed) {
        public AbilityState {
            if (!Float.isFinite(flyingSpeed) || !Float.isFinite(walkingSpeed)
                    || flyingSpeed < 0 || flyingSpeed > 1 || walkingSpeed < 0 || walkingSpeed > 1)
                throw new IllegalArgumentException("Finite bounded native ability speeds required");
        }
        @SuppressWarnings("deprecation") public static AbilityState capture(Abilities value) {
            if (value == null || value.getClass() != Abilities.class)
                throw new SurvivalRefusal("Exact native abilities class required");
            return new AbilityState(value.invulnerable, value.flying, value.mayfly, value.instabuild,
                    value.mayBuild, value.getFlyingSpeed(), value.getWalkingSpeed());
        }
    }
    public NativePlayerPoseSnapshot {
        Objects.requireNonNull(dimension); Objects.requireNonNull(gameMode); Objects.requireNonNull(abilities);
        if (dimension.toString().length() > 1024) throw new IllegalArgumentException("Bounded native dimension key required");
        for (double value : new double[]{x,y,z}) if (!Double.isFinite(value) || Math.abs(value) > POSITION_LIMIT)
            throw new IllegalArgumentException("Full native coordinate outside admitted transition range");
        for (double value : new double[]{velocityX,velocityY,velocityZ}) if (!Double.isFinite(value) || Math.abs(value) > VELOCITY_LIMIT)
            throw new IllegalArgumentException("Native velocity outside lossless save/load profile");
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(fallDistance)
                || Math.abs(yaw) > 360000F || Math.abs(pitch) > 90F || fallDistance < 0 || fallDistance > POSITION_LIMIT)
            throw new IllegalArgumentException("Finite bounded native orientation/fall distance required");
    }
    /** Only stable owner-thread boundaries; no callbacks, native allocation or live mutation. */
    public static NativePlayerPoseSnapshot capture(ServerPlayer player) {
        Objects.requireNonNull(player);
        if (player.getClass() != ServerPlayer.class || !player.server.isSameThread())
            throw new SurvivalRefusal("Exact server-thread native player required");
        NativeInventorySnapshot.requireResolvedMenu(player);
        if (!player.isAlive() || player.isRemoved() || player.isPassenger() || player.isVehicle() || player.isSleeping()
                || player.isChangingDimension() || player.portalProcess != null || player.isUsingItem()
                || !player.getActiveEffects().isEmpty() || player.getCamera() != player
                || player.isFallFlying() || player.isAutoSpinAttack() || player.isSwimming()
                || player.getPose() != net.minecraft.world.entity.Pose.STANDING)
            throw new SurvivalRefusal("Player transition state has not been admitted for durable escrow");
        var motion=player.getDeltaMovement();
        return new NativePlayerPoseSnapshot(player.level().dimension().location(),player.getX(),player.getY(),player.getZ(),
                player.getYRot(),player.getXRot(),motion.x,motion.y,motion.z,player.fallDistance,player.onGround(),
                player.gameMode.getGameModeForPlayer(),player.gameMode.getPreviousGameModeForPlayer(),
                AbilityState.capture(player.getAbilities()));
    }
    /** ResourceLocation is ASCII: two UTF length bytes plus 78 fixed native field bytes. */
    public int encodedBytes() { return 80 + dimension.toString().length(); }
    public void write(DataOutput out) throws IOException {
        out.writeUTF(dimension.toString()); out.writeDouble(x);out.writeDouble(y);out.writeDouble(z);
        out.writeFloat(yaw);out.writeFloat(pitch);out.writeDouble(velocityX);out.writeDouble(velocityY);out.writeDouble(velocityZ);
        out.writeFloat(fallDistance);out.writeBoolean(onGround);out.writeInt(gameMode.getId());out.writeInt(previousGameMode==null?-1:previousGameMode.getId());
        int flags=(abilities.invulnerable()?1:0)|(abilities.flying()?2:0)|(abilities.mayFly()?4:0)
                |(abilities.instantBuild()?8:0)|(abilities.mayBuild()?16:0);
        out.writeByte(flags);out.writeFloat(abilities.flyingSpeed());out.writeFloat(abilities.walkingSpeed());
    }
    public static NativePlayerPoseSnapshot read(DataInput in) throws IOException {
        ResourceLocation dimension=ResourceLocation.parse(in.readUTF());
        double x=in.readDouble(),y=in.readDouble(),z=in.readDouble();float yaw=in.readFloat(),pitch=in.readFloat();
        double vx=in.readDouble(),vy=in.readDouble(),vz=in.readDouble();float fall=in.readFloat();int ground=in.readUnsignedByte();
        int current=in.readInt(),previous=in.readInt(),flags=in.readUnsignedByte();
        if(ground>1||current<0||current>3||previous< -1||previous>3||(flags&~31)!=0)
            throw new IllegalArgumentException("Noncanonical native ground byte, mode or ability bits");
        return new NativePlayerPoseSnapshot(dimension,x,y,z,yaw,pitch,vx,vy,vz,fall,ground==1,GameType.byId(current),
                previous==-1?null:GameType.byId(previous),new AbilityState((flags&1)!=0,(flags&2)!=0,(flags&4)!=0,
                        (flags&8)!=0,(flags&16)!=0,in.readFloat(),in.readFloat()));
    }
}
