#version 330

precision highp float;

#moj_import <minecraft:dynamictransforms.glsl>

in vec4 vColor;

out vec4 fragColor;

void main() {
    vec4 c = vColor * ColorModulator;
    if (c.a < 0.004) {
        discard;
    }
    fragColor = c;
}
