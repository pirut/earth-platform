package io.earthplatform.minecraft.coregate;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.world.phys.AABB;

/** Exact session/player bounded native-height safety; retained through reload/edit/failed return. */
final class CorePlayerSafetyLease {
    private final UUID player;
    private final long epoch;
    private final RollingSessionDescriptor descriptor;
    private boolean closed;
    CorePlayerSafetyLease(UUID player,long epoch){this.player=Objects.requireNonNull(player);if(epoch<=0)throw new IllegalArgumentException("Positive epoch required");this.epoch=epoch;this.descriptor=null;}
    CorePlayerSafetyLease(UUID player,long epoch,RollingSessionDescriptor descriptor){this.player=Objects.requireNonNull(player);if(epoch<=0)throw new IllegalArgumentException("Positive epoch required");this.epoch=epoch;this.descriptor=Objects.requireNonNull(descriptor);}
    boolean contains(UUID candidate,AABB body){
        return contains(candidate,epoch,body);
    }
    boolean contains(UUID candidate,long candidateEpoch,AABB body){
        if(closed||candidateEpoch!=epoch||!player.equals(candidate)||!RollingSessionDescriptor.finitePlayerBody(body))return false;
        if(descriptor!=null)return descriptor.containsBody(body);
        return body.minX>=-512&&body.maxX<=512&&body.minZ>=-512&&body.maxZ<=512
            &&body.minY>=CoreGateProfile.GAMEPLAY_MIN_Y&&body.maxY<=CoreGateProfile.GAMEPLAY_MAX_Y_EXCLUSIVE;
    }
    boolean ownedBy(UUID candidate){return !closed&&player.equals(candidate);}
    long epoch(){return epoch;}
    void close(){closed=true;}
}
