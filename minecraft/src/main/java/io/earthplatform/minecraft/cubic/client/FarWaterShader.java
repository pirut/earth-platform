package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.io.IOException;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;

/**
 * Far water under the Earth's curve, without a shader pack: the game's translucent shader, with
 * each vertex dropped by the same curve as far terrain's (see {@link EarthView}) and drawn a little
 * nearer in depth rather than shrunk toward the camera, which moved each column's edges apart.
 * Render thread only.
 */
final class FarWaterShader {
    private static ShaderInstance base, shader;
    private static boolean failed;

    private FarWaterShader() {}

    /** The shader, loaded again after a resource reload replaces the game's; null if it failed to load. */
    @Nullable static ShaderInstance get() {
        ShaderInstance current = GameRenderer.getRendertypeTranslucentShader();
        if (current != base) {
            if (shader != null) shader.close();
            shader = null;
            base = current;
            failed = false;
        }
        if (shader == null && !failed) {
            try { shader = load(Minecraft.getInstance().getResourceManager()); }
            catch (IOException | RuntimeException failure) {
                failed = true;
                EarthPlatformMod.LOGGER.warn("The curved far water shader could not load; the horizon stays flat", failure);
            }
        }
        return shader;
    }

    /** Loads the shader from the mod's namespace; the String constructor works on both loaders. */
    static ShaderInstance load(ResourceProvider resources) throws IOException {
        return new ShaderInstance(location -> resources.getResource(
                location.getPath().startsWith("shaders/core/earthplatform_water")
                        ? ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, location.getPath()) : location),
                "earthplatform_water", DefaultVertexFormat.BLOCK);
    }
}
