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
import org.lwjgl.opengl.GL43C;
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
    private static final int VERSION = 4;
    private static final String MODE = System.getProperty("earthplatform.photon.compat", "auto");

    private PhotonCompat() {}

    /** The pack root Iris should read: {@code root} itself, or the adapted copy when this driver needs one. */
    public static Path adapt(Path root) {
        try {
            if (!isPhoton(root) || !needed(root)) return root;
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

    private static boolean needed(Path root) throws IOException {
        if (MODE.equals("on")) return true;
        if (MODE.equals("off")) return false;
        GLCapabilities caps = GL.getCapabilities();
        boolean compute = caps.OpenGL43 || caps.GL_ARB_compute_shader;
        return !compute || GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS) < 17
                || sharedMemory(root) > GL11C.glGetInteger(GL43C.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE);
    }

    private static final Pattern SHARED = Pattern.compile("(?m)^[ \\t]*shared[ \\t]+(\\w+)[ \\t]+\\w+((?:[ \\t]*\\[[ \\t]*\\d+[ \\t]*\\])*)[ \\t]*;");
    private static final Pattern LENGTH = Pattern.compile("\\d+");

    /**
     * The most compute shared memory any of the pack's compute shaders declares, in bytes, counting
     * a three-component vector as four components as some drivers lay it out.
     */
    static long sharedMemory(Path root) throws IOException {
        long most = 0;
        for (Path file : files(root)) {
            if (!file.getFileName().toString().endsWith(".csh")) continue;
            Matcher declaration = SHARED.matcher(Files.readString(file));
            long bytes = 0;
            while (declaration.find()) {
                long size = switch (declaration.group(1)) {
                    case "float", "int", "uint", "bool" -> 4;
                    case "vec2", "ivec2", "uvec2" -> 8;
                    case "mat2" -> 16;
                    case "mat3" -> 48;
                    case "mat4" -> 64;
                    default -> 16;
                };
                Matcher length = LENGTH.matcher(declaration.group(2));
                while (length.find()) size *= Long.parseLong(length.group());
                bytes += size;
            }
            most = Math.max(most, bytes);
        }
        return most;
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
        // Without screen-space reflections (see deferredShading) the hit branch is dead; some compilers still count its texture.
        edit(staging, "include/lighting/specular_lighting.glsl", text -> replace(text,
                "(?m)^([ \\t]*)(vec3 reflection = textureLod\\(colortex5, hit_uv_prev, mip_level\\)\\.rgb;)[ \\t]*$",
                "#ifdef EARTH_DEPTH_FROM_COMBINED\n$1vec3 reflection = vec3(0.0);\n#else\n$1$2\n#endif", 1));
        edit(staging, "program/d4_deferred_shading.fsh", PhotonCompat::deferredShading);
        // Voxy's own far water buffer (colortex16) doesn't exist under Iris; Photon only reads it for far water we don't draw.
        edit(staging, "program/c1_blend_layers.fsh", text -> replace(text,
                "(?m)^([ \\t]*)vec4 gbuffer_data = texelFetch\\(colortex16, texel, 0\\);[ \\t]*$",
                "$1vec4 gbuffer_data = vec4(0.0);", 1));
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
        Files.writeString(staging.resolve("program/earth_sky_sh.fsh"),
                skyShDeclarations(Files.readString(staging.resolve("program/d4a_generate_sky_sh.csh"))) + SKY_SH_MAIN);
        Files.writeString(staging.resolve(DONE), "Photon adapted by Earth Platform for an OpenGL driver without compute shaders or with 16 texture units\n");
        deleteTree(copy);
        Files.createDirectories(copy.getParent());
        Files.move(staging, copy);
    }

    /**
     * The deferred shading pass. Besides skipping fog history: with far terrain's own depth
     * (Photon's LOD path) it would read two more textures than fit, so there it goes without
     * screen-space reflections on opaque surfaces, and tells far terrain from the rest by the
     * combined and far depths instead of also reading the game's depth.
     */
    private static String deferredShading(String text) {
        String out = replace(text, "(#include \"/include/global.glsl\"\\n)", Matcher.quoteReplacement(DEFERRED_DEFINES), 1);
        out = replace(out, "(?m)^([ \\t]*)(float depth_mc = texelFetch\\(depthtex1, texel, 0\\)\\.x;)[ \\t]*$",
                "#ifdef EARTH_DEPTH_FROM_COMBINED\n$1float depth_mc = earth_gameDepth(texel, depth);\n#else\n$1$2\n#endif", 1);
        return replace(out, "(?m)^void main\\(\\) \\{$", Matcher.quoteReplacement(GAME_DEPTH + "void main() {"), 1);
    }

    private static final String DEFERRED_DEFINES = """
            #include "/include/global.glsl"

            #define EARTH_NO_FOG_HISTORY
            #ifdef LOD_MOD_ACTIVE
            #undef ENVIRONMENT_REFLECTIONS
            #define EARTH_DEPTH_FROM_COMBINED
            #endif
            """;

    /**
     * Stands in for the game's depth where only the hand, far terrain and everything else need
     * telling apart: the combined depth is 0 for the hand and 1 for sky, and is far terrain's own
     * depth, re-projected, exactly where far terrain shows.
     */
    private static final String GAME_DEPTH = """
            #ifdef EARTH_DEPTH_FROM_COMBINED
            float earth_gameDepth(ivec2 texel, float combined) {
                if (combined <= 0.0) return 0.0;
                float lod = texelFetch(lod_depth_tex, texel, 0).x;
                if (lod >= 1.0) return combined >= 1.0 ? 1.0 : 0.9;
                if (combined >= 1.0) return 1.0;
                float reprojected = view_to_screen_space_depth(combined_projection_matrix,
                    screen_to_view_space_depth(lod_projection_matrix_inverse, lod));
                return abs(reprojected - combined) <= 4e-7 ? 1.0 : 0.9;
            }
            #endif

            """;

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
     * The uniform declarations and includes of the pack's own sky light pass, read from the
     * player's copy so the fragment pass sees exactly what that pass would. Compute-only lines
     * (work group size, image and shared memory) are dropped.
     */
    static String skyShDeclarations(String compute) {
        int main = compute.indexOf("void main(");
        if (main < 0) throw new IllegalStateException("no main() in the sky light pass");
        StringBuilder out = new StringBuilder();
        for (String line : compute.substring(0, main).split("\n")) {
            String trimmed = line.strip();
            boolean uniform = trimmed.startsWith("uniform ") && !trimmed.contains("image");
            boolean preprocessor = trimmed.matches("#(include|if|ifdef|ifndef|elif|else|endif)\\b.*");
            if (uniform || preprocessor) out.append(trimmed).append('\n');
        }
        String declarations = out.toString();
        if (!declarations.contains("sampler2D colortex4;")) throw new IllegalStateException("the sky light pass doesn't read colortex4");
        if (!declarations.contains("spherical_harmonics")) throw new IllegalStateException("the sky light pass doesn't include the harmonics");
        return declarations;
    }

    /**
     * Integrates the sky map over the upper hemisphere and writes it where the pack's lighting
     * reads it: nine harmonic coefficients at (191, 2..10) and the upward irradiance at
     * (191, 11). Every other texel of colortex4 passes through.
     *
     * <p>The hemisphere is split into a 16 x 16 grid of cells, even in height (cos theta) and in
     * azimuth, so every cell covers the same solid angle; each cell is sampled once at its
     * centre. The basis and the irradiance come from the pack, so coefficient order and scale
     * are whatever it expects (including its Intel variant).
     */
    private static final String SKY_SH_MAIN = """

            layout(location = 0) out vec4 result;

            /* RENDERTARGETS: 4 */

            const int earth_rings = 16;
            const int earth_segments = 16;

            void earth_basis(vec3 dir, out float basis[9]) {
            #ifdef MC_GL_RENDERER_INTEL
                mat3 grid = sh_coeff_order_2(dir);
                for (int k = 0; k < 9; ++k) basis[k] = grid[k / 3][k % 3];
            #else
                basis = sh_coeff_order_2(dir);
            #endif
            }

            vec3 earth_irradiance_up(vec3 sky[9]) {
                const vec3 up = vec3(0.0, 1.0, 0.0);
            #ifdef MC_GL_RENDERER_INTEL
                sh3 grouped;
                grouped.f1 = mat3(sky[0], sky[1], sky[2]);
                grouped.f2 = mat3(sky[3], sky[4], sky[5]);
                grouped.f3 = mat3(sky[6], sky[7], sky[8]);
                return sh_evaluate_irradiance(grouped, up, 1.0);
            #else
                return sh_evaluate_irradiance(sky, up, 1.0);
            #endif
            }

            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy);
                if (texel.x != 191 || texel.y < 2 || texel.y > 11) {
                    result = texelFetch(colortex4, texel, 0);
                    return;
                }

                // Each cell's share of the hemisphere's 2 pi steradians, and the sky map's brightness scale.
                float cell = tau / float(earth_rings * earth_segments) * get_skylight_boost();
                vec3 sky[9] = vec3[9](vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0));
                for (int ring = 0; ring < earth_rings; ++ring) {
                    float height = (float(ring) + 0.5) / float(earth_rings);
                    float across = sqrt(1.0 - height * height);
                    for (int segment = 0; segment < earth_segments; ++segment) {
                        float azimuth = tau * (float(segment) + 0.5) / float(earth_segments);
                        vec3 dir = vec3(across * cos(azimuth), height, across * sin(azimuth));
                        vec3 light = texture(colortex4, project_sky(dir)).rgb * cell;
                        float basis[9];
                        earth_basis(dir, basis);
                        for (int k = 0; k < 9; ++k) sky[k] += light * basis[k];
                    }
                }

                result = vec4(texel.y < 11 ? sky[texel.y - 2] : earth_irradiance_up(sky), 0.0);
            }
            """;
}
