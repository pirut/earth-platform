package io.earthplatform.minecraft.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact 1.21.1 fields, sampled after all constructor format transforms and before emission. */
@Mixin(BufferBuilder.class)
public interface SceneBufferBuilderAccessor {
    @Accessor("format") VertexFormat earthplatform$format();
    @Accessor("vertexSize") int earthplatform$vertexSize();
}
