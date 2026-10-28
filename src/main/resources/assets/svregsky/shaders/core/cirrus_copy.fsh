#version 330

precision highp float;

// SvregSky: copies the scene colour into a texture the soft-glow / depth-of-field pass can read

layout(std140) uniform DynamicTransforms {
    highp mat4 ModelViewMat;
    highp vec4 ColorModulator;
    highp vec3 ModelOffset;
    highp mat4 TextureMat;
};

uniform highp sampler2D Sampler0;

in highp vec2 uv;

out highp vec4 fragColor;

void main() {
    fragColor = vec4(texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0).rgb, 1.0) * ColorModulator;
}
