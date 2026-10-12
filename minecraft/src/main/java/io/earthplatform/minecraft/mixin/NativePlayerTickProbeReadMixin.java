package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.gametest.NativePlayerTickProbeScope;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe and bound the actual cubic-owner read. Never cancel, replace or bypass its guards. */
@Mixin(value=SurvivalLevelBinding.class,remap=false)
abstract class NativePlayerTickProbeReadMixin {
    @Inject(method="reject(Ljava/lang/String;)V",at=@At("HEAD"),require=1)
    private void earthplatform$retainDiagnosticTerrainRefusal(String operation,CallbackInfo result) {
        NativePlayerTickProbeScope.beforeTerrainRefusal((SurvivalLevelBinding)(Object)this,operation);
    }
    @Inject(method="get(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",at=@At("HEAD"),require=1)
    private void earthplatform$beforeDiagnosticRead(BlockPos pos,CallbackInfoReturnable<BlockState> result) {
        NativePlayerTickProbeScope.beforeRead((SurvivalLevelBinding)(Object)this,pos);
    }
    @Inject(method="get(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",at=@At("RETURN"),require=1)
    private void earthplatform$afterDiagnosticRead(BlockPos pos,CallbackInfoReturnable<BlockState> result) {
        NativePlayerTickProbeScope.afterRead((SurvivalLevelBinding)(Object)this,pos,result.getReturnValue());
    }
}
