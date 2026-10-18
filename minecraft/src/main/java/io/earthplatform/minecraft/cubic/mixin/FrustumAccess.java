package io.earthplatform.minecraft.cubic.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The culling view can differ from the draw matrices, especially with a captured frustum. */
@Mixin(Frustum.class)
public interface FrustumAccess {
    @Accessor("matrix") Matrix4f earthplatform$matrix();
    @Accessor("intersection") FrustumIntersection earthplatform$intersection();
    @Accessor("camX") double earthplatform$camX();
    @Accessor("camY") double earthplatform$camY();
    @Accessor("camZ") double earthplatform$camZ();
}
