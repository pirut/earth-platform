package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import kroppeb.stareval.function.Type;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;

/**
 * The shader pack's uniforms on the far terrain's own program, set the way Iris sets them on
 * Distant Horizons' programs: Iris's common uniforms, its replacements for the old built-ins, and
 * the pack's custom uniforms (such as Photon's {@code taa_offset}). Only loaded with Iris.
 */
final class FarIrisUniforms {
    /** Iris uniforms a {@code taaOffset} may read that are not the pack's own, with their GLSL types. */
    private static final Map<String, String> BUILT_IN = Map.of(
            "viewWidth", "float", "viewHeight", "float", "aspectRatio", "float",
            "frameCounter", "int", "frameTimeCounter", "float", "frameTime", "float");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final ProgramUniforms uniforms;
    private final CustomUniforms custom;
    /** Which of the pipeline's programs the custom uniforms' locations belong to. */
    private final Object pass;
    /** The pack's samplers its Voxy code reads, bound as Iris binds its own programs'; null when it reads none. */
    @Nullable private final ProgramSamplers samplers;

    private FarIrisUniforms(ProgramUniforms uniforms, CustomUniforms custom, Object pass, @Nullable ProgramSamplers samplers) {
        this.uniforms = uniforms;
        this.custom = custom;
        this.pass = pass;
        this.samplers = samplers;
    }

    /**
     * Texture units the far terrain's programs keep for themselves (see {@link FarGpu} and
     * {@link FarLod}); the pack's samplers take the units above them, as many as the GPU has.
     */
    private static final Set<Integer> OWN_UNITS = Set.of(0, 1, 2, 3, 4, 5, 6, 7);

    /** A pack's Voxy code reads more samplers than the GPU has texture units left for it. */
    static final class TooManySamplers extends RuntimeException {
        TooManySamplers(Throwable cause) { super(cause.getMessage(), cause); }
    }

    /**
     * The GLSL types of the named uniforms, as Iris would give them to the pack's programs: its
     * common uniforms (with far terrain's own Voxy ones, such as {@code vxModelView}), its
     * replacements for the old built-ins, and the pack's custom uniforms. Names it doesn't know
     * are left out.
     */
    static Map<String, String> types(java.util.Collection<String> names) {
        Map<String, String> types = new java.util.HashMap<>();
        if (names.isEmpty()) return types;
        WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        if (!(current instanceof IrisRenderingPipeline pipeline) || pack == null) return types;
        ProgramSet programs = pack.getProgramSet(Iris.getCurrentDimension());
        if (programs == null) return types;
        Map<String, UniformType> found = new java.util.HashMap<>();
        net.irisshaders.iris.gl.uniform.DynamicLocationalUniformHolder recorder = new net.irisshaders.iris.gl.uniform.DynamicLocationalUniformHolder() {
            @Override public net.irisshaders.iris.gl.uniform.DynamicLocationalUniformHolder addDynamicUniform(
                    net.irisshaders.iris.gl.uniform.Uniform uniform, net.irisshaders.iris.gl.state.ValueUpdateNotifier notifier) { return this; }
            @Override public net.irisshaders.iris.gl.uniform.LocationalUniformHolder addUniform(
                    net.irisshaders.iris.gl.uniform.UniformUpdateFrequency frequency, net.irisshaders.iris.gl.uniform.Uniform uniform) { return this; }
            @Override public java.util.OptionalInt location(String name, UniformType type) {
                found.putIfAbsent(name, type);
                return java.util.OptionalInt.empty();
            }
            @Override public net.irisshaders.iris.gl.uniform.UniformHolder externallyManagedUniform(String name, UniformType type) {
                found.putIfAbsent(name, type);
                return this;
            }
        };
        try {
            CustomUniforms custom = pipeline.getCustomUniforms();
            CommonUniforms.addCommonUniforms(recorder, pack.getIdMap(), programs.getPackDirectives(), pipeline.getFrameUpdateNotifier(), FogMode.PER_VERTEX);
            BuiltinReplacementUniforms.addBuiltinReplacementUniforms(recorder);
            for (String name : names) {
                String type = custom.hasVariable(name) && custom.getVariable(name) instanceof CachedUniform uniform ? glsl(uniform.getType())
                        : found.containsKey(name) ? glsl(found.get(name)) : null;
                if (type != null) types.put(name, type);
            }
        } catch (RuntimeException failure) {
            EarthPlatformMod.LOGGER.warn("Could not work out the shader pack's uniform types for far terrain", failure);
        }
        return types;
    }

    @Nullable private static String glsl(UniformType uniform) {
        String name = uniform.name().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith("i") && name.startsWith("vec") ? "i" + name.substring(0, name.length() - 1) : name;
    }

    /** The pipeline the pack's uniforms belong to; programs built for another one must be rebuilt. */
    @Nullable static Object pipeline() {
        return Iris.getPipelineManager().getPipelineNullable();
    }

    /**
     * GLSL for {@code earth_taaOffset()}: declarations of the uniforms the pack's {@code taaOffset}
     * body reads, then the function. Names it reads that are neither the pack's uniforms nor Iris's
     * are left undeclared; if they are not GLSL's own, the shader fails to build and goes without.
     */
    @Nullable static String jitter(@Nullable String body) {
        CustomUniforms custom = custom();
        if (body == null || custom == null) return null;
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = NAME.matcher(body);
        while (matcher.find()) names.add(matcher.group());
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            String type = BUILT_IN.get(name);
            if (custom.hasVariable(name) && custom.getVariable(name) instanceof CachedUniform uniform) type = glsl(uniform.getType());
            if (type != null) out.append("uniform ").append(type).append(' ').append(name).append(";\n");
        }
        return out.append("vec2 earth_taaOffset() ").append(body).append('\n').toString();
    }

    @Nullable private static String glsl(Type type) {
        UniformType uniform = Type.convert(type);
        // Iris names its types as GLSL does, in capitals, with integer vectors marked by a trailing I.
        return uniform == null ? null : glsl(uniform);
    }

    @Nullable private static CustomUniforms custom() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        return pipeline instanceof IrisRenderingPipeline iris ? iris.getCustomUniforms() : null;
    }

    /**
     * Finds the pack's uniforms in a linked program, and with {@code samplers} its samplers, or
     * null when there is no pack pipeline to take them from. Throws {@link TooManySamplers} when
     * the pack's samplers don't fit in the texture units left.
     */
    @Nullable static FarIrisUniforms create(int program, String name, boolean samplers) {
        WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        if (!(current instanceof IrisRenderingPipeline pipeline) || pack == null) return null;
        ProgramSet programs = pack.getProgramSet(Iris.getCurrentDimension());
        if (programs == null) return null;
        try {
            CustomUniforms custom = pipeline.getCustomUniforms();
            ProgramUniforms.Builder builder = ProgramUniforms.builder(name, program);
            CommonUniforms.addCommonUniforms(builder, pack.getIdMap(), programs.getPackDirectives(),
                    pipeline.getFrameUpdateNotifier(), FogMode.PER_VERTEX);
            custom.assignTo(builder);
            BuiltinReplacementUniforms.addBuiltinReplacementUniforms(builder);
            Object pass = new Object();
            custom.mapholderToPass(builder, pass);
            ProgramSamplers bound = null;
            if (samplers) {
                // As Iris gives its own terrain programs their samplers: render targets, shadows, noise, custom textures.
                ProgramSamplers.Builder holder = ProgramSamplers.builder(program, OWN_UNITS);
                net.irisshaders.iris.gl.image.ImageHolder images = new net.irisshaders.iris.gl.image.ImageHolder() {
                    @Override public boolean hasImage(String image) { return false; }
                    @Override public void addTextureImage(java.util.function.IntSupplier texture,
                            net.irisshaders.iris.gl.texture.InternalTextureFormat format, String image) {}
                };
                try {
                    pipeline.addGbufferOrShadowSamplers(holder, images, pipeline::getFlippedAfterPrepare, false, true, true, false);
                    bound = holder.build();
                } catch (IllegalStateException full) {
                    throw new TooManySamplers(full);
                }
            }
            return new FarIrisUniforms(builder.buildUniforms(), custom, pass, bound);
        } catch (TooManySamplers full) {
            throw full;
        } catch (RuntimeException failure) {
            EarthPlatformMod.LOGGER.warn("Could not give far terrain the shader pack's uniforms", failure);
            return null;
        }
    }

    /** Sets the uniforms on the program, which must be in use. */
    void apply() {
        uniforms.update();
        custom.push(pass);
        if (samplers != null) samplers.update();
    }

    /** Called once the program is no longer in use, as Iris does after its own programs. */
    void done() {
        ProgramUniforms.clearActiveUniforms();
        if (samplers != null) ProgramSamplers.clearActiveSamplers();
    }

    void close() {
        uniforms.removeListeners();
        if (samplers != null) samplers.removeListeners();
    }
}
