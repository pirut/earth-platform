#version 150
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    vec4 colour = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (colour.a < 0.1) discard;
    float fog = smoothstep(FogStart, FogEnd, vertexDistance);
    fragColor = vec4(mix(colour.rgb, FogColor.rgb, fog * FogColor.a), colour.a);
}
