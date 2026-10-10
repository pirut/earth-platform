package io.earthplatform.minecraft.enginebridge;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Explicit unavailable-frame gate and bounded walking request; no world scan is needed. */
public interface NativeCollisionMotionFence {
    boolean blocksPlayerMovement();
    static boolean bounded(AABB body, Vec3 movement) {
        if (body == null || movement == null) return false;
        for (double v : new double[]{body.minX,body.minY,body.minZ,body.maxX,body.maxY,body.maxZ,movement.x,movement.y,movement.z})
            if (!Double.isFinite(v)) return false;
        return body.getXsize()>0 && body.getXsize()<=1 && body.getYsize()>0 && body.getYsize()<=2
                && body.getZsize()>0 && body.getZsize()<=1
                && Math.abs(movement.x)<=4 && Math.abs(movement.y)<=4 && Math.abs(movement.z)<=4;
    }
}
