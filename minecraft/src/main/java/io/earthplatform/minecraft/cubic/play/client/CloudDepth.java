package io.earthplatform.minecraft.cubic.play.client;

import io.earthplatform.minecraft.cubic.client.FarBlockDepth;

/** The depth the layered clouds stop at, drawn with the game's projection. Render thread only. */
interface CloudDepth {
    /** This frame's depth texture, or 0 when there is none to clip against. */
    int texture();

    /** The game's depth as it stands when clouds draw: near blocks and, without a shader pack, far terrain. */
    CloudDepth SCENE = () -> FarBlockDepth.copy() ? FarBlockDepth.texture() : 0;
}
