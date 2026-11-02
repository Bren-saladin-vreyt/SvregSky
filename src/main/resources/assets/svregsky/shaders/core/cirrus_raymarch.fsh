#version 330

precision highp float;

// SvregSky "Raymarch Ghibli" clouds, drawn into a small (1/2 .. 1/4 screen) buffer:
// a noise volume (coverage map x height profile, eroded by billowy noise) is marched per pixel, the first point inside
// a cloud is toon-lit in four flat tones. Output: rgb = colour, a = encoded distance (0 = no cloud) for the composite pass.
// TextureMat[0] = sun dir, layer height | [1] = day, gold, night, rain | [2] = haze away (xyz), amount | [3] = haze toward the sun (x, z, w)
// ColorModulator = time, reach, unused, org | ModelOffset = camera in cloud space (x, height above the cloud base, z)

layout(std140) uniform DynamicTransforms {
    highp mat4 ModelViewMat;
    highp vec4 ColorModulator;
    highp vec3 ModelOffset;
    highp mat4 TextureMat;
};

layout(std140) uniform Projection {
    highp mat4 ProjMat;
};

uniform highp sampler2D Sampler0; // baked erosion noise atlas (RaymarchNoise): 32 slices of 66 x 66

in highp vec2 uv;

out highp vec4 fragColor;

highp float h3(highp vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }
highp float h2(highp vec2 p) { highp vec3 q = fract(vec3(p.xyx) * 0.1031); q += dot(q, q.yzx + 33.33); return fract((q.x + q.y) * q.z); }
highp float n2(highp vec2 p) {
    highp vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(h2(i), h2(i + vec2(1.0, 0.0)), f.x), mix(h2(i + vec2(0.0, 1.0)), h2(i + vec2(1.0, 1.0)), f.x), f.y);
}
highp float n3(highp vec3 p) {
    highp vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(h3(i), h3(i + vec3(1.0, 0.0, 0.0)), f.x), mix(h3(i + vec3(0.0, 1.0, 0.0)), h3(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
               mix(mix(h3(i + vec3(0.0, 0.0, 1.0)), h3(i + vec3(1.0, 0.0, 1.0)), f.x), mix(h3(i + vec3(0.0, 1.0, 1.0)), h3(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
}
// baked erosion: tile 128 x 64 x 128 blocks, two bilinear reads (neighbouring y slices)
highp float erosionTex(highp vec3 p) {
    highp float fy = fract(p.y / 64.0) * 32.0 - 0.5;
    highp float s0 = floor(fy);
    highp float wy = fy - s0;
    highp float sa = mod(s0, 32.0), sb = mod(s0 + 1.0, 32.0);
    highp vec2 xz = fract(p.xz / 128.0) * 64.0 + 1.0;
    highp float a = texture(Sampler0, vec2((sa * 66.0 + xz.x) / 2112.0, xz.y / 66.0)).r;
    highp float b = texture(Sampler0, vec2((sb * 66.0 + xz.x) / 2112.0, xz.y / 66.0)).r;
    return mix(a, b, wy);
}

highp float layerH, amount;

highp float coverage(highp vec2 xz) {
    highp float c = n2(xz / 230.0) * 0.58 + n2(xz / 80.0 + 7.0) * 0.42;
    return smoothstep(0.66 - 0.1 * amount, 0.86 - 0.1 * amount, c);
}

highp float density(highp vec3 p) {
    highp float cov = coverage(p.xz);
    if (cov <= 0.0) {
        return -1.0;
    }
    highp float top = 30.0 + (layerH - 30.0) * cov;
    highp float hf = p.y / top;
    if (hf < 0.0 || hf > 1.0) {
        return -1.0;
    }
    highp float prof = smoothstep(0.0, 0.16, hf) * (1.0 - smoothstep(0.4, 1.0, hf));
    highp float e = erosionTex(p);
    return cov * prof - (1.0 - e) * (0.42 + 0.25 * (1.0 - hf)) + 0.12;
}

void main() {
    highp vec3 sunDir = normalize(TextureMat[0].xyz);
    layerH = TextureMat[0].w;
    highp float day = TextureMat[1].x, gold = TextureMat[1].y, night = TextureMat[1].z, rain = TextureMat[1].w;
    highp vec3 hazeAway = TextureMat[2].xyz;
    amount = TextureMat[2].w;
    highp vec3 hazeSun = vec3(TextureMat[3].x, TextureMat[3].z, TextureMat[3].w);
    highp float reach = ColorModulator.y;
    highp float org = ColorModulator.w;

    // view ray for this (big) pixel, turned into the world by the camera rotation
    highp vec3 vd = normalize(vec3((uv * 2.0 - 1.0) / vec2(ProjMat[0][0], ProjMat[1][1]), -1.0));
    highp vec3 rd = normalize(transpose(mat3(ModelViewMat)) * vd);
    highp vec3 ro = ModelOffset;

    fragColor = vec4(0.0);
    if (abs(rd.y) < 1e-4) {
        return;
    }
    highp float a = (0.0 - ro.y) / rd.y, b = (layerH - ro.y) / rd.y;
    highp float tA = max(min(a, b), 0.0), tB = min(max(a, b), reach);
    if (tB <= tA) {
        return;
    }
    highp float t = tA;
    highp float jit = h2(gl_FragCoord.xy + 17.0) * 0.5;
    highp float tPrev = t;
    int maxSteps = int(ColorModulator.z);
    for (int i = 0; i < 110; i++) {
        if (t > tB || i >= maxSteps) {
            break;
        }
        highp vec3 p = ro + rd * t;
        highp float d = density(p);
        if (d > 0.0) {
            highp float t0 = tPrev, t1 = t;
            for (int k = 0; k < 4; k++) {
                highp float tm = 0.5 * (t0 + t1);
                if (density(ro + rd * tm) > 0.0) {
                    t1 = tm;
                } else {
                    t0 = tm;
                }
            }
            t = t1;
            p = ro + rd * t;
            // light: cloud toward the sun and above darkens; open sun-facing surfaces are bright
            highp float occl = 0.0;
            for (int k = 1; k <= 3; k++) {
                occl += max(0.0, density(p + sunDir * float(k) * 12.0));
            }
            occl *= 1.33;
            highp float up = max(0.0, density(p + vec3(0.0, 14.0, 0.0))) * 2.0;
            // soft normal from 4 samples (tetrahedron)
            const highp float e = 4.0;
            highp float d1 = density(p + vec3(e, -e, -e)), d2 = density(p + vec3(-e, -e, e));
            highp float d3 = density(p + vec3(-e, e, -e)), d4 = density(p + vec3(e, e, e));
            highp vec3 n = -normalize(vec3(d1 - d2 - d3 + d4, -d1 - d2 + d3 + d4, -d1 + d2 - d3 + d4) + vec3(0.0, 1e-4, 0.0));
            highp float l = dot(n, sunDir) * 0.5 + 0.5;
            highp float v = 0.42 + 0.30 * l + 0.18 * (n.y * 0.5 + 0.5) - 0.18 * occl - 0.18 * up;
            v += (erosionTex(p * 3.0) - 0.5) * 0.06;
            // tones with soft steps between them (painted, not hard-cut)
            highp float k1 = smoothstep(0.28, 0.33, v), k2 = smoothstep(0.44, 0.49, v), k3 = smoothstep(0.60, 0.65, v);
            int band = v > 0.62 ? 3 : (v > 0.46 ? 2 : (v > 0.30 ? 1 : 0));

            // the same four tones as the voxel clouds (day -> golden hour -> night, rain greys)
            highp vec3 c0 = mix(vec3(0.55, 0.74, 0.95), mix(vec3(0.42, 0.30, 0.52), vec3(0.55, 0.50, 0.72), org), gold);
            highp vec3 c1 = mix(vec3(0.70, 0.87, 1.00), mix(vec3(0.58, 0.42, 0.62), vec3(0.72, 0.68, 0.86), org), gold);
            highp vec3 c2 = mix(vec3(0.86, 0.96, 1.00), mix(vec3(0.90, 0.66, 0.60), vec3(0.95, 0.84, 0.84), org), gold);
            highp vec3 c3 = mix(vec3(1.00, 1.00, 1.00), mix(vec3(1.00, 0.86, 0.64), vec3(1.00, 0.95, 0.84), org), gold);
            highp float nk = night * (1.0 - day) * (1.0 - gold);
            c0 = mix(c0, vec3(0.10, 0.11, 0.20), nk);
            c1 = mix(c1, vec3(0.16, 0.18, 0.30), nk);
            c2 = mix(c2, vec3(0.24, 0.27, 0.42), nk);
            c3 = mix(c3, vec3(0.36, 0.40, 0.58), nk);
            highp float dim = 0.30 + 0.70 * max(day, gold);
            c0 = mix(c0, vec3(0.40, 0.43, 0.49) * dim, rain * 0.8);
            c1 = mix(c1, vec3(0.52, 0.55, 0.61) * dim, rain * 0.8);
            c2 = mix(c2, vec3(0.62, 0.65, 0.71) * dim, rain * 0.8);
            c3 = mix(c3, vec3(0.70, 0.73, 0.78) * dim, rain * 0.8);
            highp vec3 col = mix(mix(mix(c0, c1, k1), c2, k2), c3, k3);
            col = mix(col, col * 0.6 + hazeAway * 0.4, float(3 - band) / 3.0 * 0.3 * (1.0 - rain));
            // distance haze into the horizon colour
            highp vec2 sxz = sunDir.xz / (length(sunDir.xz) + 1e-4);
            highp vec2 dxz = rd.xz / (length(rd.xz) + 1e-4);
            highp vec3 haze = mix(hazeAway, hazeSun, pow(clamp(0.5 + 0.5 * dot(dxz, sxz), 0.0, 1.0), 1.4));
            col = mix(col, haze, smoothstep(0.3, 1.0, t / reach) * 0.85);
            // distance, log-encoded into alpha (always > 0 for a hit)
            highp float enc = 1.0 - log2(1.0 + t) / log2(1.0 + 4096.0);
            fragColor = vec4(col, max(2.0 / 255.0, enc));
            return;
        }
        tPrev = t;
        // big strides through empty sky (no coverage), small ones near cloud
        t += max(2.5, (d < -0.5 ? 16.0 : 3.0) * (1.0 + t / 600.0)) * (0.75 + jit);
    }
}
