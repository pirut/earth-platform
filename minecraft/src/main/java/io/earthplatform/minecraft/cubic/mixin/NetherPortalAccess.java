package io.earthplatform.minecraft.cubic.mixin;

import net.minecraft.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's placement of an entity in an exit portal, keeping its offset in the portal it left. */
@Mixin(NetherPortalBlock.class)
public interface NetherPortalAccess {
    @Invoker("getDimensionTransitionFromExit")
    static DimensionTransition earthplatform$exit(Entity entity, BlockPos pos, BlockUtil.FoundRectangle exit, ServerLevel level,
                                                 DimensionTransition.PostDimensionTransition post) {
        throw new AssertionError();
    }
}
