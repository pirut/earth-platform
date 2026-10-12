package io.earthplatform.compat.dh.shader;

/** Read-only scalars on the actual installed singleton's held terrain program. Implementations
 * supplied by callers are never accepted. Matrix objects and event parameters never escape. */
public interface SurvivalDhHookAccess {
    Object earthplatform$survivalDhInvocation();
    Object earthplatform$survivalDhLevel();
    boolean earthplatform$survivalDhOpaque();
    int earthplatform$survivalDhPass();
    float earthplatform$survivalDhClip(boolean far);
    double earthplatform$survivalDhCamera(int axis);
    float earthplatform$survivalDhMatrix(boolean projection,int column,int row);
}
