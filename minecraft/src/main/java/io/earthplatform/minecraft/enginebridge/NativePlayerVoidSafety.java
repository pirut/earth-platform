package io.earthplatform.minecraft.enginebridge;

import java.util.UUID;
import net.minecraft.world.phys.AABB;

/** Optional exact-player lease, separate from collision readiness and never general damage immunity. */
public interface NativePlayerVoidSafety {
    boolean preventsStockVoidCheck(UUID player,AABB body);
}
