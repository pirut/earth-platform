package io.earthplatform.minecraft.cubic.client;

/**
 * GLSL for {@link FarLod}'s full-screen depth passes under a shader pack: merging the game's depth
 * with far terrain's, laying in far water's surface and floor, smoothing far terrain's depth, and
 * marching sun shadows and ambient occlusion across it. {@link FarLod} links these into its
 * programs on the render thread; the far terrain shaders themselves live in {@link FarShaders}.
 */
final class FarLodShaders {
    private FarLodShaders() {}

    static final String FULL_SCREEN = """
            #version 330 core
            out vec2 earth_uv;
            void main() {
                vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                earth_uv = corner;
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /**
     * The nearer of the game's depth and far terrain's, in the game's projection; also kept as a
     * colour to compare with later. Far terrain's faces' own depth is used rather than its
     * smoothed slopes', which can lie over a narrow river.
     */
    static final String MERGE = """
            #version 330 core
            uniform sampler2D earth_Game;
            uniform sampler2D earth_Far;
            uniform mat4 earth_FarInverse;
            uniform mat4 earth_GameProjection;
            in vec2 earth_uv;
            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy);
                float game = texelFetch(earth_Game, texel, 0).r;
                float far = texelFetch(earth_Far, texel, 0).r;
                float merged = game;
                if (far < 1.0) {
                    vec4 view = earth_FarInverse * vec4(vec3(earth_uv, far) * 2.0 - 1.0, 1.0);
                    vec4 clip = earth_GameProjection * vec4(view.xyz / view.w, 1.0);
                    merged = min(game, clamp(clip.z / clip.w * 0.5 + 0.5, 0.0, 1.0));
                }
                gl_FragDepth = merged;
            }
            """;

    /** Far water's surface, in the game's depth, unless far terrain stands in front of it. */
    static final String SURFACE = """
            #version 330 core
            uniform sampler2D earth_Far;
            uniform vec2 earth_NearFar;
            in vec3 earth_view;
            void main() {
                float far = texelFetch(earth_Far, ivec2(gl_FragCoord.xy), 0).r;
                float n = earth_NearFar.x, f = earth_NearFar.y;
                float front = 2.0 * n * f / (f + n - (far * 2.0 - 1.0) * (f - n));
                if (far < 1.0 && -earth_view.z > front + 2.0) discard;
            }
            """;

    /** One direction of {@link FarLod#smooth}: a Gaussian over nine pixels of linear depth, leaving out pixels on the far side of an edge. */
    static final String SMOOTH = """
            #version 330 core
            uniform sampler2D earth_Game;
            uniform ivec2 earth_Step;
            uniform vec2 earth_NearFar;
            uniform float earth_Focal;
            uniform float earth_NearReach;
            in vec2 earth_uv;

            float earth_linear(float depth) {
                float n = earth_NearFar.x, f = earth_NearFar.y;
                return 2.0 * n * f / (f + n - (depth * 2.0 - 1.0) * (f - n));
            }

            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy);
                float centre = texelFetch(earth_Game, texel, 0).r;
                if (centre >= 1.0) {
                    gl_FragDepth = centre;
                    return;
                }
                float z = earth_linear(centre), sum = z, weights = 1.0;
                ivec2 size = textureSize(earth_Game, 0);
                for (int i = 1; i <= 4; i++) {
                    float weight = exp(-float(i * i) / 8.0);
                    for (int sign = -1; sign <= 1; sign += 2) {
                        ivec2 at = clamp(texel + earth_Step * i * sign, ivec2(0), size - 1);
                        float other = texelFetch(earth_Game, at, 0).r;
                        if (other >= 1.0) continue;
                        float zo = earth_linear(other);
                        if (abs(zo - z) > 0.03 * z) continue;
                        sum += zo * weight;
                        weights += weight;
                    }
                }
                // Close to the game's blocks, where the finest far voxels (two blocks) span plenty of
                // pixels, the steps keep their depth so the pack shadows them step by step as it does
                // the blocks in front (see FarShaders' earth_faceShare); smaller, they're smoothed.
                // Only from the blocks' edge out, as in FarShaders' earth_slopeDepth.
                float steps = smoothstep(2.0, 10.0, earth_Focal * 2.0 / z) * smoothstep(earth_NearReach - 32.0, earth_NearReach - 8.0, z);
                float n = earth_NearFar.x, f = earth_NearFar.y, smoothed = mix(sum / weights, z, steps);
                gl_FragDepth = clamp(((f + n - 2.0 * n * f / smoothed) / (f - n)) * 0.5 + 0.5, 0.0, 1.0);
            }
            """;

    static final String DEEPEN_VERTEX = """
            #version 330 core
            layout(location = 0) in vec3 Position;
            uniform mat4 earth_ModelView;
            uniform mat4 earth_Projection;
            uniform vec3 earth_Offset;
            out vec3 earth_view;
            void main() {
                vec4 view = earth_ModelView * vec4(Position + earth_Offset, 1.0);
                earth_view = view.xyz;
                gl_Position = earth_Projection * view;
            }
            """;

    /**
     * Far water's floor, where the water shows over far terrain: its bed's own depth, or the least
     * depth if that is nearer. It replaces the slope depth there, which across a narrow channel
     * can lie well off the bed.
     */
    static final String DEEPEN = """
            #version 330 core
            uniform sampler2D earth_Game;
            uniform sampler2D earth_Far;
            uniform mat4 earth_Projection;
            uniform float earth_Least;
            uniform float earth_GameNear;
            uniform vec2 earth_NearFar;
            in vec3 earth_view;
            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy);
                float bed = texelFetch(earth_Far, texel, 0).r;
                if (bed >= 1.0 || texelFetch(earth_Game, texel, 0).r < 1.0) discard;
                // Far water lies level with the top of its bed's voxel, so it may sit a little behind it.
                float n = earth_NearFar.x, f = earth_NearFar.y;
                float behind = 2.0 * n * f / (f + n - (bed * 2.0 - 1.0) * (f - n));
                if (-earth_view.z > behind + 2.0) discard;
                // The pack's water is tested against this floor through the game's projection (see
                // beginWater), where one step of a float depth spans z * z * 2^-24 / near blocks: a
                // hundred at 10 km. Within a few steps the surface and floor round alike and the
                // water drew on alternate rows and flickered as the camera moved.
                float distance = length(earth_view);
                float step = distance * distance * 5.96e-8 / earth_GameNear;
                vec4 clip = earth_Projection * vec4(earth_view * ((distance + max(earth_Least, 4.0 * step)) / distance), 1.0);
                gl_FragDepth = max(clamp(clip.z / clip.w * 0.5 + 0.5, 0.0, 1.0), bed);
            }
            """;

    /**
     * One step of {@link FarLod#shadows}: red is 1 where a far hill stands between the pixel and the sun,
     * green how far the ground around closes in above the pixel (walls and floors of valleys).
     */
    static final String SUN = """
            #version 330 core
            uniform sampler2D earth_Game;
            uniform mat4 earth_Projection;
            uniform mat4 earth_FarInverse;
            uniform vec3 earth_Sun;
            uniform bool earth_SunUp;
            uniform vec3 earth_Up;
            uniform vec2 earth_Focal;
            in vec2 earth_uv;
            out vec2 earth_shadow;

            vec3 earth_view(vec2 uv, float depth) {
                vec4 view = earth_FarInverse * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
                return view.xyz / view.w;
            }

            float earth_sun(vec3 start, float distance, float noise) {
                if (!earth_SunUp) return 0.0;
                start += earth_Sun * (1.0 + 0.004 * distance);
                float t = (2.0 + 0.002 * distance) * (1.0 + noise);
                for (int i = 0; i < 32; i++, t *= 1.3) {
                    vec3 at = start + earth_Sun * t;
                    vec4 clip = earth_Projection * vec4(at, 1.0);
                    if (clip.w <= 0.0) break;
                    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
                    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) break;
                    float other = texture(earth_Game, uv).r;
                    if (other >= 1.0) continue;
                    float behind = -at.z, front = -earth_view(uv, other).z;
                    // Behind far terrain, but not so far behind it that the step passed beyond a ridge.
                    if (front < behind - 0.5 - 0.001 * behind && behind - front < 30.0 + 0.3 * t) return 1.0;
                }
                return 0.0;
            }

            // How far the terrain round the pixel rises above it, out to a share of its distance: the
            // ambient light a valley floor or a wall at the foot of a cliff doesn't get.
            float earth_occlusion(vec3 centre, float distance, float noise) {
                float radius = 24.0 + 0.02 * distance, total = 0.0;
                vec2 pixels = earth_Focal * radius / distance;
                for (int i = 0; i < 12; i++) {
                    float angle = (float(i) + noise) * 2.39996323;
                    float reach = sqrt((float(i) + 0.5) / 12.0);
                    vec2 uv = earth_uv + vec2(cos(angle), sin(angle)) * reach * pixels / vec2(textureSize(earth_Game, 0));
                    float other = texture(earth_Game, uv).r;
                    if (other >= 1.0) continue;
                    vec3 step = earth_view(uv, other) - centre;
                    float span = length(step);
                    if (span < 1.0 || span > 2.0 * radius) continue;
                    total += clamp(dot(step, earth_Up) / span - 0.1, 0.0, 1.0);
                }
                return total / 12.0;
            }

            void main() {
                float depth = texelFetch(earth_Game, ivec2(gl_FragCoord.xy), 0).r;
                earth_shadow = vec2(0.0);
                if (depth >= 1.0) return;
                vec3 start = earth_view(earth_uv, depth);
                float distance = length(start);
                // Interleaved noise spreads the steps between neighbouring pixels.
                float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
                earth_shadow = vec2(earth_sun(start, distance, noise), earth_occlusion(start, distance, noise));
            }
            """;
}
