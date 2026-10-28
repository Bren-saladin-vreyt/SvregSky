#version 330

precision highp float;

// SvregSky "Cirrus" sky: Ghibli gradient, sun glow + rays, stars, milky way, aurora, shooting stars (mode 0)
// and the painted pixel-cumulus cloud layers (mode 1, drawn after the sun and moon so clouds cover them).
// Every parameter comes in through TextureMat / ColorModulator / ModelOffset (see CirrusSky.java).

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0; // baked cloud density: R = density, G/B = 16-bit cluster shape
uniform sampler2D Sampler1; // baked cumulonimbus sprites (8 x 320x256): R = light band 0..4, G = rim, B = height, A = 255 cloud / 110 rain

in vec3 skyDir;

out vec4 fragColor;

const int F_GRADIENT = 1;
const int F_CLOUDS = 2;
const int F_STARS = 4;
const int F_MILKYWAY = 8;
const int F_AURORA = 16;
const int F_SHOOTING = 32;
const int F_RAYS = 64;
const int F_GODRAYS = 128;
const int F_MOUNTAINS = 256;
const int F_TOWERS = 512;

// ---------------------------------------------------------------- noise
float hash2(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hash3(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = hash2(i);
    float b = hash2(i + vec2(1.0, 0.0));
    float c = hash2(i + vec2(0.0, 1.0));
    float d = hash2(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm3(vec2 p) {
    const mat2 rot = mat2(0.80, -0.60, 0.60, 0.80);
    float v = 0.55 * noise(p);
    p = rot * p * 2.05 + vec2(5.7, 3.2);
    v += 0.30 * noise(p);
    p = rot * p * 2.10 + vec2(11.3, 7.9);
    v += 0.15 * noise(p);
    return v;
}

float sstep(float e0, float e1, float x) {
    return smoothstep(e0, e1, x);
}

// ---------------------------------------------------------------- background gradient
vec3 gradient(float dy, vec3 horizon, vec3 mid, vec3 zenith, vec3 below) {
    float h = clamp(dy, 0.0, 1.0);
    vec3 col = mix(mix(horizon, mid, sstep(0.0, 0.22, h)), zenith, sstep(0.18, 0.75, h));
    vec3 under = mix(horizon, below, 1.0 - sstep(-0.25, 0.0, dy));
    return dy < 0.0 ? under : col;
}

vec3 skyDay(float dy) {
    return gradient(dy, vec3(0.80, 0.96, 0.99), vec3(0.30, 0.75, 0.98), vec3(0.12, 0.58, 0.97), vec3(0.45, 0.70, 0.85));
}

vec3 skyNight(float dy) {
    return gradient(dy, vec3(0.22, 0.20, 0.66), vec3(0.10, 0.12, 0.46), vec3(0.03, 0.06, 0.22), vec3(0.05, 0.06, 0.14));
}

// magenta -> yellow sunset (rise = 0) or a softer, pinker sunrise (rise = 1)
// warm amber golden hour: the whole sky goes orange, yellow at the horizon by the sun
vec3 skyOrange(vec3 dir, float h, float side) {
    vec3 sg = mix(vec3(1.00, 0.86, 0.42), vec3(0.98, 0.70, 0.16), sstep(0.0, 0.12, h));
    sg = mix(sg, vec3(0.90, 0.55, 0.03), sstep(0.10, 0.40, h));
    sg = mix(sg, vec3(0.84, 0.47, 0.02), sstep(0.40, 0.95, h));
    vec3 ag = mix(vec3(0.95, 0.62, 0.14), vec3(0.88, 0.51, 0.04), sstep(0.0, 0.30, h));
    ag = mix(ag, vec3(0.80, 0.44, 0.04), sstep(0.30, 0.95, h));
    return mix(ag, sg, side);
}

vec3 skyEvent(vec3 dir, vec3 sunDir, float rise, float org) {
    float dy = dir.y;
    float h = clamp(dy, 0.0, 1.0);
    vec2 dxz = normalize(dir.xz + vec2(1e-5));
    vec2 sxz = normalize(sunDir.xz + vec2(1e-5));
    float side = pow(clamp(0.5 + 0.5 * dot(dxz, sxz), 0.0, 1.0), 1.4);

    vec3 hz = mix(vec3(0.97, 0.55, 0.16), vec3(1.00, 0.68, 0.32), rise);
    vec3 og = mix(vec3(0.86, 0.32, 0.28), vec3(0.94, 0.46, 0.40), rise);
    vec3 mg = mix(vec3(0.64, 0.18, 0.50), vec3(0.76, 0.32, 0.58), rise);
    const vec3 vi = vec3(0.55, 0.17, 0.58);
    const vec3 ze = vec3(0.45, 0.16, 0.56);

    vec3 sg = mix(hz, og, sstep(0.0, 0.10, h));
    sg = mix(sg, mg, sstep(0.08, 0.30, h));
    sg = mix(sg, vi, sstep(0.28, 0.60, h));
    sg = mix(sg, ze, sstep(0.55, 0.95, h));

    vec3 ag = mix(vec3(0.78, 0.34, 0.46), vec3(0.52, 0.18, 0.56), sstep(0.0, 0.25, h));
    ag = mix(ag, ze, sstep(0.25, 0.85, h));
    vec3 col = mix(ag, sg, side);
    col = mix(col, skyOrange(dir, h, side), org);

    vec3 under = mix(mix(vec3(0.55, 0.32, 0.48), vec3(0.95, 0.55, 0.35), side), vec3(0.20, 0.15, 0.28), 1.0 - sstep(-0.25, 0.0, dy));
    return dy < 0.0 ? under : col;
}

// ---------------------------------------------------------------- night sky
float softStar(vec3 p, float scale, float thr, float tm, out float seed) {
    vec3 sp = p * scale;
    vec3 id = floor(sp);
    vec3 f = fract(sp) - 0.5;
    float hs = hash3(id);
    seed = hs;
    if (hs < thr) {
        return 0.0;
    }
    vec3 offset = vec3(fract(hs * 13.7), fract(hs * 29.3), fract(hs * 53.1)) - 0.5;
    float d = length(f - offset * 0.35);
    float core = exp(-d * d * 38.0);
    float halo = exp(-d * d * 10.0) * 0.35;
    float twinkle = 0.65 + 0.35 * sin(tm * (1.4 + fract(hs * 19.0) * 1.5) + hs * 300.0);
    return (core + halo) * (1.0 - sstep(0.08, 0.45, d)) * twinkle;
}

vec4 auroraCurtain(float u, float h, float tm, float seed, float baseH, float amp) {
    float wob = noise(vec2(u * 2.2 + tm * 0.020, seed));
    float wob2 = noise(vec2(u * 5.5 - tm * 0.035, seed + 4.0));
    float c = baseH + amp * (wob - 0.5) * 2.0 + 0.03 * (wob2 - 0.5);
    float rel = h - c;
    if (rel < -0.01) {
        return vec4(0.0);
    }
    float body = sstep(0.0, 0.012, rel) * exp(-max(rel, 0.0) * 6.0);
    float rays = 0.40 + 0.60 * sstep(0.25, 0.85, noise(vec2(u * 48.0 + wob * 6.0, tm * 0.09 + seed)));
    float fold = sstep(0.30, 0.70, noise(vec2(u * 1.5 + tm * 0.010, seed + 11.0)));
    float hh = clamp(rel * 4.5, 0.0, 1.0);
    vec3 ac = mix(mix(vec3(0.15, 1.00, 0.55), vec3(0.10, 0.80, 0.95), sstep(0.0, 0.45, hh)), vec3(0.68, 0.30, 0.95), sstep(0.45, 1.0, hh));
    return vec4(ac, body * rays * fold);
}

vec3 nightSky(vec3 col, vec3 dir, vec3 sunDir, float vis, float visLow, float tm, int flags, float auroraStrength, float milkyStrength, float shootPeriod) {
    // celestial frame: turns with the sun, so stars and the milky way rise and set
    float sl = max(length(sunDir.xy), 0.001);
    float cs = sunDir.x / sl;
    float sn = sunDir.y / sl;
    vec3 cel = vec3(dir.x * cs + dir.y * sn, -dir.x * sn + dir.y * cs, dir.z);
    float mwMask = 0.0;

    if ((flags & F_MILKYWAY) != 0 && milkyStrength > 0.0) {
        const vec3 gN = vec3(0.1527, 0.5599, 0.8144);
        const vec3 gE1 = vec3(0.9649, -0.2631, 0.0);
        const vec3 gE2 = vec3(0.2143, 0.7858, -0.5804);
        float bd = dot(cel, gN);
        float mw = exp(-(bd * bd) / 0.03);
        mwMask = mw;
        if (mw > 0.002) {
            float lonA = atan(dot(cel, gE2), dot(cel, gE1));
            vec2 mp = vec2(lonA * 3.0, bd * 9.0);
            float dust1 = fbm3(mp * 1.8 + 3.1);
            float dust2 = fbm3(mp * 3.8 + 11.7);
            float dust3 = noise(mp * 11.0 + 5.0);
            float coreW = 0.45 + 0.85 * sstep(-0.3, 0.9, dot(cel, gE1));
            float core = exp(-(bd * bd) / 0.006) * coreW;
            float glow = (0.28 + 0.90 * dust1) * (mw * 0.65 + core * 1.10) * (0.75 + 0.50 * dust3);
            float riftC = bd + (dust2 - 0.5) * 0.10;
            glow *= 1.0 - 0.75 * exp(-(riftC * riftC) / 0.0016) * sstep(0.25, 0.70, dust1 + 0.2);
            vec3 mwCol = mix(mix(vec3(0.30, 0.40, 0.85), vec3(0.52, 0.50, 0.92), sstep(0.08, 0.40, glow)), vec3(0.80, 0.72, 0.92), sstep(0.60, 1.30, glow * coreW));
            col = mix(col, mwCol, clamp(glow * 0.28, 0.0, 0.40) * vis * milkyStrength);
        }
    }

    if ((flags & F_STARS) != 0) {
        float hs1, hs2;
        float small = softStar(cel, 240.0, 0.988 - 0.025 * mwMask, tm, hs1);
        float big = softStar(cel + vec3(0.37, 0.71, 0.19), 95.0, 0.994, tm * 0.8, hs2);
        vec3 c1 = mix(vec3(0.75, 0.88, 1.00), vec3(1.00, 0.92, 0.80), fract(hs1 * 91.7));
        vec3 c2 = mix(vec3(0.85, 0.92, 1.00), vec3(1.00, 0.85, 0.70), fract(hs2 * 47.3));
        col += (c1 * small * 1.05 + c2 * big * 1.70) * vis;
    }

    if ((flags & F_AURORA) != 0 && auroraStrength > 0.0) {
        vec2 dxz = normalize(dir.xz + vec2(1e-4));
        const vec2 aDir = vec2(0.34, -0.94); // toward north
        float along = dot(dxz, aDir);
        if (along > -0.6) {
            float u = atan(dot(dxz, vec2(-aDir.y, aDir.x)), along);
            float sector = sstep(-0.6, 0.15, along);
            vec4 a1 = auroraCurtain(u, dir.y, tm, 1.0, 0.17, 0.07);
            vec4 a2 = auroraCurtain(u * 1.3 + 2.0, dir.y, tm, 7.0, 0.31, 0.06);
            col += (a1.rgb * a1.a + a2.rgb * a2.a * 0.55) * 0.80 * auroraStrength * sector * visLow;
        }
    }

    if ((flags & F_SHOOTING) != 0 && shootPeriod > 0.5) {
        float tt = tm / shootPeriod;
        float sid = floor(tt);
        float lt = fract(tt) * shootPeriod;
        if (lt < 1.1 && hash2(vec2(sid, 1.7)) < 0.75) {
            float azs = hash2(vec2(sid, 5.3)) * 6.2831853;
            float els = 0.35 + 0.55 * hash2(vec2(sid, 9.1));
            float rr = sqrt(1.0 - els * els);
            vec3 sa = vec3(cos(azs) * rr, els, sin(azs) * rr);
            vec3 st0 = normalize(cross(sa, vec3(0.0, 1.0, 0.0)));
            vec3 st1 = cross(sa, st0);
            float sang = hash2(vec2(sid, 13.7)) * 6.2831853;
            vec3 sv = st0 * cos(sang) + st1 * sin(sang);
            vec3 snrm = cross(sa, sv);
            float prog = lt / 1.1;
            float sHead = prog * 0.55;
            float sTail = max(sHead - 0.16 * (0.4 + 0.6 * sin(prog * 3.14159)), 0.0);
            float env = sstep(0.0, 0.12, prog) * (1.0 - sstep(0.70, 1.0, prog));
            float off = abs(dot(dir, snrm));
            if (off < 0.012) {
                float sdAng = atan(dot(dir, sv), dot(dir, sa));
                float alongS = (sdAng - sTail) / max(sHead - sTail, 0.0001);
                if (alongS > 0.0 && alongS < 1.0) {
                    float wS = mix(0.0006, 0.0026, alongS);
                    float lineS = 1.0 - sstep(0.0, wS, off);
                    col += vec3(0.85, 0.93, 1.0) * lineS * pow(alongS, 2.2) * env * 2.2 * vis;
                }
            }
            vec3 headP = sa * cos(sHead) + sv * sin(sHead);
            float hd = length(dir - headP);
            col += vec3(0.90, 0.95, 1.0) * exp(-hd * hd * 9000.0) * env * 1.6 * vis;
        }
    }
    return col;
}

// ---------------------------------------------------------------- painted pixel cumulus
const float PX = 62.0;   // sky pixels per projection unit
const float COV = 0.545; // density threshold of the near layer (~30% of the sky)

vec2 decode(vec4 t) {
    return vec2(t.r, t.g * (255.0 * 256.0 / 65535.0) + t.b * (255.0 / 65535.0));
}

// density (x) and cluster shape (y) at cloud-plane position q; one texture tile = 40 plane units, wraps around
vec2 cloudSample(vec2 q) {
    ivec2 size = textureSize(Sampler0, 0);
    vec2 fs = vec2(size);
    vec2 t = mod(q / 40.0 * fs - 0.5, fs);
    vec2 i = floor(t);
    vec2 f = t - i;
    ivec2 i0 = min(ivec2(i), size - 1);
    ivec2 i1 = i0 + 1;
    i1 -= size * ivec2(greaterThanEqual(i1, size));
    vec2 va = decode(texelFetch(Sampler0, i0, 0));
    vec2 vb = decode(texelFetch(Sampler0, ivec2(i1.x, i0.y), 0));
    vec2 vc = decode(texelFetch(Sampler0, ivec2(i0.x, i1.y), 0));
    vec2 vd = decode(texelFetch(Sampler0, i1, 0));
    return mix(mix(va, vb, f.x), mix(vc, vd, f.x), f.y);
}

struct Palette {
    vec3 t0;
    vec3 t1;
    vec3 t2;
    vec3 t3;
    vec3 haze;
};

vec4 cloudLayer(vec2 q, vec2 ldir, float thr, Palette p, float hz, float am) {
    vec2 s = cloudSample(q);
    if (s.x < thr) {
        return vec4(0.0);
    }
    float cl2 = cloudSample(q + ldir * 0.45).y;
    float sun = clamp((s.y - cl2) * 14.0 + 0.55, 0.0, 1.0);
    float thick = clamp((s.x - thr) * 5.0, 0.0, 1.0);
    float l = clamp(0.55 * sun + 0.45 * (1.0 - thick * 0.9), 0.0, 1.0);
    l = floor(l * 3.999) / 3.0;
    vec3 c = p.t0;
    c = mix(c, p.t1, step(0.30, l));
    c = mix(c, p.t2, step(0.60, l));
    c = mix(c, p.t3, step(0.95, l));
    return vec4(mix(c, p.haze, hz), am);
}

vec4 clouds(vec3 dir, vec3 sunDir, float day, float gold, float org, float rain, vec2 off, float coverAdj, int layers) {
    if (dir.y < 0.03) {
        return vec4(0.0);
    }
    // palettes: day / golden hour / night, then rain greys them out
    vec3 lit = mix(vec3(0.36, 0.38, 0.55), vec3(1.00, 1.00, 0.98), day);
    vec3 mid = mix(vec3(0.20, 0.21, 0.34), vec3(0.84, 0.92, 0.99), day);
    vec3 shade = mix(vec3(0.10, 0.10, 0.19), vec3(0.62, 0.76, 0.93), day);
    vec3 haze = mix(vec3(0.14, 0.16, 0.44), vec3(0.48, 0.80, 0.97), day);
    // golden hour: rose clouds over the magenta sky, or periwinkle-white clouds over the orange sky
    vec3 gLit = mix(vec3(1.00, 0.88, 0.72), vec3(0.88, 0.91, 1.00), org);
    vec3 gMid = mix(vec3(0.84, 0.66, 0.74), vec3(0.72, 0.80, 1.00), org);
    vec3 gShade = mix(vec3(0.44, 0.34, 0.56), vec3(0.56, 0.62, 0.92), org);
    vec3 gHaze = mix(vec3(0.86, 0.40, 0.46), vec3(0.96, 0.64, 0.20), org);
    lit = mix(lit, gLit, gold);
    mid = mix(mid, gMid, gold);
    shade = mix(shade, gShade, gold);
    haze = mix(haze, gHaze, gold);
    float dim = 0.30 + 0.70 * day;
    lit = mix(lit, vec3(0.66, 0.69, 0.74) * dim, rain * 0.8);
    mid = mix(mid, vec3(0.52, 0.55, 0.61) * dim, rain * 0.8);
    shade = mix(shade, vec3(0.36, 0.39, 0.45) * dim, rain * 0.8);
    haze = mix(haze, vec3(0.50, 0.54, 0.60) * dim, rain * 0.8);

    Palette p;
    p.t0 = shade * 0.9;
    p.t1 = shade * 0.4 + mid * 0.6;
    p.t2 = mid * 0.45 + lit * 0.55;
    p.t3 = lit;
    p.haze = haze;

    vec2 p0 = dir.xz / (max(dir.y, 0.07) + 0.085);
    vec2 q0 = (floor(p0 * PX) + 0.5) / PX;
    // light from the zenith (toward the middle of the sky); at golden hour it swings toward the sun
    vec2 ldir = -q0 / (length(q0) + 1e-4);
    vec2 sxz = sunDir.xz / (length(sunDir.xz) + 1e-4);
    ldir = normalize(mix(ldir, sxz, gold * 0.6) + vec2(1e-4));

    float cov = COV - coverAdj - 0.12 * rain;
    bool skipNear = layers < 0;
    layers = abs(layers);
    vec4 k = skipNear ? vec4(0.0) : cloudLayer(q0 * 3.0 + off, ldir, cov, p, 0.0, 1.0);
    if (k.a < 0.5 && layers >= 2) {
        const mat2 r1 = mat2(0.80, -0.60, 0.60, 0.80);
        k = cloudLayer(r1 * (q0 * 5.6) + vec2(37.1, 12.7) + off, r1 * ldir, cov + 0.075, p, 0.28, 0.95);
    }
    if (k.a < 0.5 && layers >= 3) {
        const mat2 r2 = mat2(0.28, 0.96, -0.96, 0.28);
        k = cloudLayer(r2 * (q0 * 9.5) + vec2(81.3, 55.9) + off, r2 * ldir, cov + 0.105, p, 0.52, 0.90);
    }
    float hf = (1.0 - sstep(0.0, 0.35, dir.y)) * 0.35;
    k.rgb = mix(k.rgb, haze, hf);
    k.a *= sstep(0.03, 0.24, dir.y) * mix(0.75, 1.0, max(day, gold));
    return k;
}

// ---------------------------------------------------------------- light shafts through gaps in the painted clouds
float shafts(vec3 dir, vec3 sunDir, vec2 off, float cov) {
    vec2 p0 = dir.xz / (max(dir.y, 0.07) + 0.085);
    vec2 ps = sunDir.xz / (max(sunDir.y, 0.07) + 0.085);
    float open = 0.0;
    for (int i = 1; i <= 8; i++) {
        vec2 q = mix(p0, ps, float(i) / 9.0);
        open += step(cloudSample(q * 3.0 + off).x, cov);
    }
    return open / 8.0;
}

// ---------------------------------------------------------------- rainbow (after rain): pastel arc 40-42 degrees around the antisolar point
// ---------------------------------------------------------------- painted horizon: far mountains, hills, a forest line
float ridge(float a, float freq, float seed, float sharp) {
    // periodic 1D fbm around the horizon (a = azimuth in radians)
    float v = 0.0, amp = 0.55, f = freq;
    for (int i = 0; i < 4; i++) {
        float x = a * f + seed * 7.13;
        float n = noise(vec2(cos(a) * f, sin(a) * f) * 0.5 + vec2(seed * 3.1, float(i) * 5.7));
        v += amp * mix(n, 1.0 - abs(2.0 * n - 1.0), sharp);
        amp *= 0.5;
        f *= 2.1;
    }
    return v;
}

vec3 landHeights(float a) {
    float h1 = 0.02 + 0.24 * pow(ridge(a, 2.0, 1.0, 0.85), 1.7);           // far mountains
    float h2 = 0.012 + 0.10 * pow(ridge(a + 1.7, 3.5, 2.0, 0.35), 1.3);    // rolling hills
    float trees = noise(vec2(a * 140.0, 3.0)) * 0.009 + step(0.62, noise(vec2(a * 55.0, 9.0))) * 0.007;
    float h3 = 0.006 + 0.03 * ridge(a + 4.1, 6.0, 3.0, 0.1) + trees;       // forest line
    return vec3(h1, h2, h3);
}

vec3 horizonLand(vec3 col, vec3 dir, vec3 sunDir, float day, float gold, float night, float org, float rain) {
    float a = atan(dir.z, dir.x);
    float y = dir.y;
    if (y > 0.26) {
        return col;
    }
    vec3 hs = landHeights(a);
    float h1 = hs.x, h2 = hs.y, h3 = hs.z;
    vec2 dxz = normalize(dir.xz + vec2(1e-5));
    vec2 sxz = normalize(sunDir.xz + vec2(1e-5));
    float side = pow(clamp(0.5 + 0.5 * dot(dxz, sxz), 0.0, 1.0), 1.5);
    // layer colours: day blue-green, golden hour warm silhouettes, night deep blue
    vec3 far = mix(vec3(0.10, 0.12, 0.30), vec3(0.50, 0.66, 0.88), day);
    vec3 mid = mix(vec3(0.07, 0.10, 0.22), vec3(0.38, 0.60, 0.56), day);
    vec3 near = mix(vec3(0.05, 0.07, 0.16), vec3(0.26, 0.47, 0.34), day);
    far = mix(far, mix(vec3(0.72, 0.42, 0.56), vec3(0.88, 0.58, 0.40), org), gold * 0.85);
    mid = mix(mid, mix(vec3(0.50, 0.30, 0.46), vec3(0.66, 0.40, 0.34), org), gold * 0.85);
    near = mix(near, mix(vec3(0.32, 0.20, 0.34), vec3(0.42, 0.27, 0.26), org), gold * 0.85);
    vec3 grey = vec3(dot(far, vec3(0.3, 0.59, 0.11)));
    far = mix(far, grey, rain * 0.6); mid = mix(mid, grey * 0.8, rain * 0.6); near = mix(near, grey * 0.6, rain * 0.6);
    vec3 c = col;
    // each layer fades into the sky with height (aerial perspective) and lights up its rim toward the sun
    if (y < h1) {
        float k = 0.78 + 0.17 * (1.0 - smoothstep(h1 - 0.05, h1, y));
        vec3 lc = far + vec3(1.0, 0.85, 0.6) * gold * side * 0.15 * smoothstep(h1 - 0.006, h1, y);
        c = mix(col, lc, k);
    }
    if (y < h2) {
        c = mix(col, mid, 0.78) + vec3(1.0, 0.8, 0.5) * gold * side * 0.12 * smoothstep(h2 - 0.004, h2, y);
    }
    if (y < h3) {
        c = mix(col, near, 0.9);
    }
    // the very bottom melts into the horizon haze, so terrain fog meets it smoothly
    // below the horizon: distant land, fading into a soft haze further down
    // (fades into the horizon colour right at the horizon, where the world's distance fog ends)
    vec3 land = mix(mix(near, col, 0.3), col, smoothstep(-0.10, 0.0, y));
    return y < 0.0 ? land : c;
}

// ---------------------------------------------------------------- Ghibli sun & moon (replace the vanilla squares)
vec3 sunAndMoon(vec3 col, vec3 dir, vec3 sunDir, float day, float gold, float night, float rain, float phase) {
    float clear = 1.0 - 0.85 * rain;
    // sun: soft round disc with a warm core
    float sa = acos(clamp(dot(dir, sunDir), -1.0, 1.0));
    float sr = 0.05;
    float disc = 1.0 - smoothstep(sr - 0.004, sr, sa);
    if (disc > 0.0 && sunDir.y > -0.08) {
        vec3 sc = mix(vec3(1.0, 0.98, 0.90), vec3(1.0, 0.80, 0.46), gold);
        sc = mix(sc, vec3(1.0), (1.0 - smoothstep(0.0, sr * 0.7, sa)) * 0.6);
        col = mix(col, sc * 1.05, disc * clear);
    }
    // moon: cream disc with phases, a few soft craters and a halo
    vec3 md = -sunDir;
    if (md.y > -0.08) {
        float ma = acos(clamp(dot(dir, md), -1.0, 1.0));
        float mr = 0.045;
        float fullness = 0.5 + 0.5 * cos(phase / 8.0 * 6.2831853);
        col += vec3(0.55, 0.70, 1.0) * exp(-ma * ma / 0.004) * 0.10 * (0.3 + 0.7 * fullness) * night * clear;
        if (ma < mr) {
            vec3 right = normalize(cross(md, vec3(0.0, 0.0, 1.0)));
            vec3 up2 = cross(right, md);
            vec2 p = vec2(dot(dir, right), dot(dir, up2)) / mr;
            float c = cos(phase / 8.0 * 6.2831853);
            float side = phase < 4.0 ? -1.0 : 1.0;
            float lit = step(-c * sqrt(max(0.0, 1.0 - p.y * p.y)), side * p.x);
            float crater = smoothstep(0.62, 0.7, noise(p * 3.0 + 4.0)) * 0.12;
            vec3 mc = vec3(0.99, 0.96, 0.86) * (1.0 - crater);
            float edge = 1.0 - smoothstep(0.85, 1.0, length(p));
            vec3 darkSide = col * 1.08 + vec3(0.01, 0.015, 0.03);
            col = mix(col, mix(darkSide, mc, lit), edge * clear * (0.35 + 0.65 * (1.0 - day)));
        }
        col += vec3(0.02, 0.04, 0.09) * fullness * night;
    }
    return col;
}


// towering summer cumulonimbus on the horizon (Laputa-style), drawn in the cloud pass.
// The puffy toon shapes are pre-baked into a sprite atlas (Sampler1), so the shader only does a few texel reads:
// cheap, and it draws the same on phone GPUs as on PCs.
vec4 towers(vec3 dir, vec3 sunDir, float day, float gold, float night, float rain, float org, float amtStyle, float seed) {
    vec4 res = vec4(0.0);
    float style = floor(amtStyle / 2.0);        // 0 mixed, 1 towers, 2 wide storms, 3 anvils
    float amt = amtStyle - style * 2.0;
    if (amt < 0.01 || dir.y < -0.03 || dir.y > 0.9) {
        return res;
    }
    float az = atan(dir.z, dir.x);
    float sunAz = atan(sunDir.z, sunDir.x);
    // five toon tones: dark base, shade, mid, lit, highlight (day -> golden hour)
    vec3 c0 = mix(vec3(0.36, 0.41, 0.55), vec3(0.38, 0.33, 0.50), gold);
    vec3 c1 = mix(vec3(0.59, 0.65, 0.81), vec3(0.62, 0.50, 0.66), gold);
    vec3 c2 = mix(vec3(0.80, 0.84, 0.94), mix(vec3(0.90, 0.68, 0.64), vec3(0.88, 0.58, 0.62), org), gold);
    vec3 c3 = mix(vec3(0.95, 0.96, 0.98), mix(vec3(1.00, 0.82, 0.62), vec3(1.00, 0.70, 0.58), org), gold);
    vec3 c4 = mix(vec3(1.00, 0.99, 0.96), mix(vec3(1.00, 0.91, 0.74), vec3(1.00, 0.82, 0.68), org), gold);
    float sd = fract(seed * 0.1173) * 10.0;
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        // one cloud in each quarter of the horizon, at a random spot within it
        float c = fi * 1.5708 + hash2(vec2(sd, fi * 3.7)) * 1.2 - 3.1416;
        float W = (0.095 + 0.035 * hash2(vec2(sd + 7.0, fi))) * (0.75 + 0.25 * amt);
        float du = az - c;
        du -= 6.2832 * floor((du + 3.1416) / 6.2832);
        float side = sin(sunAz - c) >= 0.0 ? 1.0 : -1.0;   // baked lit from the right: mirror for the other side
        float px = du / W * 64.0 * side + 160.0;
        float py = dir.y / W * 64.0;
        if (px >= 0.0 && px < 320.0 && py >= 0.0 && py < 256.0 && res.a < 0.5) {
            float pick = mod(fi * 3.0 + floor(seed), 8.0);    // 8 irregular shapes, a new mix every day
            vec4 t = texelFetch(Sampler1, ivec2(int(px) + int(pick) * 320, 255 - int(py)), 0);
            if (t.a > 0.7) {
                float band = floor(t.r * 4.0 + 0.5);
                vec3 col = band < 0.5 ? c0 : (band < 1.5 ? c1 : (band < 2.5 ? c2 : (band < 3.5 ? c3 : c4)));
                col = mix(col, vec3(1.0, 0.98, 0.92), t.g * 0.35 * max(day, gold));   // bright rim toward the sun
                col = mix(col, vec3(0.16, 0.20, 0.34), night * (1.0 - day));          // night silhouettes
                col = mix(col, vec3(dot(col, vec3(0.3, 0.59, 0.11))) * 0.85, rain * 0.7);
                col = mix(col, vec3(0.80, 0.86, 0.95), 0.25 * (1.0 - t.b) * day * (1.0 - gold)); // aerial haze low down
                res = vec4(col, smoothstep(0.0, 0.2, amt) * smoothstep(-0.03, 0.03, dir.y));
            } else if (t.a > 0.2) {
                // rain falling from the dark base
                vec3 rc = mix(c1, c0, 0.4);
                res = vec4(rc, 0.35 * smoothstep(0.0, 0.2, amt) * (0.4 + 0.6 * t.b) * smoothstep(-0.03, 0.03, dir.y));
            }
        }
    }
    return res;
}

vec3 hue(float h) {
    return clamp(abs(fract(vec3(h) + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

// ---------------------------------------------------------------- main
void main() {
    vec3 dir = normalize(skyDir);
    vec3 sunDir = TextureMat[0].xyz;
    float tm = TextureMat[0].w;
    float day = TextureMat[1].x;
    float gold = TextureMat[1].y;
    float night = TextureMat[1].z;
    float rain = TextureMat[1].w;
    vec2 cloudOff = TextureMat[2].xy;
    float auroraStrength = TextureMat[2].w;
    int flags = int(TextureMat[3].x + 0.5);
    float coverAdj = TextureMat[3].y;
    float shootPeriod = TextureMat[3].z;
    float mode = TextureMat[3].w;
    float rise = ColorModulator.x;
    float org = ColorModulator.w;
    float milkyStrength = ColorModulator.y;
    int layers = int(round(ColorModulator.z));

    if (mode > 0.5) {
        vec4 k = clouds(dir, sunDir, day, gold, org, rain, cloudOff, coverAdj, layers);
        if ((flags & F_TOWERS) != 0) {
            vec4 tw = towers(dir, sunDir, day, gold, night, rain, org, ModelOffset.y, ModelOffset.z);
            // the far, low painted clouds sit behind the towers; high ones (overhead) pass in front
            float front = smoothstep(0.30, 0.45, dir.y);
            vec4 over = vec4(mix(tw.rgb, k.rgb, k.a), max(k.a, tw.a));
            vec4 under = vec4(mix(k.rgb, tw.rgb, tw.a), max(k.a, tw.a));
            k = mix(under, over, front);
        }
        if ((flags & F_MOUNTAINS) != 0 && dir.y < 0.26) {
            vec3 hs = landHeights(atan(dir.z, dir.x));
            if (dir.y < max(hs.x, max(hs.y, hs.z))) {
                k = vec4(0.0); // painted clouds sit behind the painted mountains
            }
        }
        float sd = dot(dir, sunDir);
        float rbowK = TextureMat[2].z;
        if ((flags & F_GODRAYS) != 0 && k.a < 0.5 && (gold * day > 0.05 || rbowK > 0.05) && sd > 0.35 && dir.y > 0.0 && layers != 0) {
            // beams of warm light where the sky between this pixel and the sun is free of clouds
            float lightK = shafts(dir, sunDir, cloudOff, COV - coverAdj - 0.12 * rain);
            float ray = pow((sd - 0.35) / 0.65, 2.5) * lightK * max(gold * day, rbowK * 0.6) * (1.0 - rain * 0.7);
            vec3 rc = mix(vec3(1.0, 0.86, 0.62), vec3(1.0, 0.93, 0.70), org);
            vec4 r = vec4(rc, ray * 0.45);
            k = vec4(mix(r.rgb, k.rgb, k.a), max(k.a, r.a));
        }
        k.rgb += vec3(0.55, 0.58, 0.72) * ModelOffset.x * k.a * 0.6; // lightning lights the clouds from inside
        if (k.a < 0.004) {
            discard;
        }
        fragColor = k;
        return;
    }

    vec3 col = mix(skyNight(dir.y), skyDay(dir.y), day);
    // golden hour creeps down from... up from the horizon: the low sky turns first, the zenith last.
    // where blue and the warm colours meet, lift toward cream so the mix never turns grey/khaki
    float wv = gold >= 0.999 ? 1.0 : sstep(0.0, 1.0, gold * 2.0 - max(dir.y, 0.0));
    col = mix(col, skyEvent(dir, sunDir, rise, org), wv);
    col += vec3(0.16, 0.13, 0.07) * wv * (1.0 - wv) * 4.0 * day;

    float sd = max(dot(dir, sunDir), 0.0);
    float clear = 1.0 - rain;
    // sun glow: soft by day, wide and warm at golden hour
    col += vec3(1.0, 0.92, 0.75) * (pow(sd, 6.0) * 0.10 + pow(sd, 36.0) * 0.16) * day * (1.0 - gold) * clear;
    col += mix(vec3(1.0, 0.72, 0.38), vec3(1.0, 0.85, 0.45), org) * (pow(sd, 12.0) * 0.55 + pow(sd, 90.0) * 0.6) * gold * clear;
    // moon glow
    col += vec3(0.45, 0.65, 1.00) * pow(max(dot(dir, -sunDir), 0.0), 20.0) * 0.12 * night * clear;

    if ((flags & F_RAYS) != 0 && sd > 0.08 && gold * day > 0.02) {
        vec3 upv = abs(sunDir.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
        vec3 rt = normalize(cross(sunDir, upv));
        vec3 up2 = cross(rt, sunDir);
        float ang = atan(dot(dir, up2), dot(dir, rt));
        float streak = noise(vec2(ang * 8.0 + tm * 0.03, 2.0));
        float ray = pow(sd, 6.0) * sstep(0.35, 0.9, streak);
        col += mix(vec3(1.0, 0.85, 0.65), vec3(1.0, 0.55, 0.40), gold) * ray * (0.06 + 0.20 * gold) * day * clear;
    }

    if (night > 0.01 && dir.y > -0.03) {
        float vis = night * clear * sstep(0.0, 0.12, dir.y);
        float visLow = night * clear * sstep(0.02, 0.08, dir.y);
        col = nightSky(col, dir, sunDir, vis, visLow, tm, flags, auroraStrength, milkyStrength, shootPeriod);
    }

    if (ModelOffset.z > 0.5) {
        col = sunAndMoon(col, dir, sunDir, day, gold, night, rain, ModelOffset.y);
    }
    // lightning glowing inside the storm clouds
    col += vec3(0.50, 0.55, 0.72) * ModelOffset.x * (0.4 + 0.6 * rain) * smoothstep(-0.05, 0.3, dir.y);

    if ((flags & F_MOUNTAINS) != 0) {
        col = horizonLand(col, dir, sunDir, day, gold, night, org, rain);
    }

    float rbow = TextureMat[2].z;
    if (rbow > 0.01 && dir.y > 0.0) {
        float ang = acos(clamp(dot(dir, -sunDir), -1.0, 1.0));
        float x = (ang - 0.700) / 0.042; // 0 = violet (inner), 1 = red (outer)
        float band = smoothstep(-0.25, 0.1, x) * (1.0 - smoothstep(0.9, 1.25, x));
        if (band > 0.0) {
            vec3 rc = mix(vec3(1.0), hue((1.0 - clamp(x, 0.0, 1.0)) * 0.78), 0.55);
            col = mix(col, rc, band * rbow * 0.32 * smoothstep(0.0, 0.15, dir.y) * day);
        }
    }

    // rain: grey, flat sky
    float lum = dot(col, vec3(0.30, 0.59, 0.11));
    col = mix(col, vec3(lum * 0.92, lum * 0.95, lum * 1.02), rain * 0.80);

    fragColor = vec4(col, 1.0);
}
