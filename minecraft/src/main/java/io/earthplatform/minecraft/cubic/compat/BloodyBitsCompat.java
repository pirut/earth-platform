package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Origin-rebasing helpers for Bloody Bits blood spatter decals.
 *
 * <p>{@code BloodSpatterParticle.renderBlockDecal} narrows absolute world coordinates to 32-bit
 * floats before subtracting the camera position. At Earth coordinates (~12.5M blocks from the
 * origin) one float32 ULP is a full block, making decals drift with the camera and snap back.
 * Subtracting the integer camera block from the block position, extents, center and camera keeps
 * every float32 value within a few blocks of zero.
 */
public final class BloodyBitsCompat {
    private BloodyBitsCompat() {}

    public static boolean shouldRebase() {
        var level = Minecraft.getInstance().level;
        return level != null && CubicLevel.of(level) != null;
    }

    public static int originX(Vec3 camera) { return Mth.floor(camera.x); }
    public static int originY(Vec3 camera) { return Mth.floor(camera.y); }
    public static int originZ(Vec3 camera) { return Mth.floor(camera.z); }

    public static boolean isZeroOrigin(int ox, int oy, int oz) {
        return ox == 0 && oy == 0 && oz == 0;
    }
}
