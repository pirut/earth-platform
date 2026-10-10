package io.earthplatform.minecraft.coregate;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.world.phys.AABB;

/** Exact session/player bounded native-height safety; retained through reload/edit/failed return. */
final class CorePlayerSafetyLease {
    private final UUID player;
    private final long epoch;
    private boolean closed;
    CorePlayerSafetyLease(UUID player,long epoch){this.player=Objects.requireNonNull(player);if(epoch<=0)throw new IllegalArgumentException("Positive epoch required");this.epoch=epoch;}
    boolean contains(UUID candidate,AABB body){
        if(closed||!player.equals(candidate)||body==null)return false;
        for(double v:new double[]{body.minX,body.minY,body.minZ,body.maxX,body.maxY,body.maxZ})if(!Double.isFinite(v))return false;
        return body.getXsize()>0&&body.getXsize()<=1&&body.getYsize()>0&&body.getYsize()<=2&&body.getZsize()>0&&body.getZsize()<=1
            &&body.minX>=-512&&body.maxX<=512&&body.minZ>=-512&&body.maxZ<=512
            &&body.minY>=CoreGateProfile.GAMEPLAY_MIN_Y&&body.maxY<=CoreGateProfile.GAMEPLAY_MAX_Y_EXCLUSIVE;
    }
    boolean ownedBy(UUID candidate){return !closed&&player.equals(candidate);}
    long epoch(){return epoch;}
    void close(){closed=true;}
}
