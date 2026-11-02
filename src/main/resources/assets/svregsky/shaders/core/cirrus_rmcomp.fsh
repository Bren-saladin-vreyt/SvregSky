#version 330

precision highp float;

// SvregSky "Raymarch Ghibli": upscales the small cloud buffer with sharp pixels and writes each cloud pixel's depth,
// so terrain, roofs and trees in front still hide the clouds (hardware depth test, which also works on phones).
// ColorModulator = buffer scale (screen pixels per cloud pixel), depth zero-to-one (1) or minus-one-to-one (0)

layout(std140) uniform DynamicTransforms {
    highp mat4 ModelViewMat;
    highp vec4 ColorModulator;
    highp vec3 ModelOffset;
    highp mat4 TextureMat;
};

layout(std140) uniform Projection {
    highp mat4 ProjMat;
};

uniform highp sampler2D Sampler0;

in highp vec2 uv;

out highp vec4 fragColor;

void main() {
    highp float scale = ColorModulator.x;
    highp vec4 c = texelFetch(Sampler0, ivec2(gl_FragCoord.xy / scale), 0);
    if (c.a < 1.0 / 255.0) {
        discard;
    }
    highp float t = exp2((1.0 - c.a) * log2(1.0 + 4096.0)) - 1.0;
    highp vec3 vd = normalize(vec3((uv * 2.0 - 1.0) / vec2(ProjMat[0][0], ProjMat[1][1]), -1.0));
    highp vec4 clip = ProjMat * vec4(vd * t, 1.0);
    highp float ndc = clip.z / clip.w;
    gl_FragDepth = clamp(ColorModulator.y > 0.5 ? ndc : ndc * 0.5 + 0.5, 0.0, 1.0);
    fragColor = vec4(c.rgb, 1.0);
}
