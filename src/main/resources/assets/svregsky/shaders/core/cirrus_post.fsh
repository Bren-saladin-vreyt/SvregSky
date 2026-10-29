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

// screen-space sun shadows: march from each pixel toward the sun and see if the depth buffer has something in front
highp float sunShadow(highp vec2 uvp, ivec2 size) {
    highp float d = texture(Sampler0, uvp).r;
    if (d <= 0.00001 || d >= 0.99999 || abs(d - 0.5) < 0.000002) {
        return 0.0;  // sky (cleared depth is 0, 1 or 0.5 depending on the depth convention)
    }
    highp mat4 inv = inverse(ProjMat);
    highp vec3 p = viewPos(uvp, d, inv);
    highp vec3 L = normalize(ColorModulator.xyz);
    highp float dist = length(p);
    highp float range = TextureMat[2].y;
    if (dist > range) {
        return 0.0;
    }
    highp float jitter = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    highp float occ = 0.0;
    highp float stepLen = 0.12 + dist * 0.004;
    highp float t = stepLen * (0.5 + jitter);
    int steps = int(TextureMat[2].x + 0.5);
    for (int i = 0; i < 32; i++) {
        if (i >= steps) {
            break;
        }
        highp vec3 q = p + L * t;
        highp vec4 c = ProjMat * vec4(q, 1.0);
        highp vec3 n = c.xyz / c.w;
        highp vec2 suv = n.xy * 0.5 + 0.5;
        if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) {
            break;
        }
        highp float sd = texture(Sampler0, suv).r;
        highp vec3 sp = viewPos(suv, sd, inv);
        // something in front of the ray point (closer to the camera), but not far in front (that would be a separate object)
        highp float gap = q.z - sp.z;            // view space looks down -z: closer = larger z
        if (gap < -0.03 && gap > -(5.0 + dist * 0.06) && sd > 0.00001) {
            occ = 1.0;
            break;
        }
        t += stepLen;
        stepLen *= TextureMat[2].z;
    }
    // fade out with distance and at the screen edges
    highp vec2 e = min(uvp, 1.0 - uvp);
    return occ * (1.0 - smoothstep(range * 0.5, range, dist)) * smoothstep(0.0, 0.03, min(e.x, e.y));
}

void main() {
    highp vec3 tint = TextureMat[0].xyz;
    highp float tintA = TextureMat[0].w;
    highp float vig = TextureMat[1].x;
    highp float outline = TextureMat[1].y;

    if (TextureMat[3].x > 0.5) {
        // shadow check: red = distance (dark near, bright far), green = phone depth mode, blue = shadowed, grey = sky / no depth
        highp float d = texture(Sampler0, uv).r;
        if (d <= 0.00001 || d >= 0.99999) {
            fragColor = vec4(0.5, 0.5, 0.5, 0.85);
            return;
        }
        highp vec3 p = viewPos(uv, d, inverse(ProjMat));
        highp float occ = sunShadow(uv, textureSize(Sampler0, 0));
        fragColor = vec4(clamp(length(p) / 64.0, 0.0, 1.0), d > 0.5 ? 1.0 : 0.0, occ > 0.0 ? 1.0 : 0.0, 0.85);
        return;
    }
    highp vec3 col = tint;
    highp float a = tintA;
    if (ColorModulator.w > 0.0) {
        ivec2 sz = textureSize(Sampler0, 0);
        highp float sh = sunShadow(uv, sz) * ColorModulator.w;
        // anime shadow: cool blue-violet, laid under the tint
        highp float ci = TextureMat[2].w;
        highp vec3 shc = ci < 0.5 ? vec3(0.20, 0.22, 0.42) : ci < 1.5 ? vec3(0.08, 0.08, 0.10) : vec3(0.30, 0.17, 0.10);
        col = mix(col, shc, sh / max(a + sh, 1e-4));
        a = 1.0 - (1.0 - a) * (1.0 - sh);
    }

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
