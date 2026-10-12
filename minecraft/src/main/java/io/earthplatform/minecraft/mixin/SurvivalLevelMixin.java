package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.host.SurvivalLevelAccess;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact HEAD gates precede vanilla build-height checks, chunk access and side effects. */
@Mixin(Level.class)
abstract class SurvivalLevelMixin implements SurvivalLevelAccess {
    @Shadow @Final private Thread thread;
    @Unique private SurvivalLevelBinding earthplatform$survival;
    @Override public SurvivalLevelBinding earthplatform$survivalBinding() {
        if (earthplatform$survival != null && Thread.currentThread() != thread)
            throw new SurvivalRefusal("Survival Level accessed outside its server thread");
        return earthplatform$survival;
    }
    @Override public void earthplatform$bindSurvival(SurvivalLevelBinding binding) {
        if (Thread.currentThread() != thread || earthplatform$survival != null)
            throw new SurvivalRefusal("Survival Level is already bound or not on its server thread");
        Objects.requireNonNull(binding).attachTo((Level)(Object)this);
        earthplatform$survival = binding;
    }
    @Override public void earthplatform$unbindSurvival(SurvivalLevelBinding expected) {
        if (Thread.currentThread() != thread || expected == null || earthplatform$survival != expected)
            throw new SurvivalRefusal("Survival Level detach ownership mismatch");
        expected.detachFrom((Level)(Object)this);
        // Keep the exact detached identity as a tombstone. Never fall back to vanilla terrain.
    }
    @Inject(method="getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", at=@At("HEAD"), cancellable=true)
    private void earthplatform$survivalGet(BlockPos pos, CallbackInfoReturnable<BlockState> result) {
        if (earthplatform$survival != null) result.setReturnValue(earthplatform$survival.get(pos));
    }
    @Inject(method="getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;", at=@At("HEAD"), cancellable=true)
    private void earthplatform$survivalFluid(BlockPos pos, CallbackInfoReturnable<FluidState> result) {
        if (earthplatform$survival != null) result.setReturnValue(earthplatform$survival.get(pos).getFluidState());
    }
    @Inject(method="getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;", at=@At("HEAD"), cancellable=true)
    private void earthplatform$survivalEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> result) {
        if (earthplatform$survival != null) result.setReturnValue(earthplatform$survival.view().getBlockEntity(pos));
    }
    @Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at=@At("HEAD"), cancellable=true)
    private void earthplatform$survivalSet(BlockPos pos, BlockState state, int flags, int depth, CallbackInfoReturnable<Boolean> result) {
        if (earthplatform$survival != null) {
            BlockState before = earthplatform$survival.set(pos, state, flags, depth);
            result.setReturnValue(before != null && before != state);
        }
    }
    @Inject(method="removeBlock(Lnet/minecraft/core/BlockPos;Z)Z", at=@At("HEAD"), cancellable=true)
    private void earthplatform$survivalRemove(BlockPos pos, boolean moving, CallbackInfoReturnable<Boolean> result) {
        if (earthplatform$survival != null) {
            BlockState replacement = earthplatform$survival.get(pos).getFluidState().createLegacyBlock();
            BlockState before = earthplatform$survival.set(pos, replacement, 3 | (moving ? 64 : 0), 512);
            result.setReturnValue(before != null && before != replacement);
        }
    }
    @Inject(method="destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at=@At("HEAD"))
    private void earthplatform$survivalDestroy(BlockPos pos, boolean drops, Entity entity, int depth, CallbackInfoReturnable<Boolean> result) {
        if (earthplatform$survival != null) earthplatform$survival.reject("raw destroyBlock before drops/events; use admitted native preparation");
    }
    @Inject(method="isLoaded(Lnet/minecraft/core/BlockPos;)Z", at=@At("HEAD"), cancellable=true)
    private void earthplatform$survivalLoaded(BlockPos pos, CallbackInfoReturnable<Boolean> result) {
        if (earthplatform$survival != null) result.setReturnValue(earthplatform$survival.isResident(pos));
    }
    @Inject(method="getChunkForCollisions(II)Lnet/minecraft/world/level/BlockGetter;", at=@At("HEAD"), cancellable=true)
    private void earthplatform$survivalCollision(int x, int z, CallbackInfoReturnable<BlockGetter> result) {
        if (earthplatform$survival != null) result.setReturnValue(earthplatform$survival.view());
    }
    @Inject(method="loadedAndEntityCanStandOnFace(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Direction;)Z", at=@At("HEAD"), cancellable=true)
    private void earthplatform$survivalStand(BlockPos pos, Entity entity, Direction direction, CallbackInfoReturnable<Boolean> result) {
        if (earthplatform$survival != null) result.setReturnValue(earthplatform$survival.isResident(pos)
                && earthplatform$survival.get(pos).entityCanStandOnFace((Level)(Object)this, pos, entity, direction));
    }
    @Inject(method={"getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I",
            "getLightEngine()Lnet/minecraft/world/level/lighting/LevelLightEngine;"}, at=@At("HEAD"))
    private void earthplatform$survivalUnsupportedRead(CallbackInfoReturnable<?> result) {
        if (earthplatform$survival != null) earthplatform$survival.reject(result.getId());
    }
    @Inject(method={"setBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            "removeBlockEntity(Lnet/minecraft/core/BlockPos;)V",
            "markAndNotifyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;II)V"}, at=@At("HEAD"))
    private void earthplatform$survivalUnsupportedWrite(CallbackInfo result) {
        if (earthplatform$survival != null) earthplatform$survival.reject(result.getId());
    }
}
