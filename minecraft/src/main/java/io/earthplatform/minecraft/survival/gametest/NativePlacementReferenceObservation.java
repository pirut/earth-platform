package io.earthplatform.minecraft.survival.gametest;

import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/** Read-only, explicitly scoped native-reference observations. Never cancels a native call. */
public final class NativePlacementReferenceObservation implements AutoCloseable {
    private static final ThreadLocal<NativePlacementReferenceObservation> ACTIVE=new ThreadLocal<>();
    public record Sound(ResourceLocation sound,String category,double x,double y,double z,float volume,float pitch,long seed,UUID excludedActor) {}
    public record Event(ResourceLocation event,Vec3 position,UUID actor,BlockState affectedState) {}
    private final ServerLevel level;
    private final ServerPlayer player;
    private final Thread owner=Thread.currentThread();
    private final List<Sound> sounds=new ArrayList<>(2);
    private final List<Event> events=new ArrayList<>(2);
    private final List<String> order=new ArrayList<>(4);
    private String failure;
    private boolean closed;
    private NativePlacementReferenceObservation(ServerLevel level,ServerPlayer player) { this.level=level;this.player=player; }
    public static NativePlacementReferenceObservation open(ServerLevel level,ServerPlayer player) {
        var current=ACTIVE.get();
        if (current!=null) { current.fail("Nested native reference observation"); throw refused(current.failure); }
        if (level==null || player==null || player.getClass()!=ServerPlayer.class || player.serverLevel()!=level
                || !(level.getServer() instanceof GameTestServer) || !level.getServer().isSameThread()
                || SurvivalLevelBinding.find(level)!=null)
            throw refused("Native reference observation requires the exact unbound GameTest level/player owner");
        var result=new NativePlacementReferenceObservation(level,player); ACTIVE.set(result); return result;
    }
    public List<String> order() { require();return List.copyOf(order); }
    public List<Sound> sounds() { require();return List.copyOf(sounds); }
    public List<Event> events() { require();return List.copyOf(events); }
    private void fail(String value) { if (failure==null) failure=value; }
    private boolean accept(ServerLevel received) {
        if (closed || owner!=Thread.currentThread() || ACTIVE.get()!=this || received!=level
                || player.serverLevel()!=level || SurvivalLevelBinding.find(level)!=null || !level.getServer().isSameThread()) {
            fail("Native reference observation owner/world changed"); return false;
        }
        return failure==null;
    }
    private void require() {
        if (!accept(level)) throw refused(failure==null?"Native reference observation is closed":failure);
    }
    public static void sound(ServerLevel level,Player excluded,double x,double y,double z,Holder<SoundEvent> sound,
            SoundSource category,float volume,float pitch,long seed) {
        var scope=ACTIVE.get(); if (scope==null || !scope.accept(level)) return;
        if (scope.sounds.size()>=2 || excluded!=scope.player) { scope.fail("Native reference sound bound/actor changed");return; }
        scope.order.add("sound");scope.sounds.add(new Sound(BuiltInRegistries.SOUND_EVENT.getKey(sound.value()),category.getName(),x,y,z,volume,pitch,seed,excluded.getUUID()));
    }
    public static void event(ServerLevel level,Holder<GameEvent> event,Vec3 position,GameEvent.Context context) {
        var scope=ACTIVE.get(); if (scope==null || !scope.accept(level)) return;
        if (scope.events.size()>=2 || context.sourceEntity()!=scope.player) { scope.fail("Native reference game-event bound/actor changed");return; }
        scope.order.add("event");scope.events.add(new Event(BuiltInRegistries.GAME_EVENT.getKey(event.value()),position,context.sourceEntity().getUUID(),context.affectedState()));
    }
    @Override public void close() {
        if (owner!=Thread.currentThread()) { fail("Native reference observation close is off owner thread");throw refused(failure); }
        if (closed || ACTIVE.get()!=this) throw refused("Native reference observation close identity changed");
        ACTIVE.remove();closed=true;
        if (failure!=null) throw refused(failure);
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
