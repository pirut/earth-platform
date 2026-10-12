package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.persistence.NativeFoodSnapshot;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.persistence.NativePlayerPoseSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Detached before-image, never a restorer or execution capability. Resolved menus only. */
public record NativePlayerStepSnapshot(NativePlayerPoseSnapshot pose, NativeInventorySnapshot inventory,
        NativeFoodSnapshot food, NativeInventoryMenuSnapshot.Data menu, float health, float absorption, int air, int totalExperience,
        int experienceLevel, float experienceProgress, int tickCount, AABB body,
        Optional<BlockPos> support, boolean horizontalCollision, boolean verticalCollision,
        boolean verticalCollisionBelow, boolean minorHorizontalCollision, Map<String,Object> transientState,
        ConnectionState connection) {
    public NativePlayerStepSnapshot {
        support=support.map(BlockPos::immutable); transientState=Map.copyOf(transientState);
    }
    public record ConnectionState(double firstX,double firstY,double firstZ,
            double lastX,double lastY,double lastZ,int tickCount,int received,int known,
            int awaitingTeleport,int awaitingTeleportTime,Optional<Vec3> correction,
            boolean floating,int floatingTicks,boolean vehicleFloating,int vehicleFloatingTicks,
            int blockAck,int chatSpam,int dropSpam,Optional<Object> lastVehicle) {}
    static NativePlayerStepSnapshot capture(ServerPlayer p,NativePlayerStepBudget budget) {
        NativePlayerMoveSnapshotAdmission.require(p);
        int allowance=budget.snapshot();
        budget.requireScratch();
        NativeInventorySnapshot inventory=NativeInventorySnapshot.capture(p,NativePlayerStepBudget.INVENTORY);
        NativeInventoryMenuSnapshot.Data menu=NativeInventoryMenuSnapshot.capture(p,p.inventoryMenu,p.getInventory(),
                new NativeInventoryMenuSnapshot.Budget(allowance-NativePlayerStepBudget.INVENTORY-4096)).data();
        NativeInventorySnapshot.requireResolvedMenu(p);
        if(p.connection==null||p.connection.getClass()!=ServerGamePacketListenerImpl.class
                ||p.connection.player!=p||!p.server.isSameThread())throw refused("Exact owner-thread step connection required");
        var values=new LinkedHashMap<String,Object>();
        captureFields(values,Entity.class,p,"firstTick","remainingFireTicks","wasTouchingWater","wasEyeInWater",
                "onGroundNoBlocks","inBlockState","nextStep","stuckSpeedMultiplier","portalCooldown","moveDist","walkDist","flyDist");
        captureFields(values,LivingEntity.class,p,"lastPos","lastClimbablePos","noJumpDelay","jumping",
                "attackAnim","oAttackAnim","attackStrengthTicker");
        captureFields(values,Player.class,p,"jumpTriggerTime","sleepCounter",
                "currentImpulseContextResetGraceTime","ignoreFallDamageFromCurrentImpulse",
                "currentImpulseImpactPos","currentExplosionCause","lastDayTimeTick");
        captureFields(values,ServerPlayer.class,p,"spawnInvulnerableTime","startingToFallPosition",
                "levitationStartPos","levitationStartTime","enteredLavaOnVehiclePosition","spawnExtraParticlesOnFall",
                "lastRecordedHealthAndAbsorption","lastRecordedFoodLevel","lastRecordedAirLevel","lastRecordedArmor",
                "lastRecordedLevel","lastRecordedExperience","lastSentHealth","lastSentFood","lastFoodSaturationZero","lastSentExp",
                "lastKnownClientMovement");
        captureFields(values,ServerPlayerGameMode.class,p.gameMode,"isDestroyingBlock","hasDelayedDestroy","gameTicks",
                "destroyProgressStart","destroyPos","delayedTickStart","delayedDestroyPos","lastSentState");
        Object warden=field(ServerPlayer.class,p,"wardenSpawnTracker");
        if(warden==null||warden.getClass()!=net.minecraft.world.entity.monster.warden.WardenSpawnTracker.class)
            throw refused("Exact native Warden tracker required");
        captureFields(values,net.minecraft.world.entity.monster.warden.WardenSpawnTracker.class,warden,
                "ticksSinceLastWarning","warningLevel","cooldownTicks");
        values.put("old-position",java.util.List.of(p.xo,p.yo,p.zo,p.xOld,p.yOld,p.zOld));
        values.put("old-rotation",java.util.List.of(p.yRotO,p.xRotO));
        values.put("timers",java.util.List.of(p.invulnerableTime,p.hurtTime,p.hurtDuration,p.deathTime,p.takeXpDelay));
        values.put("food.lastFoodLevel",p.getFoodData().getLastFoodLevel());
        return new NativePlayerStepSnapshot(NativePlayerPoseSnapshot.capture(p),inventory,
                NativeFoodSnapshot.capture(p.getFoodData()),menu,p.getHealth(),p.getAbsorptionAmount(),p.getAirSupply(),
                p.totalExperience,p.experienceLevel,p.experienceProgress,p.tickCount,p.getBoundingBox(),
                p.mainSupportingBlockPos,p.horizontalCollision,p.verticalCollision,p.verticalCollisionBelow,
                p.minorHorizontalCollision,values,captureConnection(p.connection));
    }
    private static ConnectionState captureConnection(ServerGamePacketListenerImpl c) {
        return new ConnectionState(d(c,"firstGoodX"),d(c,"firstGoodY"),d(c,"firstGoodZ"),d(c,"lastGoodX"),d(c,"lastGoodY"),d(c,"lastGoodZ"),
                i(c,"tickCount"),i(c,"receivedMovePacketCount"),i(c,"knownMovePacketCount"),i(c,"awaitingTeleport"),i(c,"awaitingTeleportTime"),
                Optional.ofNullable((Vec3)field(ServerGamePacketListenerImpl.class,c,"awaitingPositionFromClient")),
                b(c,"clientIsFloating"),i(c,"aboveGroundTickCount"),b(c,"clientVehicleIsFloating"),i(c,"aboveGroundVehicleTickCount"),
                i(c,"ackBlockChangesUpTo"),i(c,"chatSpamTickCount"),i(c,"dropSpamTickCount"),
                Optional.ofNullable(field(ServerGamePacketListenerImpl.class,c,"lastVehicle")));
    }
    private static double d(Object c,String name){return (Double)field(ServerGamePacketListenerImpl.class,c,name);}
    private static int i(Object c,String name){return (Integer)field(ServerGamePacketListenerImpl.class,c,name);}
    private static boolean b(Object c,String name){return (Boolean)field(ServerGamePacketListenerImpl.class,c,name);}
    static Object field(Class<?> owner,Object value,String name) {
        try {Field field=owner.getDeclaredField(name);if(!field.trySetAccessible())throw refused("Step field inaccessible: "+name);return field.get(value);}
        catch(ReflectiveOperationException|SecurityException failure){throw refused("Pinned native step field unavailable: "+owner.getSimpleName()+"."+name);}
    }
    private static void captureFields(Map<String,Object> out,Class<?> owner,Object value,String... names){
        for(String name:names){Object field=field(owner,value,name);out.put(owner.getSimpleName()+"."+name,Optional.ofNullable(field));}
    }
    void requireConserved(NativePlayerStepSnapshot after){
        if(!inventory.equals(after.inventory)||health!=after.health||absorption!=after.absorption||air!=after.air
                ||totalExperience!=after.totalExperience||experienceLevel!=after.experienceLevel||experienceProgress!=after.experienceProgress)
            throw refused("Native player step changed unowned inventory/health/air/experience");
    }
    private static SurvivalRefusal refused(String message){return new SurvivalRefusal(message);}
}
