package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.jet.JetStream;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Another player's glider, or the recorded player in a Replay Mod replay, eases toward each position the
 * server sends over three ticks, so it is drawn about three and a half ticks behind its real path, and
 * further behind while it speeds up or turns. At jet speed that is some 200 blocks, so a replay camera
 * written from the recorded path never caught it. In the cubic world a glider eases only over the ticks
 * between its updates: it moves as smoothly and lags by a short, steady time (in a replay, 1.5 ticks).
 */
@Mixin(LivingEntity.class)
abstract class CubicJetLerpMixin {
    @Unique private int earthplatform$lastUpdate;

    @ModifyVariable(method = "lerpTo", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int earthplatform$keepUp(int steps) {
        LivingEntity self = (LivingEntity) (Object) this;
        int since = self.tickCount - earthplatform$lastUpdate;
        earthplatform$lastUpdate = self.tickCount;
        return JetStream.remoteGlider(self) ? JetStream.easeSteps(steps, since) : steps;
    }
}
