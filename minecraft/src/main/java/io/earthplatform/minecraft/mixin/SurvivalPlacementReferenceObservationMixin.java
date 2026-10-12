package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.gametest.NativePlacementReferenceObservation;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Does nothing outside an explicit unbound native GameTest reference scope. */
@Mixin(ServerLevel.class)
public abstract class SurvivalPlacementReferenceObservationMixin {
    @Inject(method="playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",at=@At("HEAD"))
    private void earthplatform$observeReferenceSound(Player excluded,double x,double y,double z,Holder<SoundEvent> sound,
            SoundSource source,float volume,float pitch,long seed,CallbackInfo ci) {
        NativePlacementReferenceObservation.sound((ServerLevel)(Object)this,excluded,x,y,z,sound,source,volume,pitch,seed);
    }
    @Inject(method="gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V",at=@At("HEAD"))
    private void earthplatform$observeReferenceEvent(Holder<GameEvent> event,Vec3 position,GameEvent.Context context,CallbackInfo ci) {
        NativePlacementReferenceObservation.event((ServerLevel)(Object)this,event,position,context);
    }
}
