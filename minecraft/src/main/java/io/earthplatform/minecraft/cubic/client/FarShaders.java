package io.earthplatform.minecraft.cubic.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * GLSL for far terrain. The vertex shader unpacks {@link FarGpu} faces straight from a buffer
 * texture by vertex number, six vertices a face, so faces cost 16 bytes rather than four full
 * vertices. Without a shader pack the fragment shader textures, shades, lights and fogs them like
 * vanilla terrain; with one, it hands each fragment to the pack's own Voxy fragment code
 * ({@code voxy_opaque.glsl}), so the pack shades far terrain as it shades Voxy's.
 */
final class FarShaders {
    private FarShaders() {}

    /**
     * A shader pack's Voxy code for one dimension: its fragment code, and the body of the GLSL
     * function giving its temporal anti-aliasing jitter, or null when it asks for none.
     */
    record PackPatch(String fragment, @Nullable String taaOffset) {}

    private static final Pattern TAA_OFFSET = Pattern.compile("\"taaOffset\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * The body of a Voxy {@code voxy.json}'s {@code taaOffset}, a GLSL function returning this
     * frame's jitter in clip space, or null when the pack asks for none. The file is loose JSON with
     * the pack's settings pasted into it by the preprocessor, so only this one string is picked out.
     */
    @Nullable static String taaOffset(@Nullable String settings) {
        if (settings == null) return null;
        Matcher matcher = TAA_OFFSET.matcher(settings);
        if (!matcher.find()) return null;
        String body = matcher.group(1).trim();
        return body.startsWith("{") && body.endsWith("}") ? body : null;
    }

    /** Blocks of height over which the haze thins to about a third (1/e): roughly how deep the hazy lower air is. */
    private static final float HAZE_HEIGHT = 1000.0F;

    static final String VERTEX = String.format(java.util.Locale.ROOT,
            "const float earth_HazeHeight = %.1f;\nconst float earth_Edge = %.1f;\n", HAZE_HEIGHT, 0.95F * io.earthplatform.minecraft.cubic.far.Far.reach()) + """
            uniform usamplerBuffer earth_Quads;
            uniform isamplerBuffer earth_Columns;
            uniform usamplerBuffer earth_Looks;
            uniform mat4 earth_ModelView;
            uniform mat4 earth_Projection;
            uniform ivec3 earth_CameraBlock;
            uniform vec3 earth_CameraFraction;
            uniform int earth_FogShape;

            out vec2 earth_tile;
            out float earth_distance;
            out float earth_edge;
            flat out vec4 earth_sprite;
            flat out vec3 earth_average;
            flat out vec3 earth_tint;
            flat out uint earth_blockId;
            flat out uint earth_flags;
            flat out uint earth_face;
            flat out uint earth_light;
            flat out uint earth_look;
            flat out uint earth_normal;
            out vec3 earth_position;
            flat out vec3 earth_anchor;
            flat out float earth_reach;

            // Corners (along the face's width, along its height) per direction, counter-clockwise from outside.
            const ivec2 CORNERS[24] = ivec2[](
                ivec2(0, 0), ivec2(1, 0), ivec2(1, 1), ivec2(0, 1),
                ivec2(0, 0), ivec2(0, 1), ivec2(1, 1), ivec2(1, 0),
                ivec2(0, 0), ivec2(0, 1), ivec2(1, 1), ivec2(1, 0),
                ivec2(0, 0), ivec2(1, 0), ivec2(1, 1), ivec2(0, 1),
                ivec2(0, 0), ivec2(1, 0), ivec2(1, 1), ivec2(0, 1),
                ivec2(0, 0), ivec2(0, 1), ivec2(1, 1), ivec2(1, 0));
            const int TRIANGLES[6] = int[](0, 1, 2, 0, 2, 3);

            // A point on a face, given as fractions along its width and its height.
            vec3 earth_local(uint face, vec2 ab, float x, float y, float z, float width, uint extent, float v) {
                if (face < 2u) return vec3((x + ab.x * width) * v, y, (z + ab.y * float(extent + 1u)) * v);
                if (face < 4u) return vec3((x + ab.x * width) * v, y + ab.y * float(extent) / 16.0, z * v);
                return vec3(x * v, y + ab.y * float(extent) / 16.0, (z + ab.x * width) * v);
            }

            vec3 earth_rgb(uint colour) {
                return vec3(float((colour >> 16) & 255u), float((colour >> 8) & 255u), float(colour & 255u)) / 255.0;
            }

            void main() {
                int quadIndex = gl_VertexID / 6;
                int corner = TRIANGLES[gl_VertexID - quadIndex * 6];
                uvec4 q = texelFetch(earth_Quads, quadIndex);
                uint face = (q.x >> 10) & 7u;
                float width = float(((q.x >> 13) & 15u) + 1u);
                ivec4 column = texelFetch(earth_Columns, int((q.x >> 25) | ((q.z >> 20) << 7)));
                float v = float(1 << column.w);
                float x = float(q.x & 31u), z = float((q.x >> 5) & 31u), y = float(int(q.y)) / 16.0;
                uint extent = q.z & 0xFFFFFu;
                vec2 ab = vec2(CORNERS[int(face) * 4 + corner]);
                vec3 local = earth_local(face, ab, x, y, z, width, extent, v);
                vec3 origin = vec3(column.xyz - earth_CameraBlock) - earth_CameraFraction;
                vec3 position = origin + local;
                earth_position = position;
                earth_anchor = origin + earth_local(face, vec2(0.5), x, y, z, width, extent, v);
                earth_reach = 16.0 * v;
                gl_Position = earth_Projection * earth_ModelView * vec4(position, 1.0);
            #ifdef EARTH_JITTER
                // The pack's TAA moves every frame's geometry by a sub-pixel jitter; far terrain moves with it.
                gl_Position.xy += earth_taaOffset() * gl_Position.w;
            #endif
                earth_distance = earth_FogShape == 0 ? length(position) : max(length(position.xz), abs(position.y));
                // Haze thins with height, so a line of sight rising to a far summit crosses less of it
                // than one over the lowlands: distant ranges stand clear of the haze that hides the plains.
                float rise = position.y / earth_HazeHeight;
                if (rise > 0.01) earth_distance *= (1.0 - exp(-rise)) / rise;
                // Whatever rises clear still fades out before the coarsest level's square ends.
                earth_edge = smoothstep(0.8 * earth_Edge, earth_Edge, length(position.xz));

                // Textures repeat once per block, laid on each face as the block renderer lays them.
                if (face < 2u) earth_tile = local.xz;
                else if (face == 2u) earth_tile = vec2(-local.x, -local.y);
                else if (face == 3u) earth_tile = vec2(local.x, -local.y);
                else if (face == 4u) earth_tile = vec2(local.z, -local.y);
                else earth_tile = vec2(-local.z, -local.y);

                uint look = q.w & 0xFFFFFu;
                earth_normal = q.w >> 20;
                uvec4 a = texelFetch(earth_Looks, int(look) * 2);
                uvec4 b = texelFetch(earth_Looks, int(look) * 2 + 1);
                earth_sprite = uintBitsToFloat(a);
                earth_average = earth_rgb(b.x);
                earth_tint = earth_rgb(b.y);
                earth_blockId = b.z;
                earth_flags = b.w;
                earth_face = face;
                earth_light = (q.x >> 17) & 255u;
                earth_look = look;
            }
            """;

    /** Shared by both fragment shaders: the face's texel (or average colour), before tint. */
    private static final String SAMPLE = """
            in vec2 earth_tile;
            in float earth_distance;
            flat in vec4 earth_sprite;
            flat in vec3 earth_average;
            flat in vec3 earth_tint;
            flat in uint earth_blockId;
            flat in uint earth_flags;
            flat in uint earth_face;
            flat in uint earth_light;
            flat in uint earth_look;

            uniform sampler2D earth_Atlas;

            vec2 earth_atlasUv() {
                return earth_sprite.xy + fract(earth_tile) * (earth_sprite.zw - earth_sprite.xy);
            }

            vec4 earth_sample() {
                if ((earth_flags & 1u) == 0u) return vec4(earth_average, 1.0);
                vec2 size = earth_sprite.zw - earth_sprite.xy;
                vec4 texel = textureGrad(earth_Atlas, earth_atlasUv(), dFdx(earth_tile) * size, dFdy(earth_tile) * size);
                return vec4(texel.rgb, 1.0);
            }

            vec2 earth_levels() {
                return vec2(float(earth_light >> 4), float(earth_light & 15u));
            }
            """;

    /**
     * The ground slope a far face stands for, from {@link FarMesher#normalCode}; a zero vector for
     * faces that keep their own normal (foliage and undersides).
     */
    private static final String SLOPE = """
            flat in uint earth_normal;

            vec3 earth_slope() {
                if (earth_normal == 0u) return vec3(0.0);
                vec2 xz = (vec2(float(earth_normal & 63u), float(earth_normal >> 6)) - 32.0) / 31.0;
                return vec3(xz.x, sqrt(max(0.0, 1.0 - dot(xz, xz))), xz.y);
            }
            """;

    /**
     * Vanilla's side shading (up, north and south, east and west), weighted by how far the face's
     * ground slope leans each way, so a stepped hillside shades as one slope instead of stripes.
     */
    private static final String SHADE = SLOPE + """
            uniform float earth_Shade[6];

            float earth_shade() {
                vec3 slope = earth_slope();
                if (slope == vec3(0.0)) return earth_Shade[earth_face];
                vec3 weight = slope * slope;
                // Near by, smooth lighting darkens every stair corner of a stepped slope; flat ground
                // and sheer walls have none, so the far slope darkens most at 45 degrees to match.
                float corners = 1.0 - 0.9 * slope.y * (1.0 - slope.y);
                return (weight.x * earth_Shade[4] + weight.y * earth_Shade[1] + weight.z * earth_Shade[2]) * corners;
            }
            """;

    /** Vanilla look: lightmap, side shading and linear fog, as rendertype_solid does. */
    static final String FRAGMENT = SAMPLE + SHADE + """
            uniform sampler2D earth_Lightmap;
            uniform vec4 earth_ColorModulator;
            uniform float earth_FogStart;
            uniform float earth_FogEnd;
            uniform vec4 earth_FogColor;
            in float earth_edge;

            out vec4 earth_fragColor;

            void main() {
                vec4 colour = earth_sample();
                colour.rgb *= earth_tint * earth_shade();
                vec2 light = clamp(earth_levels() / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
                colour *= texture(earth_Lightmap, light) * earth_ColorModulator;
                float fog = earth_distance <= earth_FogStart ? 0.0
                        : earth_distance < earth_FogEnd ? smoothstep(earth_FogStart, earth_FogEnd, earth_distance) : 1.0;
                fog = max(fog, earth_edge);
                if (fog > 0.0) colour.rgb = mix(colour.rgb, earth_FogColor.rgb, fog * earth_FogColor.a);
                earth_fragColor = vec4(colour.rgb, 1.0);
            }
            """;

    /**
     * Declarations a Voxy fragment patch is written against, as Voxy declares them, and the ground
     * slope's normal that {@link #packFragment} has the patch use in place of its face's.
     */
    static final String VOXY_PARAMETERS = """
            struct VoxyFragmentParameters {
                vec4 sampledColour;
                vec2 tile;
                vec2 uv;
                uint face;
                uint modelId;
                vec2 lightMap;
                vec4 tinting;
                uint customId;
            };

            """ + SLOPE + """

            // A far voxel's face lit as the slope its stack of voxels stands for, so hillsides
            // don't show as stripes of lit tops and dark risers; foliage keeps its face's normal.
            vec3 earth_packNormal(vec3 face) {
                vec3 slope = earth_slope();
                return slope == vec3(0.0) ? face : slope;
            }
            """;

    /** Where a Voxy patch works out its normal from the face, as Voxy's own example does. */
    private static final Pattern FACE_NORMAL =
            Pattern.compile("(vec3\\s+(\\w+)\\s*=\\s*vec3\\(\\s*uint\\(\\(\\s*parameters\\.face[^;]*;)");

    /**
     * Gives a ground face the depth of the slope it stands for: where the view ray through the
     * pixel meets the plane through the face's middle along the ground slope, within sixteen voxels
     * of the face. On an even staircase those planes are one plane, through every tread's and
     * riser's middle, so a shader pack's screen-space shadows see a smooth hillside instead of
     * stairs whose every step shades the next. Needs {@link #SLOPE} first.
     */
    private static final String SLOPE_DEPTH = """
            in vec3 earth_position;
            flat in vec3 earth_anchor;
            flat in float earth_reach;
            uniform mat4 earth_ModelView;
            uniform mat4 earth_Projection;

            void earth_slopeDepth() {
                vec3 slope = earth_slope();
                float along = dot(slope, earth_position);
                float t = along == 0.0 ? 1.0 : dot(slope, earth_anchor) / along;
                if (slope == vec3(0.0) || t <= 0.0 || abs(t - 1.0) * length(earth_position) > earth_reach) {
                    gl_FragDepth = gl_FragCoord.z;
                    return;
                }
                vec4 clip = earth_Projection * earth_ModelView * vec4(earth_position * t, 1.0);
                gl_FragDepth = clamp((clip.z / clip.w) * 0.5 + 0.5, 0.0, 1.0);
            }
            """;

    /**
     * Calls the pack's Voxy fragment code, which is placed before this, after
     * {@link #VOXY_PARAMETERS} and so the slope. When far terrain has its own depth
     * ({@link FarLod}), pixels the game's terrain already covers are left to it, and ground faces
     * take their slope's depth.
     */
    static final String PACK_MAIN = SAMPLE + SLOPE_DEPTH + """
            #ifdef EARTH_OWN_DEPTH
            uniform sampler2D earth_GameDepth;
            // The face's own depth, before it takes its slope's, for telling what hides far water.
            layout(location = 1) out float earth_faceDepth;
            #endif

            void main() {
            #ifdef EARTH_OWN_DEPTH
                if (texelFetch(earth_GameDepth, ivec2(gl_FragCoord.xy), 0).r < 1.0) discard;
                earth_faceDepth = gl_FragCoord.z;
                earth_slopeDepth();
            #endif
                vec4 colour = earth_sample();
                vec2 light = clamp(earth_levels() / 15.0 * (15.0 / 16.0) + 0.5 / 16.0, vec2(8.0 / 256.0), vec2(248.0 / 256.0));
                voxy_emitFragment(VoxyFragmentParameters(colour, floor(earth_tile), earth_atlasUv(), earth_face, earth_look, light,
                        vec4(earth_tint, 1.0), earth_blockId));
            }
            """;

    /** For packs without Voxy support: the far terrain's lit colour into the pack's first terrain target. */
    static final String PACK_FALLBACK = SAMPLE + SHADE + """
            uniform sampler2D earth_Lightmap;

            layout(location = 0) out vec4 earth_fragColor;

            void main() {
                vec4 colour = earth_sample();
                colour.rgb *= earth_tint * earth_shade();
                colour *= texture(earth_Lightmap, clamp(earth_levels() / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
                earth_fragColor = vec4(colour.rgb, 1.0);
            }
            """;

    /**
     * The vertex shader for a pack's far terrain: with {@code jitter} (uniform declarations and an
     * {@code earth_taaOffset()} function built from the pack's {@code taaOffset}), far terrain
     * jitters as the pack's own terrain does, so its TAA anti-aliases it instead of smearing it.
     */
    static String packVertex(String version, @Nullable String jitter) {
        return jitter == null ? version + VERTEX : version + "#define EARTH_JITTER\n" + jitter + VERTEX;
    }

    /**
     * The pack's fragment shader: its Voxy patch, with any #extension lines moved up, between our
     * declarations and main. Where the patch works out its normal from the face, the ground slope's
     * normal takes over.
     */
    static String packFragment(String version, String patch, boolean ownDepth) {
        StringBuilder extensions = new StringBuilder(), body = new StringBuilder();
        for (String line : patch.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#extension")) extensions.append(trimmed).append('\n');
            else if (!trimmed.startsWith("#version")) body.append(line).append('\n');
        }
        String patched = FACE_NORMAL.matcher(body).replaceFirst("$1\n    $2 = earth_packNormal($2);");
        return version + extensions + (ownDepth ? "#define EARTH_OWN_DEPTH\n" : "") + VOXY_PARAMETERS + patched + PACK_MAIN;
    }
}
