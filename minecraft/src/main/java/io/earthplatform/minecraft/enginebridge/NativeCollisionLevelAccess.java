package io.earthplatform.minecraft.enginebridge;

import net.minecraft.world.level.BlockGetter;

/** A scoped collision-only view; null preserves the original vanilla dimension behavior. */
public interface NativeCollisionLevelAccess {
    BlockGetter earthplatform$coreCollisionView();
    boolean earthplatform$replaceCoreCollisionView(BlockGetter expected, BlockGetter replacement);
}
