#version 330

precision highp float;

// SvregSky "Raymarch Ghibli": upscales the small cloud buffer with sharp pixels and writes each cloud pixel's depth,
// so terrain, roofs and trees in front still hide the clouds (hardware depth test, which also works on phones).
// ColorModulator = buffer scale (screen pixels per cloud pixel; negative = sharp pixel upscale, positive = smooth edges), depth zero-to-one (1) or minus-one-to-one (0),
// aerial fade strength (z, 0 = off), cloud reach (w): far low clouds are drawn see-through so the sky shows behind them

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

// smooth upscale: the cloud/sky mask is blended between the four nearest cloud pixels and cut at one half, so
// edges come out as round curves instead of pixel stairs; colour blends only between cloud pixels
highp vec4 cloudAt(ivec2 q, ivec2 size) {
    return texelFetch(Sampler0, clamp(q, ivec2(0), size - 1), 0);
}

void main() {
    highp float scale = abs(ColorModulator.x);
    highp vec4 c;
    highp float cover = 1.0;
    if (ColorModulator.x > 0.0) {
        ivec2 size = textureSize(Sampler0, 0);
        highp vec2 p = gl_FragCoord.xy / scale - 0.5;
        ivec2 i = ivec2(floor(p));
        highp vec2 f = p - floor(p);
        highp vec4 c00 = cloudAt(i, size), c10 = cloudAt(i + ivec2(1, 0), size), c01 = cloudAt(i + ivec2(0, 1), size), c11 = cloudAt(i + ivec2(1, 1), size);
        highp float th = 1.0 / 255.0;
        highp float w00 = (1.0 - f.x) * (1.0 - f.y) * step(th, c00.a), w10 = f.x * (1.0 - f.y) * step(th, c10.a);
        highp float w01 = (1.0 - f.x) * f.y * step(th, c01.a), w11 = f.x * f.y * step(th, c11.a);
        highp float m = w00 + w10 + w01 + w11;
        if (m < 0.35) {
            discard;
        }
        cover = clamp((m - 0.5) / max(fwidth(m), 1e-3) + 0.5, 0.0, 1.0);
        c.rgb = (c00.rgb * w00 + c10.rgb * w10 + c01.rgb * w01 + c11.rgb * w11) / m;
        // depth: the nearest cloud of the four (largest stored value = closest)
        c.a = max(max(c00.a * step(th, c00.a), c10.a * step(th, c10.a)), max(c01.a * step(th, c01.a), c11.a * step(th, c11.a)));
    } else {
        c = texelFetch(Sampler0, ivec2(gl_FragCoord.xy / scale), 0);
        if (c.a < 1.0 / 255.0) {
            discard;
        }
    }
    highp float t = exp2((1.0 - c.a) * log2(1.0 + 4096.0)) - 1.0;
    highp vec3 vd = normalize(vec3((uv * 2.0 - 1.0) / vec2(ProjMat[0][0], ProjMat[1][1]), -1.0));
    highp vec4 clip = ProjMat * vec4(vd * t, 1.0);
    highp float ndc = clip.z / clip.w;
    gl_FragDepth = clamp(ColorModulator.y > 0.5 ? ndc : ndc * 0.5 + 0.5, 0.0, 1.0);
    highp float a = 1.0;
    if (ColorModulator.z > 0.0) {
        highp vec3 rd = transpose(mat3(ModelViewMat)) * vd;
        highp float low = 1.0 - smoothstep(0.02, 0.30, rd.y);
        // the horizon bank (stored at ~4000 blocks) gets a light veil; the clouds overhead fade with distance
        highp float aerA = (t > 3500.0 ? 0.10 : smoothstep(0.30, 1.0, t / ColorModulator.w) * 0.55) * (0.5 + 0.5 * low);
        a = 1.0 - min(0.85, aerA * ColorModulator.z);
    }
    fragColor = vec4(c.rgb, a * cover);
}
