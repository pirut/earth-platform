package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import kroppeb.stareval.expression.Expression;
import kroppeb.stareval.function.Type;
import net.irisshaders.iris.Iris;
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

    private FarIrisUniforms(ProgramUniforms uniforms, CustomUniforms custom, Object pass) {
        this.uniforms = uniforms;
        this.custom = custom;
        this.pass = pass;
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
        if (uniform == null) return null;
        return switch (uniform) {
            case INT -> "int";
            case FLOAT -> "float";
            case MAT3 -> "mat3";
            case MAT4 -> "mat4";
            case VEC2 -> "vec2";
            case VEC2I -> "ivec2";
            case VEC3 -> "vec3";
            case VEC3I -> "ivec3";
            case VEC4 -> "vec4";
            case VEC4I -> "ivec4";
        };
    }

    @Nullable private static CustomUniforms custom() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        return pipeline instanceof IrisRenderingPipeline iris ? iris.getCustomUniforms() : null;
    }

    /** Finds the pack's uniforms in a linked program, or null when there is no pack pipeline to take them from. */
    @Nullable static FarIrisUniforms create(int program, String name) {
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
            return new FarIrisUniforms(builder.buildUniforms(), custom, pass);
        } catch (RuntimeException failure) {
            EarthPlatformMod.LOGGER.warn("Could not give far terrain the shader pack's uniforms", failure);
            return null;
        }
    }

    /** Sets the uniforms on the program, which must be in use. */
    void apply() {
        uniforms.update();
        custom.push(pass);
    }

    /** Called once the program is no longer in use, as Iris does after its own programs. */
    static void done() {
        ProgramUniforms.clearActiveUniforms();
    }

    void close() {
        uniforms.removeListeners();
    }
}
