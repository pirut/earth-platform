package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.Cubic;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Aether lights its glowstone portal only in its configured return dimension (the Overworld) and
 * in the Aether itself. The Earth world counts as a return dimension here too, so a portal built in
 * Earth lights; {@link AetherPortalReturnMixin} brings the player back to it.
 */
@Pseudo
@Mixin(targets = "com.aetherteam.aether.event.hooks.DimensionHooks", remap = false)
abstract class AetherPortalCreationMixin {
    private static final String EARTHPLATFORM$RETURN_DIMENSION = "Lcom/aetherteam/aether/world/LevelUtil;returnDimension()Lnet/minecraft/resources/ResourceKey;";

    @WrapOperation(method = "createPortal", require = 0, at = @At(value = "INVOKE", target = EARTHPLATFORM$RETURN_DIMENSION))
    private static ResourceKey<Level> earthplatform$earthLights(Operation<ResourceKey<Level>> original, Player player, Level level, BlockPos pos,
                                                                 @Nullable Direction direction, ItemStack stack, InteractionHand hand) {
        return Cubic.isCubic(level.dimension()) ? level.dimension() : original.call();
    }

    @WrapOperation(method = "detectWaterInFrame", require = 0, at = @At(value = "INVOKE", target = EARTHPLATFORM$RETURN_DIMENSION))
    private static ResourceKey<Level> earthplatform$earthWaterLights(Operation<ResourceKey<Level>> original, LevelAccessor levelAccessor, BlockPos pos,
                                                                      BlockState state, FluidState fluid) {
        return levelAccessor instanceof Level level && Cubic.isCubic(level.dimension()) ? level.dimension() : original.call();
    }
}
