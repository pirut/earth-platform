package io.earthplatform.minecraft.scene;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;

/** Getter-only actual LevelRenderer state. Callers never supply an implementation to the frame minter.
 * Matrix values are copied as bounded scalars; no mutable upstream matrix or array escapes. */
public interface SurvivalRenderHookAccess {
    Object earthplatform$survivalFrame();
    Object earthplatform$survivalPreparation();
    double earthplatform$survivalPreparationCamera(int axis);
    float earthplatform$survivalPreparationMatrix(boolean projection,int column,int row);
    Object earthplatform$survivalInvocation();
    ClientLevel earthplatform$survivalLevel();
    RenderType earthplatform$survivalLayer();
    double earthplatform$survivalCamera(int axis);
    float earthplatform$survivalMatrix(boolean projection, int column, int row);
}
