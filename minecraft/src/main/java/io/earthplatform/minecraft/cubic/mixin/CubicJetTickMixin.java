package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.jet.JetStream;
import io.earthplatform.minecraft.cubic.jet.OceanCurrent;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A player in the jet stream keeps no chunks loaded (see {@link CubicJetTicketMixin}), so the level would skip its
 * tick: no hunger, no potion timers, and no end to the moment of immunity after a hit or a join, so nothing could
 * hurt it. It is ticked anyway; a player's tick does not move it, so it needs no blocks around. A boat the ocean
 * current carries is ticked too, and its rider with it: its rider's client moves it.
 */
@Mixin(ServerLevel.class)
abstract class CubicJetTickMixin {
    // The entity tick lambda: NeoForge keeps its source name, Fabric's named jar its intermediary one.
    @WrapOperation(method = {"lambda$tick$2(Lnet/minecraft/world/TickRateManager;Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/world/entity/Entity;)V",
            "method_31420(Lnet/minecraft/world/TickRateManager;Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/world/entity/Entity;)V"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager;inEntityTickingRange(J)Z"))
    private boolean earthplatform$tickJetRiders(DistanceManager manager, long chunk, Operation<Boolean> original,
                                                TickRateManager rates, ProfilerFiller profiler, Entity entity) {
        return original.call(manager, chunk) || entity instanceof ServerPlayer player && JetStream.aboveTerrain(player) || OceanCurrent.carried(entity);
    }
}
