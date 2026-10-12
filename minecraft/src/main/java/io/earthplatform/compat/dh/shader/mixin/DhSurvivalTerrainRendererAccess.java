package io.earthplatform.compat.dh.shader.mixin;

import com.seibel.distanthorizons.common.render.openGl.GlDhTerrainRenderer_neoforge;
import com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram_neoforge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read only the already-held program. Never call the upstream lazy-creating getter. */
@Mixin(value=GlDhTerrainRenderer_neoforge.class,remap=false)
public interface DhSurvivalTerrainRendererAccess {
    @Accessor("terrainShaderProgram") GlDhTerrainShaderProgram_neoforge earthplatform$heldTerrainProgram();
}
