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
     * A shader pack's Voxy code for one dimension: its fragment code, the body of the GLSL
     * function giving its temporal anti-aliasing jitter, or null when it asks for none, the
     * fragment output location far terrain's faces' own depth takes beside the pack's outputs
     * (see {@link #faceDepthLocation}), and the uniforms and samplers (name to GLSL type) its
     * {@code voxy.json} asks for, which Voxy declares for the code (see {@link #voxyDeclarations}).
     */
    record PackPatch(String fragment, @Nullable String taaOffset, int faceDepthLocation, java.util.List<String> uniforms,
            java.util.Map<String, String> samplers) {
        PackPatch(String fragment, @Nullable String taaOffset) { this(fragment, taaOffset, java.util.List.of(), java.util.Map.of()); }

        PackPatch(String fragment, @Nullable String taaOffset, java.util.List<String> uniforms, java.util.Map<String, String> samplers) {
            this(fragment, taaOffset, FarShaders.faceDepthLocation(fragment), uniforms, samplers);
        }
    }

    private static final Pattern UNIFORM_LIST = Pattern.compile("\"uniforms\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern SAMPLER_LIST = Pattern.compile("\"samplers\"\\s*:\\s*(\\[[^\\]]*\\]|\\{[^}]*\\})");
    private static final Pattern QUOTED = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"");
    private static final Pattern SAMPLER_ENTRY = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:\\s*(?:\"([A-Za-z_][A-Za-z0-9_]*)\"|null)");

    /** The uniform names a Voxy {@code voxy.json} lists, in order; empty when it lists none. */
    static java.util.List<String> voxyUniforms(@Nullable String settings) {
        if (settings == null) return java.util.List.of();
        Matcher list = UNIFORM_LIST.matcher(settings);
        if (!list.find()) return java.util.List.of();
        java.util.List<String> names = new java.util.ArrayList<>();
        Matcher name = QUOTED.matcher(list.group(1));
        while (name.find()) if (!names.contains(name.group(1))) names.add(name.group(1));
        return java.util.List.copyOf(names);
    }

    /**
     * The samplers a Voxy {@code voxy.json} lists, by name, with their GLSL types: a list of names
     * (each a {@code sampler2D}), or an object of names to types (null for the default), read as
     * Voxy reads them, where only a sampler named exactly {@code shadowtex} defaults to a shadow sampler.
     */
    static java.util.Map<String, String> voxySamplers(@Nullable String settings) {
        if (settings == null) return java.util.Map.of();
        Matcher list = SAMPLER_LIST.matcher(settings);
        if (!list.find()) return java.util.Map.of();
        java.util.Map<String, String> samplers = new java.util.LinkedHashMap<>();
        String body = list.group(1);
        if (body.startsWith("[")) {
            Matcher name = QUOTED.matcher(body);
            while (name.find()) samplers.putIfAbsent(name.group(1), defaultSampler(name.group(1)));
        } else {
            Matcher entry = SAMPLER_ENTRY.matcher(body);
            while (entry.find()) samplers.putIfAbsent(entry.group(1), entry.group(2) != null ? entry.group(2) : defaultSampler(entry.group(1)));
        }
        return java.util.Collections.unmodifiableMap(samplers);
    }

    private static String defaultSampler(String name) { return name.equals("shadowtex") ? "sampler2DShadow" : "sampler2D"; }

    /**
     * Declarations for the uniforms and samplers a pack's {@code voxy.json} lists, which Voxy
     * declares ahead of the pack's Voxy code and the code itself doesn't: only those the code
     * reads and doesn't declare itself, so code that declares its own (or reads none, as Photon's)
     * is left exactly as it was. A uniform whose type isn't in {@code types} is left out; if the
     * code needs it, it then fails to build and far terrain falls back to plain colours.
     */
    static String voxyDeclarations(String patch, java.util.List<String> uniforms, java.util.Map<String, String> types,
            java.util.Map<String, String> samplers) {
        String code = COMMENTS.matcher(patch).replaceAll(" ");
        StringBuilder out = new StringBuilder();
        for (String name : uniforms) {
            String type = types.get(name);
            if (type != null && reads(code, name) && !declares(code, name)) out.append("uniform ").append(type).append(' ').append(name).append(";\n");
        }
        for (java.util.Map.Entry<String, String> sampler : samplers.entrySet()) {
            String name = sampler.getKey();
            if (reads(code, name) && !declares(code, name)) out.append("uniform ").append(sampler.getValue()).append(' ').append(name).append(";\n");
        }
        return out.toString();
    }

    /** The sampler names {@link #voxyDeclarations} declares for a patch. */
    static java.util.Set<String> declaredSamplers(String patch, java.util.Map<String, String> samplers) {
        String code = COMMENTS.matcher(patch).replaceAll(" ");
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (String name : samplers.keySet()) if (reads(code, name) && !declares(code, name)) names.add(name);
        return names;
    }

    private static boolean reads(String code, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(code).find();
    }

    private static boolean declares(String code, String name) {
        return Pattern.compile("\\buniform\\s+[^;{}]*\\b" + Pattern.quote(name) + "\\b").matcher(code).find();
    }

    /** Where {@link #PACK_MAIN} puts the faces' own depth, for a pack that leaves location 1 free. */
    private static final String FACE_DEPTH_OUTPUT = "layout(location = 1) out float earth_faceDepth;";
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\n]*", Pattern.DOTALL);
    /** A fragment output declared at global scope: its layout qualifier's contents, and its array size. */
    private static final Pattern OUTPUT = Pattern.compile(
            "(?m)^\\s*(?:layout\\s*\\(([^)]*)\\)\\s*)?(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise|highp|mediump|lowp)\\s+)*"
            + "out\\s+\\w+\\s+\\w+\\s*(?:\\[\\s*(\\d+)\\s*\\])?\\s*;");
    private static final Pattern LOCATION = Pattern.compile("\\blocation\\s*=\\s*(\\d+)");

    /**
     * The fragment output location for far terrain's faces' own depth with a pack's Voxy code: the
     * first from 1 up that none of the pack's outputs takes. Outputs with a location take theirs
     * (and an array's the ones after); those without one are counted as taking the lowest free
     * locations, as drivers hand them out. Photon's single output at 0 leaves 1, as before;
     * Complementary's and others' second output at 1 put it further up instead of clashing there,
     * which failed to build and left far terrain in plain colours.
     */
    static int faceDepthLocation(@Nullable String patch) {
        if (patch == null) return 1;
        java.util.BitSet used = new java.util.BitSet();
        int unlocated = 0;
        Matcher output = OUTPUT.matcher(COMMENTS.matcher(patch).replaceAll(" "));
        while (output.find()) {
            int size = output.group(2) == null ? 1 : Math.max(1, Integer.parseInt(output.group(2)));
            Matcher location = output.group(1) == null ? null : LOCATION.matcher(output.group(1));
            if (location != null && location.find()) used.set(Integer.parseInt(location.group(1)), Integer.parseInt(location.group(1)) + size);
            else unlocated += size;
        }
        for (int at = 0; unlocated > 0; at++) {
            if (!used.get(at)) {
                used.set(at);
                unlocated--;
            }
        }
        return used.nextClearBit(1);
    }

    /** A far terrain fragment shader with the faces' own depth at {@code location} rather than 1. */
    private static String faceDepthAt(String source, int location) {
        return location == 1 ? source : source.replace(FACE_DEPTH_OUTPUT, "layout(location = " + location + ") out float earth_faceDepth;");
    }

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

    /**
     * Defines for a far terrain program by fade ({@link FarGpu#NO_FADE}, {@link FarGpu#FADE_OUT},
     * {@link FarGpu#FADE_IN}). Where a level gives way to the next coarser one, both are drawn over
     * the coarser level's ring of columns at the edge, and a dither hands each pixel to one of them:
     * the finer level thins out across the ring as the coarser one fills in, so the step in voxel
     * size, height and texture between them blends over a column's width instead of showing as a
     * seam. Only those few columns are drawn twice, and only their programs discard fragments.
     */
    static String fade(int fade) {
        return fade == 0 ? "" : "#define EARTH_FADE " + fade + "\n#define EARTH_FADE_TICKS " + FADE_TICKS + ".0\n";
    }

    /** How long a finer level takes to fade in over the coarser one when its columns arrive, in hundredths of a second. */
    static final int FADE_TICKS = 60;

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
            #ifdef EARTH_FADE
            uniform uint earth_Now;
            out vec2 earth_band;
            flat out float earth_arrived;
            flat out float earth_merging;
            #endif
            out vec3 earth_vcolour;
            out vec3 earth_vnormal;
            flat out float earth_smooth;
            out vec3 earth_where;
            out vec2 earth_cellAt;
            flat out int earth_grid;

            // How far a face reaches past its edges, per block of distance: about half a pixel at 1080p.
            const float EARTH_GROW = 0.0006;

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

            // Where smooth ground's grid point g lies across its column, in voxels: the edges, then the middle of each voxel column.
            float earth_place(float g) {
                return clamp(g - 0.5, 0.0, 16.0);
            }

            vec3 earth_decodeNormal(uint code) {
                vec2 xz = (vec2(float(code & 63u), float(code >> 6)) - 32.0) / 31.0;
                return vec3(xz.x, sqrt(max(0.0, 1.0 - dot(xz, xz))), xz.y);
            }

            vec3 earth_rgb(uint colour) {
                return vec3(float((colour >> 16) & 255u), float((colour >> 8) & 255u), float(colour & 255u)) / 255.0;
            }

            void main() {
            #ifdef EARTH_INDEXED
                // Drawn by index: four corners a face, 0 1 2 0 2 3 picked by the indices.
                int quadIndex = gl_VertexID >> 2;
                int corner = gl_VertexID & 3;
            #else
                int quadIndex = gl_VertexID / 6;
                int corner = TRIANGLES[gl_VertexID - quadIndex * 6];
            #endif
                uvec4 q = texelFetch(earth_Quads, quadIndex);
                uint face = (q.x >> 10) & 7u;
                float width = float(((q.x >> 13) & 15u) + 1u);
                ivec4 column = texelFetch(earth_Columns, int((q.x >> 25) | ((q.z >> 20) << 7)));
                // The slot's fourth int holds the level, and above it when the column last began to fade (see FarGpu#stamp).
                int level = column.w & 15;
                float v = float(1 << level);
                float x = float(q.x & 31u), z = float((q.x >> 5) & 31u), y = float(int(q.y)) / 16.0;
                uint extent = q.z & 0xFFFFFu;
                vec3 origin = vec3(column.xyz - earth_CameraBlock) - earth_CameraFraction;
                vec3 local;
                earth_smooth = 0.0;
                earth_vcolour = vec3(1.0);
                earth_vnormal = vec3(0.0, 1.0, 0.0);
                earth_cellAt = vec2(0.0);
                earth_grid = 0;
                if (face >= 6u) {
                    // Smooth ground: each corner is a grid point of the column, whose height, colour and slope follow the faces.
                    uint side = q.w >> 20;
                    uint shown = face == 6u ? 1u : 2u + side;
                    vec2 ab = vec2(CORNERS[int(shown) * 4 + corner]);
                    float gx = x, gz = z;
                    if (face == 6u) { gx += ab.x * width; gz += ab.y * float(extent + 1u); }
                    else if (side < 2u) gx += ab.x * width;
                    else gz += ab.x * width;
                    uvec4 point = texelFetch(earth_Quads, int(q.y) + int(gz) * 18 + int(gx));
                    float height = float(int(point.x)) / 256.0;
                    if (face == 7u && ab.y < 0.5) height -= float(extent) / 16.0;
                    local = vec3(earth_place(gx) * v, height, earth_place(gz) * v);
                    earth_cellAt = vec2(earth_place(gx), earth_place(gz));
                    earth_grid = int(q.y);
                    earth_smooth = 1.0;
                    earth_vcolour = earth_rgb(point.y);
                    earth_vnormal = face == 6u ? earth_decodeNormal(point.z)
                            : vec3(side == 2u ? -1.0 : side == 3u ? 1.0 : 0.0, 0.0, side == 0u ? -1.0 : side == 1u ? 1.0 : 0.0);
                    face = shown;
                } else {
                    vec2 ab = vec2(CORNERS[int(face) * 4 + corner]);
                    local = earth_local(face, ab, x, y, z, width, extent, v);
                    // Faces merged to different lengths meet mid-edge, where rounding leaves pinholes
                    // that show the sky through the ground; each face reaches about half a pixel
                    // past its edges, which closes them.
                    vec2 grow = (ab * 2.0 - 1.0) * (EARTH_GROW * length(origin + local));
                    if (face < 2u) local.xz += grow;
                    else if (face < 4u) local.xy += grow;
                    else local.zy += grow;
                }
                vec3 position = origin + local;
                earth_position = position;
                earth_where = local;
                earth_anchor = earth_smooth > 0.5 ? position : origin + earth_local(face, vec2(0.5), x, y, z, width, extent, v);
                earth_reach = 16.0 * v;
            #ifdef EARTH_FADE
                // Where this point is among the coarser level's columns, from the middle of the one under the
                // camera, in column widths: that level's middle, which the finer level draws, ends at ±(hole + 1/2).
                int shift = level + (EARTH_FADE == 1 ? 5 : 4);
                ivec2 inColumn = earth_CameraBlock.xz - ((earth_CameraBlock.xz >> shift) << shift);
                earth_band = (position.xz + vec2(inColumn) + earth_CameraFraction.xz) / float(1 << shift) - 0.5;
                // How far a finer level that has just arrived is through taking over from the coarser one.
                // Where the camera has moved off, a merge runs it the other way, the coarser level taking back over.
                uint stamp = (uint(column.w) >> 4) & 0x7FFFFFFu;
                float since = clamp(float((earth_Now - stamp) & 0x7FFFFFFu) / EARTH_FADE_TICKS, 0.0, 1.0);
                earth_merging = stamp != 0u && (uint(column.w) & 0x80000000u) != 0u ? 1.0 : 0.0;
                earth_arrived = stamp == 0u ? 1.0 : earth_merging > 0.5 ? 1.0 - since : since;
            #endif
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
                // Further out a texture spans more blocks, two to a voxel side, so each voxel reads as a
                // block of its own at any distance instead of a blur of tiny repeats.
                earth_tile /= max(1.0, v * 0.5);

                uint look = q.w & 0xFFFFFu;
                earth_normal = earth_smooth > 0.5 ? 0u : q.w >> 20;
                uvec4 a = texelFetch(earth_Looks, int(look) * 2);
                uvec4 b = texelFetch(earth_Looks, int(look) * 2 + 1);
                earth_sprite = uintBitsToFloat(a);
                earth_average = earth_rgb(b.x);
                // Smooth ground's colours come tinted.
                earth_tint = earth_smooth > 0.5 ? vec3(1.0) : earth_rgb(b.y);
                earth_blockId = b.z;
                earth_flags = b.w;
                earth_face = face;
                earth_light = (q.x >> 17) & 255u;
                earth_look = look;
            }
            """;

    /** Smooth ground's blended colour and slope, declared once whichever part needs them first. */
    private static final String SMOOTH_IN = """
            #ifndef EARTH_SMOOTH_IN
            #define EARTH_SMOOTH_IN
            in vec3 earth_vcolour;
            in vec3 earth_vnormal;
            flat in float earth_smooth;
            in vec3 earth_where;
            in vec2 earth_cellAt;
            flat in int earth_grid;
            #endif
            """;

    /** Shared by both fragment shaders: the face's texel (or average colour), before tint. */
    private static final String SAMPLE = SMOOTH_IN + """
            #ifndef EARTH_TILE_IN
            #define EARTH_TILE_IN
            in vec2 earth_tile;
            #endif
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
            uniform usamplerBuffer earth_Quads;
            uniform usamplerBuffer earth_Looks;

            // The texture this pixel shows: the face's own, or on textured smooth ground the block of
            // the voxel column under the pixel, laid from above on gentle slopes and from the side on steep ones.
            bool earth_looked = false;
            vec4 earth_hereSprite;
            vec2 earth_hereTile;
            vec3 earth_hereAverage;

            void earth_here() {
                if (earth_looked) return;
                earth_looked = true;
                earth_hereSprite = earth_sprite;
                earth_hereTile = earth_tile;
                earth_hereAverage = earth_average;
                if (earth_smooth < 0.5 || (earth_flags & 1u) == 0u) return;
                ivec2 cell = clamp(ivec2(floor(earth_cellAt)), ivec2(0), ivec2(15));
                uint id = texelFetch(earth_Quads, earth_grid + (cell.y + 1) * 18 + cell.x + 1).w;
                if (id != 0u) {
                    earth_hereSprite = uintBitsToFloat(texelFetch(earth_Looks, int(id - 1u) * 2));
                    uint average = texelFetch(earth_Looks, int(id - 1u) * 2 + 1).x;
                    earth_hereAverage = vec3(float((average >> 16) & 255u), float((average >> 8) & 255u), float(average & 255u)) / 255.0;
                }
                vec3 n = earth_vnormal;
                vec3 w = earth_where;
                if (n.y * n.y > 0.5 * dot(n, n)) earth_hereTile = w.xz;
                else if (abs(n.z) >= abs(n.x)) earth_hereTile = vec2(n.z > 0.0 ? w.x : -w.x, -w.y);
                else earth_hereTile = vec2(n.x > 0.0 ? -w.z : w.z, -w.y);
            }

            vec2 earth_atlasUv() {
                earth_here();
                return earth_hereSprite.xy + fract(earth_hereTile) * (earth_hereSprite.zw - earth_hereSprite.xy);
            }

            vec4 earth_sample() {
                if (earth_smooth > 0.5 && (earth_flags & 1u) == 0u) return vec4(earth_vcolour, 1.0);
                if ((earth_flags & 1u) == 0u) return vec4(earth_average, 1.0);
                earth_here();
                vec2 size = earth_hereSprite.zw - earth_hereSprite.xy;
                vec4 texel = textureGrad(earth_Atlas, earth_atlasUv(), dFdx(earth_hereTile) * size, dFdy(earth_hereTile) * size);
                // Smooth ground's colour blends from voxel column to voxel column; the texture keeps its pattern on top.
                if (earth_smooth > 0.5) return vec4(earth_vcolour * min(texel.rgb / max(earth_hereAverage, vec3(1.0 / 255.0)), vec3(3.0)), 1.0);
                return vec4(texel.rgb, 1.0);
            }

            vec2 earth_levels() {
                return vec2(float(earth_light >> 4), float(earth_light & 15u));
            }

            #ifdef EARTH_FADE
            in vec2 earth_band;
            flat in float earth_arrived;
            flat in float earth_merging;
            uniform float earth_FadeHole;
            uniform float earth_DitherFrame;

            // Drops the fragment where the other level has this pixel: across the coarser level's outer
            // ring of middle columns the finer level's share falls from all to none, by a dither both share.
            // Where a finer level has only just arrived, its share also grows from none to all over a moment,
            // and where the camera has moved off, it shrinks back to none.
            void earth_fade() {
                float coarse = smoothstep(earth_FadeHole - 0.5, earth_FadeHole + 0.5, max(abs(earth_band.x), abs(earth_band.y)));
                // A merge happens just outside the ring, where the coarser level has it all: there only the time counts.
                coarse = earth_merging > 0.5 ? 1.0 - earth_arrived : max(coarse, 1.0 - earth_arrived);
                vec2 pixel = gl_FragCoord.xy + 5.588238 * earth_DitherFrame;
                float dither = fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
                if (EARTH_FADE == 1 ? dither < coarse : dither >= coarse) discard;
            }
            #endif
            """;

    /**
     * The ground slope a far face stands for, from {@link FarMesher#normalCode}; a zero vector for
     * faces that keep their own normal (foliage and undersides).
     */
    private static final String SLOPE = SMOOTH_IN + """
            flat in uint earth_normal;
            #ifndef EARTH_TILE_IN
            #define EARTH_TILE_IN
            in vec2 earth_tile;
            #endif

            // How far a face is lit as its ground's slope rather than as its own face. Where a voxel
            // spans plenty of pixels it is lit face by face, as a block is, with a lit top and shaded
            // sides. Smaller, a stepped hillside lit that way breaks into stripes of lit treads and
            // dark risers, so there the slope takes most of it over and the steps keep only a
            // light relief. A texture spans half a voxel.
            float earth_faceShare() {
                float pixels = 2.0 / max(length(fwidth(earth_tile)), 1e-5);
                return smoothstep(2.0, 10.0, pixels);
            }

            float earth_slopeShare() {
                return 0.85 * (1.0 - earth_faceShare());
            }

            vec3 earth_slope() {
                if (earth_smooth > 0.5) return normalize(earth_vnormal);
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
                float even = (weight.x * earth_Shade[4] + weight.y * earth_Shade[1] + weight.z * earth_Shade[2]) * corners;
                return mix(earth_Shade[earth_face], even, earth_slopeShare());
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
            #ifdef EARTH_FADE
                earth_fade();
            #endif
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

            // A far voxel's face lit as its own face, as a block is, until it shrinks so small that
            // the slope its stack of voxels stands for takes over (see earth_slopeShare); foliage
            // keeps its face's normal.
            vec3 earth_packNormal(vec3 face) {
                vec3 slope = earth_slope();
                return slope == vec3(0.0) ? face : normalize(mix(face, slope, earth_slopeShare()));
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
            uniform float earth_NearReach;

            void earth_slopeDepth() {
                vec3 slope = earth_slope();
                float along = dot(slope, earth_position);
                float t = along == 0.0 ? 1.0 : dot(slope, earth_anchor) / along;
                // Smooth ground already has its slope's depth.
                if (earth_smooth > 0.5 || slope == vec3(0.0) || t <= 0.0 || abs(t - 1.0) * length(earth_position) > earth_reach) {
                    gl_FragDepth = gl_FragCoord.z;
                    return;
                }
                // Voxels many pixels wide keep their faces' depth, as the game's blocks do, so the pack's
                // screen-space shadows step across them alike and the near edge doesn't show; the slope
                // takes over as they shrink, wholly where steps a pixel or two wide shadowed in stripes.
                // Only from the game's blocks' edge out: inside it far terrain only fills in for blocks
                // not here yet (just after arriving, with Earth data still downloading), and there
                // whole voxel faces at their own depth drew as huge walls under the pack.
                float beyond = smoothstep(earth_NearReach - 32.0, earth_NearReach - 8.0, length(earth_position.xz));
                t = mix(t, 1.0, earth_faceShare() * beyond);
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
            // Last frame's shadows of far hills (see FarLod), and how much they darken.
            uniform sampler2D earth_SunShadows;
            uniform mat4 earth_ShadowFrame;
            uniform float earth_ShadowShade;
            uniform float earth_Occlusion;
            uniform float earth_Detail;

            // What far terrain loses to its hills: x the share of its colour (their shadow), y the share
            // of the sky's light that their closeness keeps off it (which the pack takes from sky light).
            vec2 earth_sunShadow() {
                // Near terrain's nooks between blocks keep some sky off it; far off they're smaller than
                // a pixel, so far terrain takes that on as it recedes.
                float detail = earth_Detail * smoothstep(256.0, 1536.0, length(earth_position));
                vec2 shade = vec2(0.0);
                vec4 clip = earth_ShadowFrame * vec4(earth_position, 1.0);
                vec2 uv = clip.xy / max(clip.w, 1e-6) * 0.5 + 0.5;
                if (clip.w > 0.0 && all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)))) {
                    shade = texture(earth_SunShadows, uv).rg;
                }
                // The nooks shade some sunlight too, which only the colour can take.
                float colour = 1.0 - (1.0 - shade.r * earth_ShadowShade) * (1.0 - 0.2 * detail);
                return vec2(colour, 1.0 - (1.0 - shade.g * earth_Occlusion) * (1.0 - detail));
            }
            #endif

            void main() {
            #ifdef EARTH_FADE
                earth_fade();
            #endif
            #ifdef EARTH_NEAR
                if (length(earth_position.xz) >= earth_NearReach) discard;
            #endif
            #ifdef EARTH_OWN_DEPTH
                if (texelFetch(earth_GameDepth, ivec2(gl_FragCoord.xy), 0).r < 1.0) discard;
                // Inside the game's blocks' reach far terrain only fills in for blocks still loading,
                // and goes in the game's own depth (EARTH_NEAR): the pack takes its own depth for
                // distant level-of-detail terrain, and shading far voxels there right round the player
                // (as after arriving with Earth data still downloading) drew huge walls and slabs.
                if (length(earth_position.xz) < earth_NearReach) discard;
                earth_faceDepth = gl_FragCoord.z;
                earth_slopeDepth();
            #endif
                vec4 colour = earth_sample();
                vec2 levels = earth_levels();
                vec3 tint = earth_tint;
            #ifdef EARTH_OWN_DEPTH
                vec2 lost = earth_sunShadow();
                tint *= 1.0 - lost.x;
                // Packs light by the square of sky light, so this keeps off the share of it asked for.
                levels.y *= sqrt(1.0 - lost.y);
            #endif
                vec2 light = clamp(levels / 15.0 * (15.0 / 16.0) + 0.5 / 16.0, vec2(8.0 / 256.0), vec2(248.0 / 256.0));
                voxy_emitFragment(VoxyFragmentParameters(colour, floor(earth_tile), earth_atlasUv(), earth_face, earth_look, light,
                        vec4(tint, 1.0), earth_blockId));
            }
            """;

    /** For packs without Voxy support: the far terrain's lit colour into the pack's first terrain target. */
    static final String PACK_FALLBACK = SAMPLE + SHADE + """
            uniform sampler2D earth_Lightmap;

            layout(location = 0) out vec4 earth_fragColor;

            void main() {
            #ifdef EARTH_FADE
                earth_fade();
            #endif
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
    static String packFragment(String version, String patch, boolean ownDepth) { return packFragment(version, patch, ownDepth, 0); }

    /** The same for a fade (see {@link #fade}). */
    static String packFragment(String version, String patch, boolean ownDepth, int fade) {
        StringBuilder extensions = new StringBuilder(), body = new StringBuilder();
        for (String line : patch.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#extension")) extensions.append(trimmed).append('\n');
            else if (!trimmed.startsWith("#version")) body.append(line).append('\n');
        }
        String patched = FACE_NORMAL.matcher(body).replaceFirst("$1\n    $2 = earth_packNormal($2);");
        return version + extensions + (ownDepth ? "#define EARTH_OWN_DEPTH\n" : "") + fade(fade) + VOXY_PARAMETERS + patched
                + (ownDepth ? faceDepthAt(PACK_MAIN, faceDepthLocation(patch)) : PACK_MAIN);
    }

    /** The same for far terrain inside the game's blocks' reach, drawn into the game's depth while far terrain otherwise has its own. */
    static String packNearFragment(String version, String patch, int fade) {
        return packFragment(version, patch, false, fade).replaceFirst("\n", "\n#define EARTH_NEAR\n");
    }

    // Depth pre-pass (-Dearthplatform.far.depthPrepass=true; see FarGpu#DEPTH_PREPASS) ---------------

    /**
     * Put after the version and defines of both pre-pass vertex shaders, so the pre-pass and the
     * shading pass place every corner, and so every fragment's window depth, bit for bit alike.
     */
    static final String INVARIANT = "invariant gl_Position;\n";

    /** Everything of {@link #PACK_MAIN} before its main: the sample, the slope depth and the own-depth declarations. */
    private static final String PACK_DECLARATIONS = PACK_MAIN.substring(0, PACK_MAIN.lastIndexOf("void main() {"));

    /**
     * The pre-pass: exactly the own-depth program's fragment-dropping and depth (the game's terrain,
     * the game's blocks' reach, a fade's dither, the slope depth), and its faces' own depth into the
     * second target, with no colour. Depth and that target then hold what the one-pass draw leaves.
     */
    private static final String PREPASS_MAIN = """
            void main() {
            #ifdef EARTH_FADE
                earth_fade();
            #endif
                if (texelFetch(earth_GameDepth, ivec2(gl_FragCoord.xy), 0).r < 1.0) discard;
                if (length(earth_position.xz) < earth_NearReach) discard;
                earth_faceDepth = gl_FragCoord.z;
                earth_slopeDepth();
            }
            """;

    /**
     * The shading pass after the pre-pass: the own-depth program's colour, with nothing that drops
     * fragments (but a fade's dither) or writes depth, so the GPU tests depth before shading. It is
     * drawn with depth test EQUAL against the pre-pass's faces' own depth, so only the face each
     * pixel kept in the pre-pass is shaded.
     */
    private static final String SHADE_MAIN = """
            void main() {
            #ifdef EARTH_FADE
                earth_fade();
            #endif
                vec4 colour = earth_sample();
                vec2 levels = earth_levels();
                vec3 tint = earth_tint;
                vec2 lost = earth_sunShadow();
                tint *= 1.0 - lost.x;
                levels.y *= sqrt(1.0 - lost.y);
                vec2 light = clamp(levels / 15.0 * (15.0 / 16.0) + 0.5 / 16.0, vec2(8.0 / 256.0), vec2(248.0 / 256.0));
                voxy_emitFragment(VoxyFragmentParameters(colour, floor(earth_tile), earth_atlasUv(), earth_face, earth_look, light,
                        vec4(tint, 1.0), earth_blockId));
            }
            """;

    /** The slope depth's inputs the shading pass still reads, in place of the slope depth itself. */
    private static final String SHADE_INPUTS = """
            in vec3 earth_position;
            uniform float earth_NearReach;
            """;

    /** The depth pre-pass's fragment shader for a fade: no shader pack code, but its faces' depth where the pack's program has it. */
    static String packPrepassFragment(String version, String patch, int fade) {
        return version + "#define EARTH_OWN_DEPTH\n" + fade(fade) + VOXY_PARAMETERS + faceDepthAt(PACK_DECLARATIONS, faceDepthLocation(patch)) + PREPASS_MAIN;
    }

    /** The shading pass's fragment shader for a fade: the pack's Voxy code, as {@link #packFragment} splices it, without the depth work. */
    static String packShadeFragment(String version, String patch, int fade) {
        String own = packFragment(version, patch, true, fade);
        int location = faceDepthLocation(patch);
        String declarations = faceDepthAt(PACK_DECLARATIONS.replace(SLOPE_DEPTH, SHADE_INPUTS), location);
        return own.substring(0, own.length() - faceDepthAt(PACK_MAIN, location).length()) + declarations + SHADE_MAIN;
    }

    private static final Pattern BREAKS_PREPASS = Pattern.compile("\\bdiscard\\b|\\bgl_FragDepth\\b|\\bgl_SampleMask\\b|\\bdepth_(any|greater|less|unchanged)\\b");

    /**
     * Whether a pack's Voxy fragment code (as Iris preprocessed it, includes and all) leaves every
     * fragment it is given and its depth alone, which the pre-pass relies on: it decides each
     * pixel's face without the pack's code. Any mention of dropping fragments or writing depth,
     * even in a comment, keeps the pack on the one-pass draw.
     */
    static boolean prepassSafe(@Nullable String patch) {
        return patch != null && !BREAKS_PREPASS.matcher(patch).find();
    }
}
