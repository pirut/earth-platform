package io.earthplatform.minecraft.fabric.mixin;

import io.earthplatform.minecraft.cubic.CubicEvents;
import io.earthplatform.minecraft.cubic.play.EarthBasics;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NeoForge's level save and sleep-finished events, where NeoForge fires them. */
@Mixin(ServerLevel.class)
abstract class ServerLevelHooksMixin {
    /** After the chunks and entities are written, as NeoForge's LevelEvent.Save. */
    @Inject(method = "save", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;autoSave()V", shift = At.Shift.AFTER))
    private void earthplatform$savedAuto(CallbackInfo info) { CubicEvents.save((ServerLevel) (Object) this); }

    @Inject(method = "save", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;saveAll()V", shift = At.Shift.AFTER))
    private void earthplatform$savedAll(CallbackInfo info) { CubicEvents.save((ServerLevel) (Object) this); }

    /** A night slept through, as NeoForge's SleepFinishedTimeEvent. */
    @ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setDayTime(J)V"))
    private long earthplatform$sleptThrough(long newTime) {
        EarthBasics.sleptThrough((ServerLevel) (Object) this, newTime);
        return newTime;
    }
}
