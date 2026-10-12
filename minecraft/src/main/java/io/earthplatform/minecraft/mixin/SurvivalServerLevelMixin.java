package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Carrier-specific terrain/weather seam. Ordinary entity/player ticks remain untouched. */
@Mixin(ServerLevel.class)
abstract class SurvivalServerLevelMixin {
    @Inject(method="tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V",at=@At("HEAD"),cancellable=true)
    private void earthplatform$survivalTerrainTick(LevelChunk chunk,int randomTickSpeed,CallbackInfo result) {
        var binding=SurvivalLevelBinding.find((ServerLevel)(Object)this);
        if(binding!=null){binding.tickCarrier(chunk,randomTickSpeed);result.cancel();}
    }
    @Inject(method="getUncachedNoiseBiome(III)Lnet/minecraft/core/Holder;",at=@At("HEAD"),cancellable=true)
    private void earthplatform$survivalUncachedBiome(int x,int y,int z,CallbackInfoReturnable<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> result) {
        var level=(ServerLevel)(Object)this;
        if(SurvivalLevelBinding.find(level)!=null) {
            var biome=io.earthplatform.minecraft.survival.gametest.NativePlayerTickProbeScope.environmentNoiseBiome(level,x,y,z);
            if(biome!=null)result.setReturnValue(biome);else SurvivalLevelBinding.rejectTerrain(level,result.getId());
        }
    }
}
