package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Default methods must preserve Y; the deprecated two-dimensional forms require the whole envelope. */
@Mixin(LevelReader.class)
interface SurvivalLevelReaderMixin {
    @Inject(method="hasChunkAt(Lnet/minecraft/core/BlockPos;)Z", at=@At("HEAD"), cancellable=true)
    private void earthplatform$survivalLoaded(BlockPos pos, CallbackInfoReturnable<Boolean> result) {
        if ((Object)this instanceof Level level) {
            var binding=SurvivalLevelBinding.find(level); if(binding!=null) result.setReturnValue(binding.isResident(pos));
        }
    }
    @Inject(method="hasChunkAt(II)Z", at=@At("HEAD"), cancellable=true)
    private void earthplatform$survivalColumn(int x, int z, CallbackInfoReturnable<Boolean> result) {
        if ((Object)this instanceof Level level) {
            var binding=SurvivalLevelBinding.find(level); if(binding!=null) result.setReturnValue(binding.hasResidentColumns(x,z,x,z));
        }
    }
    @Inject(method="hasChunksAt(IIIIII)Z", at=@At("HEAD"), cancellable=true)
    private void earthplatform$survivalBox(int x1,int y1,int z1,int x2,int y2,int z2,CallbackInfoReturnable<Boolean> result) {
        if ((Object)this instanceof Level level) {
            var binding=SurvivalLevelBinding.find(level); if(binding!=null) result.setReturnValue(binding.hasResidentBox(x1,y1,z1,x2,y2,z2));
        }
    }
    @Inject(method="hasChunksAt(IIII)Z", at=@At("HEAD"), cancellable=true)
    private void earthplatform$survivalColumns(int x1,int z1,int x2,int z2,CallbackInfoReturnable<Boolean> result) {
        if ((Object)this instanceof Level level) {
            var binding=SurvivalLevelBinding.find(level); if(binding!=null) result.setReturnValue(binding.hasResidentColumns(x1,z1,x2,z2));
        }
    }
    @Inject(method="getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;",at=@At("HEAD"),cancellable=true)
    private void earthplatform$survivalBiome(BlockPos pos,CallbackInfoReturnable<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> result) {
        if ((Object)this instanceof Level level && SurvivalLevelBinding.find(level)!=null) {
            var biome=io.earthplatform.minecraft.survival.gametest.NativePlayerTickProbeScope.environmentBiome(level,pos);
            if(biome!=null)result.setReturnValue(biome);else SurvivalLevelBinding.rejectTerrain(level,result.getId());
        }
    }
    @Inject(method="getNoiseBiome(III)Lnet/minecraft/core/Holder;",at=@At("HEAD"),cancellable=true)
    private void earthplatform$survivalNoiseBiome(int x,int y,int z,CallbackInfoReturnable<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> result) {
        if ((Object)this instanceof Level level && SurvivalLevelBinding.find(level)!=null) {
            var biome=io.earthplatform.minecraft.survival.gametest.NativePlayerTickProbeScope.environmentNoiseBiome(level,x,y,z);
            if(biome!=null)result.setReturnValue(biome);else SurvivalLevelBinding.rejectTerrain(level,result.getId());
        }
    }
}
