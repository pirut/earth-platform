package io.earthplatform.minecraft.survival.host;

import net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager;

/** Save-only inspection of native post-load and auxiliary-light ownership. */
public interface SurvivalCarrierLevelAccess {
    boolean earthplatform$carrierPostLoadEmpty();
    LevelChunkAuxiliaryLightManager earthplatform$carrierAuxLight();
}
