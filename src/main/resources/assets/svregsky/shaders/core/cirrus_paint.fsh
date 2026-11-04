#version 330

precision highp float;

// SvregSky painterly filter, from a copy of the scene. A cheap Kuwahara: the area around each pixel is split in
// four quarters, each read with four bilinear taps (16 texels), and the calmest quarters win, so flat areas become
// smooth patches of paint while edges stay crisp. On top: brush strokes that follow the edges, a little more
// colour, and soft darker paint where two patches meet. Colour only (no depth), so it works on phones too.
// TextureMat[2] = strength (0..1), time, 0, 0

layout(std140) uniform DynamicTransforms {
    highp mat4 ModelViewMat;
    highp vec4 ColorModulator;
    highp vec3 ModelOffset;
    highp mat4 TextureMat;
};

uniform highp sampler2D Sampler0; // scene colour copy (bilinear)

in highp vec2 uv;

out highp vec4 fragColor;

highp float hash(highp vec2 p) {
    highp vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

highp float vnoise(highp vec2 p) {
    highp vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

void main() {
    highp vec2 size = vec2(textureSize(Sampler0, 0));
    highp vec2 px = 1.0 / size;
    highp vec2 st = gl_FragCoord.xy * px;
    highp float s = TextureMat[2].x;
    highp vec3 base = texture(Sampler0, st).rgb;
    // brush size grows with the screen height and the strength (about 2..5 pixels at 720p)
    highp float R = (1.5 + 3.0 * s) * max(size.y / 720.0, 0.75);

    highp vec3 m[4];
    highp float v[4];
    for (int k = 0; k < 4; k++) {
        highp vec2 dx = vec2((k == 0 || k == 3) ? 1.0 : -1.0, 0.0);
        highp vec2 dy = vec2(0.0, k < 2 ? 1.0 : -1.0);
        highp vec3 sum = vec3(0.0), sq = vec3(0.0);
        for (int j = 0; j < 4; j++) {
            highp float ox = (j == 0 || j == 2) ? 0.5 : 1.0;
            highp float oy = j < 2 ? 0.5 : 1.0;
            // offsets land between texels so each bilinear tap averages a little square
            highp vec3 c = texture(Sampler0, st + (dx * ox + dy * oy) * R * px).rgb;
            sum += c;
            sq += c * c;
        }
        m[k] = sum * 0.25;
        highp vec3 var = max(sq * 0.25 - m[k] * m[k], vec3(0.0));
        v[k] = var.r + var.g + var.b;
    }
    // smooth Kuwahara weights: the calmest quarter wins, but softly (no hard switching, less shimmer)
    highp vec3 paint = vec3(0.0);
    highp float wsum = 0.0;
    for (int k = 0; k < 4; k++) {
        highp float w = 1.0 / (1.0 + v[k] * 900.0);
        w *= w * w;
        paint += m[k] * w;
        wsum += w;
    }
    paint /= wsum;
    // keep a touch of the pixel itself so small bright details (stars, sun glints) survive
    paint = mix(paint, base, 0.12);
    highp vec3 lw = vec3(0.3, 0.59, 0.11);
    paint = mix(paint, base, smoothstep(0.12, 0.35, dot(base, lw) - dot(paint, lw)));

    // the edge between patches: how different the quarters are, and which way the colour changes
    highp float l0 = dot(m[0], vec3(0.3, 0.59, 0.11)), l1 = dot(m[1], vec3(0.3, 0.59, 0.11));
    highp float l2 = dot(m[2], vec3(0.3, 0.59, 0.11)), l3 = dot(m[3], vec3(0.3, 0.59, 0.11));
    highp vec2 grad = vec2((l0 + l3) - (l1 + l2), (l0 + l1) - (l2 + l3));
    highp float edge = length(grad);
    // brush strokes: noise stretched along the edges (across the colour change), straight where it is flat
    highp vec2 dir = edge > 0.02 ? normalize(vec2(-grad.y, grad.x)) : normalize(vec2(1.0, 0.35));
    highp vec2 sp = gl_FragCoord.xy / max(size.y / 720.0, 0.75);
    highp vec2 bp = vec2(dot(sp, dir), dot(sp, vec2(-dir.y, dir.x)));
    highp float stroke = vnoise(bp * vec2(0.09, 0.55)) * 0.65 + vnoise(bp * vec2(0.20, 1.1) + 17.0) * 0.35;
    highp float lum = dot(paint, vec3(0.3, 0.59, 0.11));
    paint *= 1.0 + (stroke - 0.5) * 0.10 * s * (0.4 + 0.6 * smoothstep(0.05, 0.6, lum));
    // a little richer colour, like gouache
    paint = mix(vec3(lum), paint, 1.0 + 0.12 * s);
    // darker paint pooling where two patches meet (watercolour edge)
    paint *= 1.0 - smoothstep(0.06, 0.35, edge) * 0.12 * s;

    fragColor = vec4(clamp(mix(base, paint, 0.35 + 0.65 * s), 0.0, 1.0), 1.0);
}
