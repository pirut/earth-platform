package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mekanism reads the world through WorldUtils. It bounds positions by the level's build height and
 * reads blocks straight from the chunk, so in the cubic level everything above Y 16 counted as
 * unloaded and the carrier chunks it read were empty. Multiblocks never formed and pipes never
 * connected. In the cubic level these reads go to the cubes instead.
 */
@Pseudo
@Mixin(targets = "mekanism.common.util.WorldUtils", remap = false)
abstract class MekanismWorldUtilsMixin {
    @Nullable
    private static CubeWorld earthplatform$cubes(@Nullable BlockGetter world) {
        return world instanceof Level level ? CubicLevel.of(level) : null;
    }

    @Inject(method = "isBlockLoaded(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$loaded(@Nullable BlockGetter world, BlockPos pos, CallbackInfoReturnable<Boolean> result) {
        CubeWorld cubes = earthplatform$cubes(world);
        if (cubes != null) result.setReturnValue(cubes.isLoaded(pos));
    }

    @Inject(method = "isBlockLoaded(Lnet/minecraft/world/level/BlockGetter;J)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$loadedPacked(@Nullable BlockGetter world, long pos, CallbackInfoReturnable<Boolean> result) {
        CubeWorld cubes = earthplatform$cubes(world);
        if (cubes != null) result.setReturnValue(cubes.isLoaded(BlockPos.of(pos)));
    }

    @Inject(method = "isBlockInBounds", at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$inBounds(@Nullable BlockGetter world, BlockPos pos, CallbackInfoReturnable<Boolean> result) {
        if (earthplatform$cubes(world) != null) result.setReturnValue(CubeWorld.inRange(pos.getY()));
    }

    @Inject(method = "getBlockState(Lnet/minecraft/world/level/LevelAccessor;Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;Lnet/minecraft/core/BlockPos;)Ljava/util/Optional;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$state(@Nullable LevelAccessor world, Long2ObjectMap<?> chunks, BlockPos pos, CallbackInfoReturnable<Optional<BlockState>> result) {
        CubeWorld cubes = earthplatform$cubes(world);
        if (cubes != null) result.setReturnValue(cubes.isLoaded(pos) ? Optional.of(cubes.getBlockState(pos)) : Optional.empty());
    }

    @Inject(method = "getFluidState(Lnet/minecraft/world/level/LevelAccessor;Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;Lnet/minecraft/core/BlockPos;)Ljava/util/Optional;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$fluid(@Nullable LevelAccessor world, Long2ObjectMap<?> chunks, BlockPos pos, CallbackInfoReturnable<Optional<FluidState>> result) {
        CubeWorld cubes = earthplatform$cubes(world);
        if (cubes != null) result.setReturnValue(cubes.isLoaded(pos) ? Optional.of(cubes.getFluidState(pos)) : Optional.empty());
    }

    @Inject(method = "getTileEntity(Lnet/minecraft/world/level/LevelAccessor;Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$tile(@Nullable LevelAccessor world, Long2ObjectMap<?> chunks, BlockPos pos, CallbackInfoReturnable<BlockEntity> result) {
        CubeWorld cubes = earthplatform$cubes(world);
        if (cubes != null) result.setReturnValue(cubes.isLoaded(pos) ? ((Level) world).getBlockEntity(pos) : null);
    }

    @Inject(method = "getTileEntity(Ljava/lang/Class;Lnet/minecraft/world/level/LevelAccessor;Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;Lnet/minecraft/core/BlockPos;Z)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$typedTile(Class<?> type, @Nullable LevelAccessor world, Long2ObjectMap<?> chunks, BlockPos pos, boolean logWrongType,
                                                CallbackInfoReturnable<BlockEntity> result) {
        CubeWorld cubes = earthplatform$cubes(world);
        if (cubes == null) return;
        BlockEntity tile = cubes.isLoaded(pos) ? ((Level) world).getBlockEntity(pos) : null;
        result.setReturnValue(type.isInstance(tile) ? tile : null);
    }
}
