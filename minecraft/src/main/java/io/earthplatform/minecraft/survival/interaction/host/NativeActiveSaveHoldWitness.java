package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

/** Transient held-profile witness, not a durable schema, restorer or tick capability.
 * Persisted item NBT, player pose, food and inventory are checked separately by the Host.
 * Keepalive/output readiness is independently fenced by the retained transport owner.
 */
final class NativeActiveSaveHoldWitness {
    static final int LOGICAL_BYTES = 2048; // 160 primitive longs plus witness/cursor/clock wrappers.
    private static final int MAX_SCALARS = 160;
    private final long[] values;
    private final int count;
    private final ServerPlayer player;
    private final List<ItemEntity> items;
    private final Object gameMode,food,connection,warden;
    private final Object[] menuGraph;
    private NativeActiveSaveHoldWitness(Cursor cursor,ServerPlayer player,List<ItemEntity> items) {
        values=cursor.values;count=cursor.index;this.player=player;this.items=List.copyOf(items);
        gameMode=player.gameMode;food=player.getFoodData();connection=player.connection;
        warden=NativePlayerStepSnapshot.field(ServerPlayer.class,player,"wardenSpawnTracker");
        menuGraph=menuGraph(player);
    }
    static NativeActiveSaveHoldWitness capture(ServerPlayer player,List<ItemEntity> items) {
        var cursor=new Cursor(null,items.size());read(cursor,player,items);return new NativeActiveSaveHoldWitness(cursor,player,items);
    }
    void requireUnchanged(ServerPlayer player,List<ItemEntity> items) {
        if(player!=this.player || !items.equals(this.items) || player.gameMode!=gameMode || player.getFoodData()!=food
                || player.connection!=connection || NativePlayerStepSnapshot.field(ServerPlayer.class,player,"wardenSpawnTracker")!=warden)
            throw new SurvivalRefusal("ACTIVE-save transient scalar owner was replaced");
        Object[] graph=menuGraph(player);
        for(int i=0;i<graph.length;i++)if(graph[i]!=menuGraph[i])throw new SurvivalRefusal("ACTIVE-save menu owner graph changed");
        // No second scalar array/image is retained; the small menu graph and cursor use prepaid scratch.
        var cursor=new Cursor(this,items.size());read(cursor,player,items);
        if(cursor.index!=count)throw new SurvivalRefusal("ACTIVE-save transient scalar witness shape changed");
    }
    private static Object[] menuGraph(ServerPlayer p){
        var menu=p.inventoryMenu;var inventory=p.getInventory();
        return new Object[]{menu,p.containerMenu,inventory,inventory.items,inventory.armor,inventory.offhand,menu.slots,
                NativePlayerStepSnapshot.field(net.minecraft.world.inventory.AbstractContainerMenu.class,menu,"lastSlots"),
                NativePlayerStepSnapshot.field(net.minecraft.world.inventory.AbstractContainerMenu.class,menu,"remoteSlots"),
                NativePlayerStepSnapshot.field(net.minecraft.world.inventory.AbstractContainerMenu.class,menu,"containerListeners"),
                NativePlayerStepSnapshot.field(net.minecraft.world.inventory.AbstractContainerMenu.class,menu,"synchronizer"),
                menu.getCraftSlots(),menu.getSlot(0).container};
    }
    private static void read(Cursor c,ServerPlayer p,List<ItemEntity> items) {
        if(p==null || p.getClass()!=ServerPlayer.class || items==null || items.size()>16
                || p.connection==null || p.connection.getClass()!=ServerGamePacketListenerImpl.class
                || p.gameMode==null || p.gameMode.getClass()!=ServerPlayerGameMode.class || !p.server.isSameThread()
                || p.getFoodData()==null || p.getFoodData().getClass()!=net.minecraft.world.food.FoodData.class)
            throw new SurvivalRefusal("ACTIVE-save transient witness requires exact native owners");
        entity(c,p);
        for(ItemEntity item:items){
            if(item==null||item.getClass()!=ItemEntity.class)throw new SurvivalRefusal("Foreign held item owner");
            entity(c,item);
        }
        c.fields(net.minecraft.world.entity.player.Inventory.class,p.getInventory(),"timesChanged");
        c.fields(net.minecraft.world.inventory.AbstractContainerMenu.class,p.inventoryMenu,
                "stateId","quickcraftType","quickcraftStatus","suppressRemoteUpdates");
        c.f(p.getHealth());c.f(p.getAbsorptionAmount());c.n(p.getAirSupply());
        c.n(p.totalExperience);c.n(p.experienceLevel);c.f(p.experienceProgress);
        c.n(p.hurtTime);c.n(p.hurtDuration);c.n(p.deathTime);c.n(p.takeXpDelay);
        c.f(p.xxa);c.f(p.yya);c.f(p.zza);c.n(p.getFoodData().getLastFoodLevel());
        c.fields(LivingEntity.class,p,"noJumpDelay","jumping","attackAnim","oAttackAnim","attackStrengthTicker");
        c.fields(Player.class,p,"jumpTriggerTime","sleepCounter","currentImpulseContextResetGraceTime",
                "ignoreFallDamageFromCurrentImpulse","lastDayTimeTick");
        c.fields(ServerPlayer.class,p,"spawnInvulnerableTime","levitationStartTime","spawnExtraParticlesOnFall");
        c.fields(ServerPlayerGameMode.class,p.gameMode,"isDestroyingBlock","hasDelayedDestroy","gameTicks",
                "destroyProgressStart","delayedTickStart","lastSentState");
        var connection=p.connection;
        c.fields(ServerGamePacketListenerImpl.class,connection,"firstGoodX","firstGoodY","firstGoodZ",
                "lastGoodX","lastGoodY","lastGoodZ","tickCount","receivedMovePacketCount","knownMovePacketCount",
                "awaitingTeleport","awaitingTeleportTime","clientIsFloating","aboveGroundTickCount",
                "clientVehicleIsFloating","aboveGroundVehicleTickCount","ackBlockChangesUpTo","chatSpamTickCount","dropSpamTickCount");
        Object warden=NativePlayerStepSnapshot.field(ServerPlayer.class,p,"wardenSpawnTracker");
        if(warden==null || warden.getClass()!=net.minecraft.world.entity.monster.warden.WardenSpawnTracker.class)
            throw new SurvivalRefusal("ACTIVE-save transient witness lost exact native Warden tracker");
        c.fields(warden.getClass(),warden,"ticksSinceLastWarning","warningLevel","cooldownTicks");
    }
    private static void entity(Cursor c,Entity e) {
        c.n(e.getId());c.n(e.tickCount);c.n(e.invulnerableTime);c.n(e.getRemainingFireTicks());c.n(e.getTicksFrozen());
        c.d(e.xo);c.d(e.yo);c.d(e.zo);c.d(e.xOld);c.d(e.yOld);c.d(e.zOld);c.f(e.yRotO);c.f(e.xRotO);
        c.f(e.moveDist);c.f(e.walkDist);c.f(e.flyDist);
        c.b(e.horizontalCollision);c.b(e.verticalCollision);c.b(e.verticalCollisionBelow);c.b(e.minorHorizontalCollision);
        c.b(e.isInPowderSnow);c.b(e.wasInPowderSnow);c.b(e.wasOnFire);
        var box=e.getBoundingBox();c.d(box.minX);c.d(box.minY);c.d(box.minZ);c.d(box.maxX);c.d(box.maxY);c.d(box.maxZ);
        var support=e.mainSupportingBlockPos;c.b(support.isPresent());
        c.n(support.isPresent()?support.get().getX():0);c.n(support.isPresent()?support.get().getY():0);c.n(support.isPresent()?support.get().getZ():0);
        c.fields(Entity.class,e,"firstTick","wasTouchingWater","wasEyeInWater","onGroundNoBlocks","nextStep","portalCooldown");
    }
    private static final class Cursor {
        final long[] values;final boolean checking;final int count;int index;
        Cursor(NativeActiveSaveHoldWitness expected,int items) {
            checking=expected!=null;values=checking?expected.values:new long[MAX_SCALARS+64*items];count=checking?expected.count:MAX_SCALARS+64*items;
        }
        void n(long value) {
            if(index>=count)throw new SurvivalRefusal("ACTIVE-save transient scalar witness exceeds prepaid shape");
            if(checking) {
                if(values[index]!=value)throw new SurvivalRefusal("ACTIVE-save held transient scalar changed at index "+index);
            } else values[index]=value;
            index++;
        }
        void b(boolean value){n(value?1:0);} void f(float value){n(Float.floatToRawIntBits(value));}
        void d(double value){n(Double.doubleToRawLongBits(value));}
        void fields(Class<?> owner,Object object,String... names) {
            for(String name:names) {
                Object value=NativePlayerStepSnapshot.field(owner,object,name);
                if(value instanceof Integer i)n(i);else if(value instanceof Long l)n(l);
                else if(value instanceof Boolean b)b(b);else if(value instanceof Float f)f(f);else if(value instanceof Double d)d(d);
                else throw new SurvivalRefusal("ACTIVE-save expected a pinned primitive scalar: "+owner.getSimpleName()+"."+name);
            }
        }
    }
}
