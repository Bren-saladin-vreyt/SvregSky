#version 330

precision highp float;

// SvregSky: copies the scene colour into a texture the soft-glow / depth-of-field pass can read

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec2 uv;

out vec4 fragColor;

void main() {
    fragColor = vec4(texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0).rgb, 1.0) * ColorModulator;
}
