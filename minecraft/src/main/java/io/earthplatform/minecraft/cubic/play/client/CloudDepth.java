package io.earthplatform.minecraft.cubic.play.client;

import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.client.FarBlockDepth;
import io.earthplatform.minecraft.cubic.client.FarRenderer;
import javax.annotation.Nullable;
import org.joml.Matrix4f;

/** A depth the layered clouds stop at. Render thread only. */
interface CloudDepth {
    /** This frame's depth texture, or 0 when there is none to clip against. */
    int texture();

    /** The projection {@link #texture} was drawn with, given the game's. */
    default Matrix4f projection(Matrix4f game) { return game; }

    /** The game's depth as it stands when clouds draw: near blocks and, without a shader pack, far terrain. */
    CloudDepth SCENE = () -> FarBlockDepth.copy() ? FarBlockDepth.texture() : 0;

    /**
     * Far terrain alone, in its own finer depth, when it drew so in this pass (see
     * {@code earthplatform.far.plainDepth}): where clouds meet a far slope, the game's depth is a
     * hundred blocks or more out.
     */
    CloudDepth FAR = new CloudDepth() {
        @Override public int texture() {
            FarRenderer far = far();
            return far == null ? 0 : far.plainDepthTexture();
        }

        @Override public Matrix4f projection(Matrix4f game) {
            FarRenderer far = far();
            return far == null ? game : far.plainDepthProjection();
        }
    };

    /** The far terrain of the world the player is in, if it is an Earth world. */
    @Nullable static FarRenderer far() {
        ClientCubeWorld world = ClientCubeWorld.current();
        return world == null ? null : world.far();
    }
}
