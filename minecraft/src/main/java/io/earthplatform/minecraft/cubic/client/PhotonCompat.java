package io.earthplatform.minecraft.cubic.client;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;

/**
 * Lets an unmodified Photon pack run on OpenGL drivers it doesn't fit, such as Apple's OpenGL 4.1:
 * 16 texture units (Photon's deferred4 samples 15 textures and Iris keeps 2 units for itself) and
 * no compute shaders (Photon computes its sky light, colored lights and voxel data with them).
 *
 * <p>When Iris loads a Photon pack on such a driver, the pack is copied into the game directory with
 * the few edits it needs and Iris reads the copy; the pack the player installed is never touched.
 * Its options keep the pack's name, so they carry over. Colored Shadows, Cloud Shadows and Colored
 * Lights are off, and the sky light is computed by a fragment pass instead of a compute pass.
 * Drivers that run Photon as it is get it as it is. {@code -Dearthplatform.photon.compat=on} or
 * {@code off} overrides the check.
 */
public final class PhotonCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Bumped whenever the edits change, so older copies are rebuilt. */
    private static final int VERSION = 1;
    private static final String MODE = System.getProperty("earthplatform.photon.compat", "auto");

    private PhotonCompat() {}

    /** The pack root Iris should read: {@code root} itself, or the adapted copy when this driver needs one. */
    public static Path adapt(Path root) {
        try {
            if (!isPhoton(root) || !needed()) return root;
            Path copy = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("earthplatform").resolve("photon-compat").resolve(fingerprint(root)).resolve("shaders");
            if (!Files.isRegularFile(copy.resolve(DONE))) build(root, copy);
            LOGGER.info("Running Photon with the edits this OpenGL driver needs, from {}", copy);
            return copy;
        } catch (IOException | UncheckedIOException | IllegalStateException failure) {
            LOGGER.warn("Could not adapt Photon to this OpenGL driver; loading it as it is", failure);
            return root;
        }
    }

    private static boolean isPhoton(Path root) {
        return Files.isRegularFile(root.resolve("world0/deferred4_a.csh")) && Files.isRegularFile(root.resolve("program/d4_deferred_shading.fsh"));
    }

    private static boolean needed() {
        if (MODE.equals("on")) return true;
        if (MODE.equals("off")) return false;
        GLCapabilities caps = GL.getCapabilities();
        boolean compute = caps.OpenGL43 || caps.GL_ARB_compute_shader;
        return !compute || GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS) < 17;
    }

    private static final String DONE = ".earthplatform-compat";

    /** Names the copy after the pack's contents and these edits, so a changed pack gets a fresh copy. */
    private static String fingerprint(Path root) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("v" + VERSION).getBytes(StandardCharsets.UTF_8));
            for (Path file : files(root)) {
                digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<Path> files(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    static void build(Path root, Path copy) throws IOException {
        Path staging = copy.resolveSibling("shaders.partial");
        deleteTree(staging);
        for (Path file : files(root)) {
            Path target = staging.resolve(root.relativize(file).toString());
            Files.createDirectories(target.getParent());
            Files.copy(file, target);
        }
        // Colored shadows and cloud shadows each add samplers to passes that are already at the limit.
        edit(staging, "settings.glsl", text -> replace(text, "(?m)^([ \\t]*)#define (SHADOW_COLOR|CLOUD_SHADOWS)[ \\t]*$", "$1//#define $2", 2));
        // Reflections in deferred4 read last frame's fog (colortex7); without it the pass fits 14 units.
        edit(staging, "include/lighting/specular_lighting.glsl", text -> replace(text,
                "(?m)^([ \\t]*)(vec3 fog_scattering_previous = texture\\(colortex7, hit_uv_prev\\)\\.rgb;)[ \\t]*$",
                "#ifdef EARTH_NO_FOG_HISTORY\n$1const vec3 fog_scattering_previous = vec3(0.0);\n#else\n$1$2\n#endif", 1));
        edit(staging, "program/d4_deferred_shading.fsh", text -> replace(text,
                "(#include \"/include/global.glsl\"\\n)", "$1\n#define EARTH_NO_FOG_HISTORY\n", 1));
        // The cloud shadow map's pass refuses to compile without Cloud Shadows; it is switched off then instead.
        edit(staging, "program/p0_clouds_prep.vsh", text -> replace(text, "(?m)^#error .*Cloud Shadows.*$", "// (disabled without Cloud Shadows)", 1));
        edit(staging, "shaders.properties", PhotonCompat::properties);
        // Photon's deferred4 moves to deferred5 so a fragment pass can compute the sky light before it.
        Path world = staging.resolve("world0");
        for (String stage : new String[] {"vsh", "fsh"}) {
            if (Files.exists(world.resolve("deferred5." + stage))) throw new IllegalStateException("this Photon already uses deferred5");
            Files.move(world.resolve("deferred4." + stage), world.resolve("deferred5." + stage));
            Files.writeString(world.resolve("deferred4." + stage), SKY_SH_ENTRY.formatted(stage, stage));
        }
        Files.writeString(staging.resolve("program/earth_sky_sh.vsh"), SKY_SH_VERTEX);
        Files.writeString(staging.resolve("program/earth_sky_sh.fsh"), SKY_SH_FRAGMENT);
        Files.writeString(staging.resolve(DONE), "Photon adapted by Earth Platform for an OpenGL driver without compute shaders or with 16 texture units\n");
        deleteTree(copy);
        Files.createDirectories(copy.getParent());
        Files.move(staging, copy);
    }

    private static String properties(String text) {
        // Profiles mustn't turn the features back on.
        String profiles = replaceEach(text, "(?m)^profile\\.\\w+\\s*=.*$",
                line -> line.replaceAll("(?<=\\s)(SHADOW_COLOR|COLORED_LIGHTS)(?=\\s|$)", "!$1"));
        String out = replace(profiles, "(?m)^(program\\.world-?\\d+/(?:setup|shadowcomp)\\.enabled\\s*=).*$", "$1 false", 6);
        out = replace(out, "(?m)^(program\\.world0/deferred4_a\\.enabled\\s*=).*$",
                "$1 false\nprogram.world0/deferred4.enabled = SH_SKYLIGHT\nprogram.world0/prepare.enabled = CLOUD_SHADOWS", 1);
        // Colored Lights needs compute shaders, so the menu doesn't offer it.
        return replace(out, "(?m)^(screen\\.lighting\\s*=.*?)COLORED_LIGHTS \\[colored_lights\\]", "$1<empty> <empty>", 1);
    }

    private static void edit(Path root, String name, UnaryOperator<String> change) throws IOException {
        Path file = root.resolve(name);
        Files.writeString(file, change.apply(Files.readString(file)));
    }

    /** Replaces every match of {@code regex}, which must match exactly {@code expected} times, so a different Photon is left alone. */
    static String replace(String text, String regex, String replacement, int expected) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int found = 0;
        while (matcher.find()) found++;
        if (found != expected) throw new IllegalStateException("expected " + expected + " of " + regex + ", found " + found);
        return matcher.replaceAll(replacement);
    }

    private static String replaceEach(String text, String regex, UnaryOperator<String> change) {
        return Pattern.compile(regex).matcher(text).replaceAll(match -> Matcher.quoteReplacement(change.apply(match.group())));
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(each);
        }
    }

    private static final String SKY_SH_ENTRY = """
            #version 400 compatibility
            #define WORLD_OVERWORLD
            #define %s
            #include "/program/earth_sky_sh.%s"
            """;

    /** A full-screen pass over colortex4, the pack's sky map target. */
    private static final String SKY_SH_VERTEX = """
            #include "/include/global.glsl"

            void main() {
                gl_Position = vec4(gl_Vertex.xy * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /**
     * Projects the sky map onto second-order spherical harmonics, using the pack's own sky
     * projection and harmonics functions, into the texels the pack's lighting reads them from:
     * nine coefficients at (191, 2..10) and the upward irradiance at (191, 11). Every other texel
     * of colortex4 passes through.
     */
    private static final String SKY_SH_FRAGMENT = """
            #include "/include/global.glsl"

            layout(location = 0) out vec4 result;

            /* RENDERTARGETS: 4 */

            uniform sampler2D colortex4;

            uniform int worldTime;
            uniform int worldDay;
            uniform int moonPhase;
            uniform float sunAngle;
            uniform float rainStrength;
            uniform float wetness;
            uniform int frameCounter;
            uniform float frameTimeCounter;
            uniform int isEyeInWater;
            uniform float blindness;
            uniform float nightVision;
            uniform float darknessFactor;
            uniform vec3 light_dir;
            uniform vec3 sun_dir;
            uniform vec3 moon_dir;
            uniform float biome_cave;
            uniform float biome_may_rain;
            uniform float biome_may_snow;
            uniform float biome_snowy;
            uniform float biome_temperate;
            uniform float biome_arid;
            uniform float biome_taiga;
            uniform float biome_jungle;
            uniform float biome_swamp;
            uniform float biome_temperature;
            uniform float biome_humidity;
            uniform float desert_sandstorm;
            uniform float world_age;
            uniform float time_sunrise;
            uniform float time_noon;
            uniform float time_sunset;
            uniform float time_midnight;

            #include "/include/lighting/colors/light_color.glsl"
            #include "/include/sky/projection.glsl"
            #include "/include/utility/random.glsl"
            #include "/include/utility/sampling.glsl"
            #include "/include/utility/spherical_harmonics.glsl"

            const uint directions = 256u;

            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy);
                if (texel.x != 191 || texel.y < 2 || texel.y > 11) {
                    result = texelFetch(colortex4, texel, 0);
                    return;
                }

                float boost = get_skylight_boost();
                vec3 harmonics[9];
                for (uint k = 0u; k < 9u; ++k) harmonics[k] = vec3(0.0);
                for (uint i = 0u; i < directions; ++i) {
                    vec3 dir = uniform_hemisphere_sample(vec3(0.0, 1.0, 0.0), r2(int(i)));
                    vec3 light = texture(colortex4, project_sky(dir)).rgb * boost;
                    float[9] basis = sh_coeff_order_2(dir);
                    for (uint k = 0u; k < 9u; ++k) harmonics[k] += light * basis[k] * (tau / float(directions));
                }

                result = texel.y < 11
                    ? vec4(harmonics[texel.y - 2], 0.0)
                    : vec4(sh_evaluate_irradiance(harmonics, vec3(0.0, 1.0, 0.0), 1.0), 0.0);
            }
            """;
}
