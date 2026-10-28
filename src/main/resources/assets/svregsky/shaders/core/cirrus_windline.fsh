#version 330

precision highp float;

layout(std140) uniform DynamicTransforms {
    highp mat4 ModelViewMat;
    highp vec4 ColorModulator;
    highp vec3 ModelOffset;
    highp mat4 TextureMat;
};

in highp vec4 vColor;

out highp vec4 fragColor;

void main() {
    highp vec4 c = vColor * ColorModulator;
    if (c.a < 0.004) {
        discard;
    }
    fragColor = c;
}
