package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import io.earthplatform.minecraft.EarthPlatformMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;

/** Plain far water shares one camera-relative warp; packs retain their own water shaders. */
public final class FarWaterShader {
    private static ShaderInstance base, shader;
    private static boolean failed;
    private FarWaterShader() {}

    static ShaderInstance get() {
        ShaderInstance current = GameRenderer.getRendertypeTranslucentShader();
        if (current != base) {
            if (shader != null) shader.close();
            shader = null; base = current; failed = false;
        }
        if (shader == null && !failed) {
            try { shader = load(Minecraft.getInstance().getResourceManager()); }
            catch (java.io.IOException | RuntimeException failure) {
                failed = true;
                EarthPlatformMod.LOGGER.warn("Continuous far-water shader unavailable; using the vanilla water path", failure);
            }
        }
        return shader;
    }

    public static ShaderInstance load(ResourceProvider resources) throws java.io.IOException {
        return new ShaderInstance(location -> resources.getResource(
                location.getPath().startsWith("shaders/core/earthplatform_water")
                        ? ResourceLocation.fromNamespaceAndPath("earthplatform", location.getPath()) : location),
                "earthplatform_water", DefaultVertexFormat.BLOCK);
    }
}
