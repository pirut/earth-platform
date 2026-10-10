package io.earthplatform.minecraft.scene;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import java.util.Objects;

/** Draw-visible native readiness, collected only from current published meshes and their halo dependencies. */
public record SceneReadyCube(CubePos position,WorldIdentity world,long epoch,long leaseGeneration,long revision,long resourceGeneration) {
    public SceneReadyCube {
        Objects.requireNonNull(position);Objects.requireNonNull(world);
        if(epoch<0||leaseGeneration<=0||revision<0||resourceGeneration<=0)throw new IllegalArgumentException("Invalid native readiness stamp");
    }
}
