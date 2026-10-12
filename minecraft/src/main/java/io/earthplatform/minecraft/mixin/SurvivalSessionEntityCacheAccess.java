package io.earthplatform.minecraft.mixin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Entity.class)
public interface SurvivalSessionEntityCacheAccess {
 @Accessor("inBlockState") void earthplatform$clearInBlockState(BlockState state);
 @Accessor("onGroundNoBlocks") void earthplatform$setOnGroundNoBlocks(boolean value);
}
