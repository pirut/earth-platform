#version 150
in vec3 Position;
in vec2 UV0;
in vec4 Color;
out vec2 weatherUV;
out vec4 weatherTint;
void main() {
    weatherUV = UV0;
    weatherTint = Color;
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
