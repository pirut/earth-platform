package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;

/**
 * Far terrain under a shader pack with neither Voxy nor Distant Horizons support (such as SEUS
 * Renewed). Drawing far terrain with a single-output fallback leaves the pack's remaining G-buffer
 * render targets unwritten, so its deferred and composite passes read corrupt data (often turning
 * distant terrain solid white). Instead, far terrain runs the pack's own solid terrain program
 * ({@code gbuffers_terrain_solid} / {@code gbuffers_terrain} / {@code gbuffers_textured_lit} /
 * {@code gbuffers_textured}) into the already-bound terrain framebuffer, unpacking each far face
 * into the vertex inputs Iris's vanilla patcher expects (see {@link FarShaders#terrainPatch}). Only
 * loaded with Iris.
 */
public final class FarTerrainPack {
    /** {@code -Dearthplatform.far.terrainPacks=false} leaves such packs on the plain single-output fallback. */
    static final boolean ENABLED = !"false".equals(System.getProperty("earthplatform.far.terrainPacks"));

    private static final ProgramId[] CANDIDATES = {
            ProgramId.TerrainSolid, ProgramId.Terrain, ProgramId.TexturedLit, ProgramId.Textured};

    private static final Map<ProgramSet, Optional<FarShaders.PackPatch>> PATCHES = Collections.synchronizedMap(new WeakHashMap<>());

    private FarTerrainPack() {}

    /** The pack code for a program set from its solid terrain program, or null when it has none that can be used. */
    @Nullable static FarShaders.PackPatch patch(ProgramSet programs) {
        if (!ENABLED) return null;
        return PATCHES.computeIfAbsent(programs, FarTerrainPack::build).orElse(null);
    }

    private static Optional<FarShaders.PackPatch> build(ProgramSet programs) {
        try {
            ProgramSource source = null;
            for (ProgramId id : CANDIDATES) {
                ProgramSource found = programs.get(id).orElse(null);
                if (found != null && found.getVertexSource().isPresent() && found.getFragmentSource().isPresent()) {
                    source = found;
                    break;
                }
            }
            if (source == null) return Optional.empty();
            if (source.getGeometrySource().isPresent() || source.getTessControlSource().isPresent() || source.getTessEvalSource().isPresent()) {
                EarthPlatformMod.LOGGER.warn("The shader pack's terrain program ({}) has geometry or tessellation shaders, which far terrain can't run; using plain colors",
                        source.getName());
                return Optional.empty();
            }
            Map<PatchShaderType, String> patched = TransformPatcher.patchVanilla(source.getName() + " (far terrain)",
                    source.getVertexSource().orElseThrow(), null, null, null, source.getFragmentSource().orElseThrow(),
                    AlphaTest.ALWAYS, false, false, new ShaderAttributeInputs(true, true, false, true, true),
                    programs.getPackDirectives().getTextureMap());
            int outputs = source.getDirectives().getDrawBuffers().length;
            FarShaders.PackPatch patch = outputs == 0 ? null
                    : FarShaders.terrainPatch(patched.get(PatchShaderType.VERTEX), patched.get(PatchShaderType.FRAGMENT), outputs);
            if (patch == null) EarthPlatformMod.LOGGER.warn("The shader pack's terrain program ({}) is not in a form far terrain can use; using plain colors",
                    source.getName());
            return Optional.ofNullable(patch);
        } catch (RuntimeException failure) {
            EarthPlatformMod.LOGGER.warn("Could not read the shader pack's terrain program for far terrain; using plain colors", failure);
            return Optional.empty();
        }
    }

    /** Called when a pack's terrain program doesn't build for far terrain: it then falls back to plain colours. */
    static void failed(FarShaders.PackPatch patch) {
        synchronized (PATCHES) {
            PATCHES.replaceAll((programs, held) -> held.isPresent() && held.get() == patch ? Optional.empty() : held);
        }
    }
}
