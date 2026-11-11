#version 150
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
uniform float EarthDepthBias;
out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
void main() {
    vec3 p = Position + ChunkOffset;
    float r = max(0.0, length(p.xz) - 2000.0);
    p.y -= EarthCurvature * r * r;
    gl_Position = ProjMat * ModelViewMat * vec4(p, 1.0);
    // Change depth only. Radially scaling each column moved its edges apart.
    gl_Position.z -= 2.0 * EarthDepthBias * gl_Position.w;
    vertexDistance = FogShape == 0 ? length(p) : max(length(p.xz), abs(p.y));
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
}
