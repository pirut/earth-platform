package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import net.minecraft.world.phys.AABB;

/** Pure full-body admission; a permanent pin cannot make remote collision space known. */
final class RollingWindowAdmission {
    private RollingWindowAdmission() {}
    static boolean contains(AABB body, CubePos center) {
        if (body==null || center==null || !Double.isFinite(body.minX) || !Double.isFinite(body.minY)
                || !Double.isFinite(body.minZ) || !Double.isFinite(body.maxX) || !Double.isFinite(body.maxY)
                || !Double.isFinite(body.maxZ) || body.getXsize()<=0 || body.getXsize()>1
                || body.getYsize()<=0 || body.getYsize()>2 || body.getZsize()<=0 || body.getZsize()>1) return false;
        return body.minX>=center.minBlockX()-32 && body.maxX<=center.minBlockX()+48
            && body.minY>=center.minBlockY()-32 && body.maxY<=center.minBlockY()+48
            && body.minZ>=center.minBlockZ()-32 && body.maxZ<=center.minBlockZ()+48;
    }
}
