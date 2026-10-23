package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.Cubic;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;

/**
 * A shader pack's Voxy fragment code ({@code voxy_opaque.glsl}), which tells Voxy how the pack
 * wants far terrain written into its buffers, and the temporal anti-aliasing jitter its
 * {@code voxy.json} asks for. Iris itself ignores both files; mixins have it load them with the
 * pack's settings applied, and keep them here per program set (one per dimension folder) for the
 * far terrain's shader. Only loaded with Iris.
 */
public final class FarPackPatch {
    /** Files Iris should read from each dimension folder along with its own. */
    public static final String[] FILES = {"voxy.json", "voxy_opaque.glsl"};
    private static final Map<ProgramSet, FarShaders.PackPatch> PATCHES = Collections.synchronizedMap(new WeakHashMap<>());

    private FarPackPatch() {}

    /** Called as Iris builds a program set: keeps the folder's preprocessed Voxy fragment code, if any. */
    public static void capture(ProgramSet programs, AbsolutePackPath directory, Function<AbsolutePackPath, String> sources) {
        try {
            String patch = sources.apply(directory.resolve("voxy_opaque.glsl"));
            if (patch == null || patch.isBlank()) return;
            String settings = sources.apply(directory.resolve("voxy.json"));
            String jitter = FarShaders.taaOffset(settings);
            PATCHES.put(programs, new FarShaders.PackPatch(patch, jitter, FarShaders.voxyUniforms(settings), FarShaders.voxySamplers(settings)));
            Cubic.LOGGER.info("Shader pack folder {} has Voxy fragment code ({} characters) for far terrain{}",
                    directory.getPathString(), patch.length(), jitter == null ? "" : ", with its TAA jitter");
        } catch (RuntimeException failure) {
            Cubic.LOGGER.warn("Could not read the shader pack's Voxy fragment code in {}", directory.getPathString(), failure);
        }
    }

    /** The active pack's Voxy code for the current dimension, or null when it has none. */
    @Nullable static FarShaders.PackPatch current() {
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        if (pack == null) return null;
        ProgramSet programs = pack.getProgramSet(Iris.getCurrentDimension());
        return programs == null ? null : PATCHES.get(programs);
    }
}
