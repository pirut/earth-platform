#version 150

// The game's rendertype_translucent, with far water dropped by the Earth's curve as far terrain is
// (see EarthView) and pulled nearer in depth only, so neighbouring columns keep their shared edges.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;
uniform int FogShape;
uniform float EarthCurvature;
uniform float EarthFlat;
uniform float EarthDepthBias;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    vec3 pos = Position + ChunkOffset;
    float curveRadius = max(0.0, length(pos.xz) - EarthFlat);
    pos.y -= EarthCurvature * curveRadius * curveRadius;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    // EarthDepthBias steps of the [0, 1] depth range nearer, wherever the vertex is.
    gl_Position.z -= 2.0 * EarthDepthBias * gl_Position.w;
    vertexDistance = FogShape == 0 ? length(pos) : max(length(pos.xz), abs(pos.y));
    vertexColor = Color * texture(Sampler2, clamp(vec2(UV2) / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    texCoord0 = UV0;
}
