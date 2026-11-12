#version 150
uniform sampler2D Sampler0;
uniform sampler2D Weather1;
uniform sampler2D Weather2;
uniform sampler2D SceneDepth;
uniform sampler3D NoiseVolume;
uniform vec4 WeatherPhase;
uniform vec4 ColorModulator;
uniform mat4 SkyInvProjection;
uniform mat4 SkyInvView;
uniform float CameraHeight;
uniform vec3 CloudLight;
uniform vec3 SunDirection;
uniform vec4 Band0;
uniform vec4 Band1;
uniform vec4 Band2;
uniform vec2 Phase0;
uniform vec2 Phase1;
uniform vec2 Phase2;
uniform vec3 NoisePhase0;
uniform vec3 NoisePhase1;
uniform float MaxOpacity;
uniform int MarchSteps;
in vec2 weatherUV;
in vec4 weatherTint;
out vec4 weatherColour;

vec4 band(int kind) { return kind == 0 ? Band0 : kind == 1 ? Band1 : Band2; }

float heightInBand(vec3 p, vec4 b) {
    return (CameraHeight + p.y - b.x) / b.y + 0.5;
}

float density(vec3 p, int kind) {
    vec4 b = band(kind);
    float h = heightInBand(p, b);
    if (h <= 0.0 || h >= 1.0) return 0.0;
    float angle = kind == 0 ? 0.35 : kind == 1 ? 1.2 : -0.6;
    float c = cos(angle), s = sin(angle);
    vec2 offset = kind == 0 ? Phase0 : kind == 1 ? Phase1 : Phase2;
    vec2 uv = vec2(c * p.x - s * p.z, s * p.x + c * p.z) / b.z + offset;
    // Fixed explicit LOD: derivatives inside the march vary at band/terrain boundaries.
    float cover = kind == 0 ? textureLod(Sampler0, uv, 0.0).a
            : kind == 1 ? textureLod(Weather1, uv, 0.0).a : textureLod(Weather2, uv, 0.0).a;
    if (cover < 0.2) return 0.0;
    // ~20 km weather cells evolve over ~10 real minutes per lattice step.
    // World-relative coordinates keep fronts in place as the camera moves.
    float front = textureLod(NoiseVolume, vec3(p.x/1280000.0 + WeatherPhase.x,
            p.z/1280000.0 + WeatherPhase.y, WeatherPhase.z), 0.0).r;
    cover *= smoothstep(0.38, 0.68, front + WeatherPhase.w);
    if (cover < 0.2) return 0.0;
    vec3 shapeAt = p / 18000.0 + NoisePhase0;
    vec3 detailAt = p / 4500.0 + NoisePhase1;
    float body = textureLod(NoiseVolume, shapeAt, 0.0).r;
    float erosion = textureLod(NoiseVolume, detailAt, 0.0).r;
    // Dense centres grow higher than the edges: billows rather than a flat slab roof.
    float bottom = 0.08 + 0.08 * (1.0 - cover);
    float top = clamp(0.25 + 0.7 * cover + 0.12 * (body - 0.5), bottom + 0.18, 1.0);
    float profile = smoothstep(bottom, bottom + 0.13, h) * (1.0 - smoothstep(top - 0.25, top, h));
    return smoothstep(0.0, 0.5, cover - 0.25 + 0.3 * (body - 0.5) - 0.12 * erosion)
            * profile * b.w;
}

vec2 interval(vec3 ray, vec4 b, float limit) {
    float low = b.x - b.y * 0.5 - CameraHeight;
    float high = b.x + b.y * 0.5 - CameraHeight;
    if (abs(ray.y) < 1e-5) return low <= 0.0 && high >= 0.0 ? vec2(1.0, limit) : vec2(limit, limit);
    float a = low / ray.y, z = high / ray.y;
    return vec2(max(1.0, min(a, z)), min(limit, max(a, z)));
}

void main() {
    vec4 projected = SkyInvProjection * vec4(weatherUV * 2.0 - 1.0, 1.0, 1.0);
    vec3 viewRay = normalize(projected.xyz / projected.w);
    vec3 ray = normalize((SkyInvView * vec4(viewRay, 0.0)).xyz);
    float limit = 64000.0;
    float sceneLimit = limit;
    float scene = texture(SceneDepth, weatherUV).r;
    if (scene < 1.0) {
        vec4 hit = SkyInvProjection * vec4(weatherUV * 2.0 - 1.0, scene * 2.0 - 1.0, 1.0);
        sceneLimit = min(limit, length(hit.xyz / hit.w));
    }
    vec2 spans[3];
    int order[3] = int[](0, 1, 2);
    for (int i = 0; i < 3; i++) spans[i] = interval(ray, band(i), limit);
    for (int i = 0; i < 2; i++) for (int j = i + 1; j < 3; j++) {
        if (spans[order[j]].x < spans[order[i]].x) { int swap = order[i]; order[i] = order[j]; order[j] = swap; }
    }
    float transmission = 1.0;
    vec3 light = vec3(0.0);
    vec3 sun = normalize(SunDirection);
    for (int layer = 0; layer < 3; layer++) {
        int kind = order[layer];
        // A thin high deck is unresolved at grazing angles: fade its optical depth
        // instead of magnifying its near-horizontal boundary into a straight ribbon.
        float layerVisibility = kind == 2 ? smoothstep(0.025, 0.1, abs(ray.y)) : 1.0;
        if (layerVisibility < 0.001) continue;
        vec2 span = spans[kind];
        if (span.y <= span.x) continue;
        for (int i = 0; i < 32; i++) {
            if (i >= MarchSteps || transmission < 0.035) break;
            // Spend more samples near the bank entry, especially while flying inside it.
            float a = float(i) / float(MarchSteps), z = float(i + 1) / float(MarchSteps);
            float start = mix(span.x, span.y, a * a), end = mix(span.x, span.y, z * z);
            // The scene clips the march, never relocates its samples. Re-spacing samples
            // per terrain triangle printed the terrain/depth grid into foreground clouds.
            if (start >= sceneLimit) break;
            end = min(end, sceneLimit);
            float stepSize = end - start;
            float t = (start + end) * 0.5;
            vec3 p = ray * t;
            float d = density(p, kind) * layerVisibility * smoothstep(20.0, 100.0, t)
                    * (1.0 - smoothstep(48000.0, 64000.0, t));
            if (d <= 0.001) continue;
            float extinction = 0.0035 * d;
            float alpha = 1.0 - exp(-extinction * min(stepSize, 1800.0));
            float h = clamp(heightInBand(p, band(kind)), 0.0, 1.0);
            float shade = kind == 2 ? 1.0 : exp(-2.5 * density(p + sun * 220.0, kind));
            float sunUp = smoothstep(-0.08, 0.15, sun.y);
            float silver = pow(max(0.0, dot(ray, sun)), 12.0) * (1.0 - d) * 0.35 * sunUp;
            vec3 colour = CloudLight * (0.38 + 0.27 * h + 0.35 * shade * sunUp + silver);
            float haze = smoothstep(18000.0, 64000.0, t) * 0.3;
            colour = mix(colour, CloudLight * vec3(0.8, 0.9, 1.0), haze);
            light += transmission * alpha * colour;
            transmission *= 1.0 - alpha;
        }
    }
    float alpha = 1.0 - transmission;
    if (alpha <= 0.001) discard;
    // Extinction stays local to occupied cloud rays. No global fog or altitude whiteout.
    weatherColour = vec4(light / max(alpha, 0.001), min(alpha, MaxOpacity)) * weatherTint * ColorModulator;
}
