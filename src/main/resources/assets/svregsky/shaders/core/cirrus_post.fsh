#version 330

precision highp float;

// SvregSky screen look: thin cartoon outlines from depth edges, a time-of-day film tint and a soft vignette.
// TextureMat[0] = tint rgb, tint amount | [1] = vignette, outline strength, depth fade start, 0

layout(std140) uniform DynamicTransforms {
    highp mat4 ModelViewMat;
    highp vec4 ColorModulator;
    highp vec3 ModelOffset;
    highp mat4 TextureMat;
};

layout(std140) uniform Projection {
    highp mat4 ProjMat;
};

uniform highp sampler2D Sampler0; // main depth
// ColorModulator = sun direction in view space, screen-space shadow strength

in highp vec2 uv;

out highp vec4 fragColor;


// Desktop GL uses glClipControl(zero-to-one) with reverse depth, so scene depth (0..0.5) is NDC z directly.
// Phones (GLES / MobileGlues) usually lack clip control: depth is then (ndc + 1) / 2 and always > 0.5.
highp float ndcZ(highp float d) {
    return d > 0.5 ? d * 2.0 - 1.0 : d;
}

highp float depthAt(ivec2 p, ivec2 size) {
    return ndcZ(texelFetch(Sampler0, clamp(p, ivec2(0), size - 1), 0).r);
}

highp vec3 viewPos(highp vec2 uvp, highp float d, highp mat4 inv) {
    highp vec4 v = inv * vec4(uvp * 2.0 - 1.0, ndcZ(d), 1.0);
    return v.xyz / v.w;
}

void main() {
    highp vec3 tint = TextureMat[0].xyz;
    highp float tintA = TextureMat[0].w;
    highp float vig = TextureMat[1].x;
    highp float outline = TextureMat[1].y;

    highp vec3 col = tint;
    highp float a = tintA;
    // vignette: darker, slightly blue corners
    highp vec2 d = uv - 0.5;
    highp float v = smoothstep(0.35, 0.95, length(d * vec2(1.25, 1.0)) * 1.25) * vig;
    col = mix(col, vec3(0.06, 0.05, 0.10), v / max(a + v, 1e-4));
    a = 1.0 - (1.0 - a) * (1.0 - v);

    if (outline > 0.0) {
        ivec2 size = textureSize(Sampler0, 0);
        ivec2 p = ivec2(gl_FragCoord.xy);
        highp float c = depthAt(p, size);
        highp float n1 = depthAt(p + ivec2(1, 0), size), n2 = depthAt(p - ivec2(1, 0), size);
        highp float n3 = depthAt(p + ivec2(0, 1), size), n4 = depthAt(p - ivec2(0, 1), size);
        highp float mx = max(max(n1, n2), max(n3, n4)), mn = min(min(n1, n2), min(n3, n4));
        // reverse depth (1 near, 0 far): relative jumps in depth are relative jumps in distance
        highp float rel = (max(mx, c) - min(mn, c)) / max(max(mx, c), 1e-6);
        highp float edge = smoothstep(0.18, 0.35, rel) * smoothstep(0.0, TextureMat[1].z, max(mx, c));
        highp float oa = edge * outline;
        col = mix(col, vec3(0.10, 0.08, 0.14), oa / max(a + oa, 1e-4));
        a = 1.0 - (1.0 - a) * (1.0 - oa);
    }
    highp float rainK = TextureMat[3].y;
    if (rainK > 0.0) {
        // raindrops on the lens: round drops appear, slide a little and fade; a pale rim and a bright highlight
        highp float sz = 72.0;
        highp vec2 fc = gl_FragCoord.xy;
        highp vec2 cell = floor(fc / sz);
        highp vec2 fl = fc - cell * sz;
        highp float h0 = fract(sin(dot(cell, vec2(12.9898, 78.233))) * 43758.5453);
        if (h0 > 1.0 - rainK * 0.6) {
            highp float period = 2.5 + 4.0 * fract(h0 * 13.7);
            highp float ph = fract(TextureMat[3].z / period + h0 * 7.0);
            highp vec2 ctr = (vec2(fract(h0 * 31.1), fract(h0 * 57.3)) * 0.6 + 0.2) * sz;
            ctr.y -= ph * ph * 14.0;
            highp float r = 5.0 + 8.0 * fract(h0 * 91.7);
            highp vec2 dv = (fl - ctr) / r;
            highp float d = length(dv * vec2(1.0, 0.85));
            highp float fade = smoothstep(0.0, 0.06, ph) * (1.0 - smoothstep(0.45, 0.62, ph));
            if (d < 1.0 && fade > 0.0) {
                highp float ring = smoothstep(0.55, 0.95, d) * (1.0 - smoothstep(0.95, 1.0, d));
                highp float spec = 1.0 - smoothstep(0.0, 0.32, length(dv - vec2(-0.35, 0.38)));
                highp float da = (0.16 + 0.45 * ring + 0.75 * spec) * fade;
                highp vec3 dc = mix(vec3(0.62, 0.70, 0.82), vec3(1.0), spec);
                col = mix(col, dc, da / max(a + da, 1e-4));
                a = 1.0 - (1.0 - a) * (1.0 - da);
            }
        }
    }
    highp float paper = TextureMat[1].w;
    if (paper > 0.0) {
        // watercolour paper: soft fibrous grain and faint blotches that stay put on screen
        highp vec2 q = gl_FragCoord.xy;
        highp float grain = fract(sin(dot(floor(q * 0.5), vec2(12.9898, 78.233))) * 43758.5453);
        highp vec2 b = q / 90.0;
        highp vec2 i = floor(b), f = fract(b);
        f = f * f * (3.0 - 2.0 * f);
        highp float h00 = fract(sin(dot(i, vec2(127.1, 311.7))) * 43758.5453), h10 = fract(sin(dot(i + vec2(1, 0), vec2(127.1, 311.7))) * 43758.5453);
        highp float h01 = fract(sin(dot(i + vec2(0, 1), vec2(127.1, 311.7))) * 43758.5453), h11 = fract(sin(dot(i + vec2(1, 1), vec2(127.1, 311.7))) * 43758.5453);
        highp float blot = mix(mix(h00, h10, f.x), mix(h01, h11, f.x), f.y);
        highp float pa = paper * (0.035 * grain + 0.05 * blot);
        col = mix(col, vec3(0.42, 0.36, 0.30), pa / max(a + pa, 1e-4));
        a = 1.0 - (1.0 - a) * (1.0 - pa);
    }
    if (a < 0.002) {
        discard;
    }
    fragColor = vec4(col, a);
}
