package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class SurvivalLevelChunkExtrasMixin implements io.earthplatform.minecraft.survival.host.SurvivalCarrierLevelAccess {
    @Shadow private LevelChunk.PostLoadProcessor postLoad;
    @Shadow @Final private net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager auxLightManager;
    @Override public boolean earthplatform$carrierPostLoadEmpty() { return postLoad == null; }
    @Override public net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager earthplatform$carrierAuxLight() {
        return auxLightManager;
    }
    @Shadow @Final private net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.block.Block> blockTicks;
    @Shadow @Final private net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.material.Fluid> fluidTicks;
    public net.minecraft.world.level.chunk.ChunkAccess.TicksToSave earthplatform$carrierTicks() {
        return new net.minecraft.world.level.chunk.ChunkAccess.TicksToSave(blockTicks,fluidTicks);
    }
    @Inject(method={"getBlockTicks()Lnet/minecraft/world/ticks/TickContainerAccess;",
            "getFluidTicks()Lnet/minecraft/world/ticks/TickContainerAccess;",
            "getTicksForSerialization()Lnet/minecraft/world/level/chunk/ChunkAccess$TicksToSave;"},at=@At("HEAD"))
    private void earthplatform$survivalTicks(CallbackInfoReturnable<?> result) {
        SurvivalLevelBinding.rejectTerrain(((LevelChunk)(Object)this).getLevel(),result.getId());
    }
    @Inject(method="getFluidState(III)Lnet/minecraft/world/level/material/FluidState;",at=@At("HEAD"),cancellable=true)
    private void earthplatform$survivalFluid(int x,int y,int z,CallbackInfoReturnable<FluidState> result) {
        var binding=SurvivalLevelBinding.find(((LevelChunk)(Object)this).getLevel());
        if(binding!=null) result.setReturnValue(binding.get(new BlockPos(x,y,z)).getFluidState());
    }
}
