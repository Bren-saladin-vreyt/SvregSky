#version 330

precision highp float;

// SvregSky "Cirrus" sky: Ghibli gradient, sun glow + rays, stars, milky way, aurora, shooting stars (mode 0)
// and the painted pixel-cumulus cloud layers (mode 1, drawn after the sun and moon so clouds cover them).
// Every parameter comes in through TextureMat / ColorModulator / ModelOffset (see CirrusSky.java).

layout(std140) uniform DynamicTransforms {
    highp mat4 ModelViewMat;
    highp vec4 ColorModulator;
    highp vec3 ModelOffset;
    highp mat4 TextureMat;
};

uniform highp sampler2D Sampler0; // baked cloud density: R = density, G/B = 16-bit cluster shape
uniform highp sampler2D Sampler1;
uniform highp sampler2D Sampler2; // 3D Ghibli cloud footprint (for light rays) // baked cumulonimbus sprites (2 rows of 8 x 320x256): R = light band 0..4, G = rim, B = height, A = 255 cloud / 110 rain

in highp vec3 skyDir;

out highp vec4 fragColor;

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
highp float hash2(highp vec2 p) {
    highp vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

highp float hash3(highp vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

highp float noise(highp vec2 p) {
    highp vec2 i = floor(p);
    highp vec2 f = fract(p);
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    highp float a = hash2(i);
    highp float b = hash2(i + vec2(1.0, 0.0));
    highp float c = hash2(i + vec2(0.0, 1.0));
    highp float d = hash2(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

highp float fbm3(highp vec2 p) {
    const highp mat2 rot = mat2(0.80, -0.60, 0.60, 0.80);
    highp float v = 0.55 * noise(p);
    p = rot * p * 2.05 + vec2(5.7, 3.2);
    v += 0.30 * noise(p);
    p = rot * p * 2.10 + vec2(11.3, 7.9);
    v += 0.15 * noise(p);
    return v;
}

highp float sstep(highp float e0, highp float e1, highp float x) {
    return smoothstep(e0, e1, x);
}

// ---------------------------------------------------------------- background gradient
highp vec3 gradient(highp float dy, highp vec3 horizon, highp vec3 mid, highp vec3 zenith, highp vec3 below) {
    highp float h = clamp(dy, 0.0, 1.0);
    highp vec3 col = mix(mix(horizon, mid, sstep(0.0, 0.22, h)), zenith, sstep(0.18, 0.75, h));
    highp vec3 under = mix(horizon, below, 1.0 - sstep(-0.25, 0.0, dy));
    return dy < 0.0 ? under : col;
}

highp vec3 skyDay(highp float dy) {
    return gradient(dy, vec3(0.80, 0.96, 0.99), vec3(0.30, 0.75, 0.98), vec3(0.12, 0.58, 0.97), vec3(0.45, 0.70, 0.85));
}

highp vec3 skyNight(highp float dy) {
    return gradient(dy, vec3(0.22, 0.20, 0.66), vec3(0.10, 0.12, 0.46), vec3(0.03, 0.06, 0.22), vec3(0.05, 0.06, 0.14));
}

// magenta -> yellow sunset (rise = 0) or a softer, pinker sunrise (rise = 1)
// warm amber golden hour: the whole sky goes orange, yellow at the horizon by the sun
highp vec3 skyOrange(highp vec3 dir, highp float h, highp float side) {
    highp vec3 sg = mix(vec3(1.00, 0.86, 0.42), vec3(0.98, 0.70, 0.16), sstep(0.0, 0.12, h));
    sg = mix(sg, vec3(0.90, 0.55, 0.03), sstep(0.10, 0.40, h));
    sg = mix(sg, vec3(0.84, 0.47, 0.02), sstep(0.40, 0.95, h));
    highp vec3 ag = mix(vec3(0.95, 0.62, 0.14), vec3(0.88, 0.51, 0.04), sstep(0.0, 0.30, h));
    ag = mix(ag, vec3(0.80, 0.44, 0.04), sstep(0.30, 0.95, h));
    return mix(ag, sg, side);
}

highp vec3 skyEvent(highp vec3 dir, highp vec3 sunDir, highp float rise, highp float org) {
    highp float dy = dir.y;
    highp float h = clamp(dy, 0.0, 1.0);
    highp vec2 dxz = normalize(dir.xz + vec2(1e-5));
    highp vec2 sxz = normalize(sunDir.xz + vec2(1e-5));
    highp float side = pow(clamp(0.5 + 0.5 * dot(dxz, sxz), 0.0, 1.0), 1.4);

    highp vec3 hz = mix(vec3(0.97, 0.55, 0.16), vec3(1.00, 0.68, 0.32), rise);
    highp vec3 og = mix(vec3(0.86, 0.32, 0.28), vec3(0.94, 0.46, 0.40), rise);
    highp vec3 mg = mix(vec3(0.64, 0.18, 0.50), vec3(0.76, 0.32, 0.58), rise);
    const highp vec3 vi = vec3(0.55, 0.17, 0.58);
    const highp vec3 ze = vec3(0.45, 0.16, 0.56);

    highp vec3 sg = mix(hz, og, sstep(0.0, 0.10, h));
    sg = mix(sg, mg, sstep(0.08, 0.30, h));
    sg = mix(sg, vi, sstep(0.28, 0.60, h));
    sg = mix(sg, ze, sstep(0.55, 0.95, h));

    highp vec3 ag = mix(vec3(0.78, 0.34, 0.46), vec3(0.52, 0.18, 0.56), sstep(0.0, 0.25, h));
    ag = mix(ag, ze, sstep(0.25, 0.85, h));
    highp vec3 col = mix(ag, sg, side);
    col = mix(col, skyOrange(dir, h, side), org);

    highp vec3 under = mix(mix(vec3(0.55, 0.32, 0.48), vec3(0.95, 0.55, 0.35), side), vec3(0.20, 0.15, 0.28), 1.0 - sstep(-0.25, 0.0, dy));
    return dy < 0.0 ? under : col;
}

// ---------------------------------------------------------------- night sky
highp float softStar(highp vec3 p, highp float scale, highp float thr, highp float tm, out highp float seed) {
    highp vec3 sp = p * scale;
    highp vec3 id = floor(sp);
    highp vec3 f = fract(sp) - 0.5;
    highp float hs = hash3(id);
    seed = hs;
    if (hs < thr) {
        return 0.0;
    }
    highp vec3 offset = vec3(fract(hs * 13.7), fract(hs * 29.3), fract(hs * 53.1)) - 0.5;
    highp float d = length(f - offset * 0.35);
    highp float core = exp(-d * d * 38.0);
    highp float halo = exp(-d * d * 10.0) * 0.35;
    highp float twinkle = 0.65 + 0.35 * sin(tm * (1.4 + fract(hs * 19.0) * 1.5) + hs * 300.0);
    return (core + halo) * (1.0 - sstep(0.08, 0.45, d)) * twinkle;
}

highp vec4 auroraCurtain(highp float u, highp float h, highp float tm, highp float seed, highp float baseH, highp float amp) {
    highp float wob = noise(vec2(u * 2.2 + tm * 0.020, seed));
    highp float wob2 = noise(vec2(u * 5.5 - tm * 0.035, seed + 4.0));
    highp float c = baseH + amp * (wob - 0.5) * 2.0 + 0.03 * (wob2 - 0.5);
    highp float rel = h - c;
    if (rel < -0.01) {
        return vec4(0.0);
    }
    highp float body = sstep(0.0, 0.012, rel) * exp(-max(rel, 0.0) * 6.0);
    highp float rays = 0.40 + 0.60 * sstep(0.25, 0.85, noise(vec2(u * 48.0 + wob * 6.0, tm * 0.09 + seed)));
    highp float fold = sstep(0.30, 0.70, noise(vec2(u * 1.5 + tm * 0.010, seed + 11.0)));
    highp float hh = clamp(rel * 4.5, 0.0, 1.0);
    highp vec3 ac = mix(mix(vec3(0.15, 1.00, 0.55), vec3(0.10, 0.80, 0.95), sstep(0.0, 0.45, hh)), vec3(0.68, 0.30, 0.95), sstep(0.45, 1.0, hh));
    return vec4(ac, body * rays * fold);
}

highp vec3 nightSky(highp vec3 col, highp vec3 dir, highp vec3 sunDir, highp float vis, highp float visLow, highp float tm, int flags, highp float auroraStrength, highp float milkyStrength, highp float shootPeriod) {
    // celestial frame: turns with the sun, so stars and the milky way rise and set
    highp float sl = max(length(sunDir.xy), 0.001);
    highp float cs = sunDir.x / sl;
    highp float sn = sunDir.y / sl;
    highp vec3 cel = vec3(dir.x * cs + dir.y * sn, -dir.x * sn + dir.y * cs, dir.z);
    highp float mwMask = 0.0;

    if ((flags & F_MILKYWAY) != 0 && milkyStrength > 0.0) {
        const highp vec3 gN = vec3(0.1527, 0.5599, 0.8144);
        const highp vec3 gE1 = vec3(0.9649, -0.2631, 0.0);
        const highp vec3 gE2 = vec3(0.2143, 0.7858, -0.5804);
        highp float bd = dot(cel, gN);
        highp float mw = exp(-(bd * bd) / 0.03);
        mwMask = mw;
        if (mw > 0.002) {
            highp float lonA = atan(dot(cel, gE2), dot(cel, gE1));
            highp vec2 mp = vec2(lonA * 3.0, bd * 9.0);
            highp float dust1 = fbm3(mp * 1.8 + 3.1);
            highp float dust2 = fbm3(mp * 3.8 + 11.7);
            highp float dust3 = noise(mp * 11.0 + 5.0);
            highp float coreW = 0.45 + 0.85 * sstep(-0.3, 0.9, dot(cel, gE1));
            highp float core = exp(-(bd * bd) / 0.006) * coreW;
            highp float glow = (0.28 + 0.90 * dust1) * (mw * 0.65 + core * 1.10) * (0.75 + 0.50 * dust3);
            highp float riftC = bd + (dust2 - 0.5) * 0.10;
            glow *= 1.0 - 0.75 * exp(-(riftC * riftC) / 0.0016) * sstep(0.25, 0.70, dust1 + 0.2);
            highp vec3 mwCol = mix(mix(vec3(0.30, 0.40, 0.85), vec3(0.52, 0.50, 0.92), sstep(0.08, 0.40, glow)), vec3(0.80, 0.72, 0.92), sstep(0.60, 1.30, glow * coreW));
            col = mix(col, mwCol, clamp(glow * 0.28, 0.0, 0.40) * vis * milkyStrength);
        }
    }

    if ((flags & F_STARS) != 0) {
        highp float hs1, hs2;
        highp float small = softStar(cel, 240.0, 0.988 - 0.025 * mwMask, tm, hs1);
        highp float big = softStar(cel + vec3(0.37, 0.71, 0.19), 95.0, 0.994, tm * 0.8, hs2);
        highp vec3 c1 = mix(vec3(0.75, 0.88, 1.00), vec3(1.00, 0.92, 0.80), fract(hs1 * 91.7));
        highp vec3 c2 = mix(vec3(0.85, 0.92, 1.00), vec3(1.00, 0.85, 0.70), fract(hs2 * 47.3));
        col += (c1 * small * 1.05 + c2 * big * 1.70) * vis;
    }

    if ((flags & F_AURORA) != 0 && auroraStrength > 0.0) {
        highp vec2 dxz = normalize(dir.xz + vec2(1e-4));
        const highp vec2 aDir = vec2(0.34, -0.94); // toward north
        highp float along = dot(dxz, aDir);
        if (along > -0.6) {
            highp float u = atan(dot(dxz, vec2(-aDir.y, aDir.x)), along);
            highp float sector = sstep(-0.6, 0.15, along);
            highp vec4 a1 = auroraCurtain(u, dir.y, tm, 1.0, 0.17, 0.07);
            highp vec4 a2 = auroraCurtain(u * 1.3 + 2.0, dir.y, tm, 7.0, 0.31, 0.06);
            col += (a1.rgb * a1.a + a2.rgb * a2.a * 0.55) * 0.80 * auroraStrength * sector * visLow;
        }
    }

    if ((flags & F_SHOOTING) != 0 && shootPeriod > 0.5) {
        highp float tt = tm / shootPeriod;
        highp float sid = floor(tt);
        highp float lt = fract(tt) * shootPeriod;
        if (lt < 1.1 && hash2(vec2(sid, 1.7)) < 0.75) {
            highp float azs = hash2(vec2(sid, 5.3)) * 6.2831853;
            highp float els = 0.35 + 0.55 * hash2(vec2(sid, 9.1));
            highp float rr = sqrt(1.0 - els * els);
            highp vec3 sa = vec3(cos(azs) * rr, els, sin(azs) * rr);
            highp vec3 st0 = normalize(cross(sa, vec3(0.0, 1.0, 0.0)));
            highp vec3 st1 = cross(sa, st0);
            highp float sang = hash2(vec2(sid, 13.7)) * 6.2831853;
            highp vec3 sv = st0 * cos(sang) + st1 * sin(sang);
            highp vec3 snrm = cross(sa, sv);
            highp float prog = lt / 1.1;
            highp float sHead = prog * 0.55;
            highp float sTail = max(sHead - 0.16 * (0.4 + 0.6 * sin(prog * 3.14159)), 0.0);
            highp float env = sstep(0.0, 0.12, prog) * (1.0 - sstep(0.70, 1.0, prog));
            highp float off = abs(dot(dir, snrm));
            if (off < 0.012) {
                highp float sdAng = atan(dot(dir, sv), dot(dir, sa));
                highp float alongS = (sdAng - sTail) / max(sHead - sTail, 0.0001);
                if (alongS > 0.0 && alongS < 1.0) {
                    highp float wS = mix(0.0006, 0.0026, alongS);
                    highp float lineS = 1.0 - sstep(0.0, wS, off);
                    col += vec3(0.85, 0.93, 1.0) * lineS * pow(alongS, 2.2) * env * 2.2 * vis;
                }
            }
            highp vec3 headP = sa * cos(sHead) + sv * sin(sHead);
            highp float hd = length(dir - headP);
            col += vec3(0.90, 0.95, 1.0) * exp(-hd * hd * 9000.0) * env * 1.6 * vis;
        }
    }
    return col;
}

// ---------------------------------------------------------------- painted pixel cumulus
const highp float PX = 62.0;   // sky pixels per projection unit
const highp float COV = 0.545; // density threshold of the near layer (~30% of the sky)

highp vec2 decode(highp vec4 t) {
    return vec2(t.r, t.g * (255.0 * 256.0 / 65535.0) + t.b * (255.0 / 65535.0));
}

// density (x) and cluster shape (y) at cloud-plane position q; one texture tile = 40 plane units, wraps around
highp vec2 cloudSample(highp vec2 q) {
    ivec2 size = textureSize(Sampler0, 0);
    highp vec2 fs = vec2(size);
    highp vec2 t = mod(q / 40.0 * fs - 0.5, fs);
    highp vec2 i = floor(t);
    highp vec2 f = t - i;
    ivec2 i0 = min(ivec2(i), size - 1);
    ivec2 i1 = i0 + 1;
    i1 -= size * ivec2(greaterThanEqual(i1, size));
    highp vec2 va = decode(texelFetch(Sampler0, i0, 0));
    highp vec2 vb = decode(texelFetch(Sampler0, ivec2(i1.x, i0.y), 0));
    highp vec2 vc = decode(texelFetch(Sampler0, ivec2(i0.x, i1.y), 0));
    highp vec2 vd = decode(texelFetch(Sampler0, i1, 0));
    return mix(mix(va, vb, f.x), mix(vc, vd, f.x), f.y);
}

struct Palette {
    highp vec3 t0;
    highp vec3 t1;
    highp vec3 t2;
    highp vec3 t3;
    highp vec3 haze;
};

highp vec4 cloudLayer(highp vec2 q, highp vec2 ldir, highp float thr, Palette p, highp float hz, highp float am) {
    highp vec2 s = cloudSample(q);
    if (s.x < thr) {
        return vec4(0.0);
    }
    highp float cl2 = cloudSample(q + ldir * 0.45).y;
    highp float sun = clamp((s.y - cl2) * 14.0 + 0.55, 0.0, 1.0);
    highp float thick = clamp((s.x - thr) * 5.0, 0.0, 1.0);
    highp float l = clamp(0.55 * sun + 0.45 * (1.0 - thick * 0.9), 0.0, 1.0);
    l = floor(l * 3.999) / 3.0;
    highp vec3 c = p.t0;
    c = mix(c, p.t1, step(0.30, l));
    c = mix(c, p.t2, step(0.60, l));
    c = mix(c, p.t3, step(0.95, l));
    return vec4(mix(c, p.haze, hz), am);
}

highp vec4 clouds(highp vec3 dir, highp vec3 sunDir, highp float day, highp float gold, highp float org, highp float rain, highp vec2 off, highp float coverAdj, int layers) {
    if (dir.y < 0.03) {
        return vec4(0.0);
    }
    // palettes: day / golden hour / night, then rain greys them out
    highp vec3 lit = mix(vec3(0.36, 0.38, 0.55), vec3(1.00, 1.00, 0.98), day);
    highp vec3 mid = mix(vec3(0.20, 0.21, 0.34), vec3(0.84, 0.92, 0.99), day);
    highp vec3 shade = mix(vec3(0.10, 0.10, 0.19), vec3(0.62, 0.76, 0.93), day);
    highp vec3 haze = mix(vec3(0.14, 0.16, 0.44), vec3(0.48, 0.80, 0.97), day);
    // golden hour: rose clouds over the magenta sky, or periwinkle-white clouds over the orange sky
    highp vec3 gLit = mix(vec3(1.00, 0.88, 0.72), vec3(0.88, 0.91, 1.00), org);
    highp vec3 gMid = mix(vec3(0.84, 0.66, 0.74), vec3(0.72, 0.80, 1.00), org);
    highp vec3 gShade = mix(vec3(0.44, 0.34, 0.56), vec3(0.56, 0.62, 0.92), org);
    highp vec3 gHaze = mix(vec3(0.86, 0.40, 0.46), vec3(0.96, 0.64, 0.20), org);
    lit = mix(lit, gLit, gold);
    mid = mix(mid, gMid, gold);
    shade = mix(shade, gShade, gold);
    haze = mix(haze, gHaze, gold);
    highp float dim = 0.30 + 0.70 * day;
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

    highp vec2 p0 = dir.xz / (max(dir.y, 0.07) + 0.085);
    highp vec2 q0 = (floor(p0 * PX) + 0.5) / PX;
    // light from the zenith (toward the middle of the sky); at golden hour it swings toward the sun
    highp vec2 ldir = -q0 / (length(q0) + 1e-4);
    highp vec2 sxz = sunDir.xz / (length(sunDir.xz) + 1e-4);
    ldir = normalize(mix(ldir, sxz, gold * 0.6) + vec2(1e-4));

    highp float cov = COV - coverAdj - 0.12 * rain;
    bool skipNear = layers < 0;
    layers = abs(layers);
    highp vec4 k = skipNear ? vec4(0.0) : cloudLayer(q0 * 3.0 + off, ldir, cov, p, 0.0, 1.0);
    if (k.a < 0.5 && layers >= 2) {
        const highp mat2 r1 = mat2(0.80, -0.60, 0.60, 0.80);
        k = cloudLayer(r1 * (q0 * 5.6) + vec2(37.1, 12.7) + off, r1 * ldir, cov + 0.075, p, 0.28, 0.95);
    }
    if (k.a < 0.5 && layers >= 3) {
        const highp mat2 r2 = mat2(0.28, 0.96, -0.96, 0.28);
        k = cloudLayer(r2 * (q0 * 9.5) + vec2(81.3, 55.9) + off, r2 * ldir, cov + 0.105, p, 0.52, 0.90);
    }
    highp float hf = (1.0 - sstep(0.0, 0.35, dir.y)) * 0.35;
    k.rgb = mix(k.rgb, haze, hf);
    k.a *= sstep(0.03, 0.24, dir.y) * mix(0.75, 1.0, max(day, gold));
    return k;
}

// ---------------------------------------------------------------- light shafts through gaps in the painted clouds
highp float shafts(highp vec3 dir, highp vec3 sunDir, highp vec2 off, highp float cov) {
    highp vec2 p0 = dir.xz / (max(dir.y, 0.07) + 0.085);
    highp vec2 ps = sunDir.xz / (max(sunDir.y, 0.07) + 0.085);
    highp float open = 0.0;
    for (int i = 1; i <= 8; i++) {
        highp vec2 q = mix(p0, ps, float(i) / 9.0);
        open += step(cloudSample(q * 3.0 + off).x, cov);
    }
    return open / 8.0;
}

// ---------------------------------------------------------------- rainbow (after rain): pastel arc 40-42 degrees around the antisolar point
// ---------------------------------------------------------------- painted horizon: far mountains, hills, a forest line
highp float ridge(highp float a, highp float freq, highp float seed, highp float sharp) {
    // periodic 1D fbm around the horizon (a = azimuth in radians)
    highp float v = 0.0, amp = 0.55, f = freq;
    for (int i = 0; i < 4; i++) {
        highp float x = a * f + seed * 7.13;
        highp float n = noise(vec2(cos(a) * f, sin(a) * f) * 0.5 + vec2(seed * 3.1, float(i) * 5.7));
        v += amp * mix(n, 1.0 - abs(2.0 * n - 1.0), sharp);
        amp *= 0.5;
        f *= 2.1;
    }
    return v;
}

highp vec3 landHeights(highp float a) {
    highp float h1 = 0.02 + 0.24 * pow(ridge(a, 2.0, 1.0, 0.85), 1.7);           // far mountains
    highp float h2 = 0.012 + 0.10 * pow(ridge(a + 1.7, 3.5, 2.0, 0.35), 1.3);    // rolling hills
    highp float trees = noise(vec2(a * 140.0, 3.0)) * 0.009 + step(0.62, noise(vec2(a * 55.0, 9.0))) * 0.007;
    highp float h3 = 0.006 + 0.03 * ridge(a + 4.1, 6.0, 3.0, 0.1) + trees;       // forest line
    return vec3(h1, h2, h3);
}

highp vec3 horizonLand(highp vec3 col, highp vec3 dir, highp vec3 sunDir, highp float day, highp float gold, highp float night, highp float org, highp float rain) {
    highp float a = atan(dir.z, dir.x);
    highp float y = dir.y;
    if (y > 0.26) {
        return col;
    }
    highp vec3 hs = landHeights(a);
    highp float h1 = hs.x, h2 = hs.y, h3 = hs.z;
    highp vec2 dxz = normalize(dir.xz + vec2(1e-5));
    highp vec2 sxz = normalize(sunDir.xz + vec2(1e-5));
    highp float side = pow(clamp(0.5 + 0.5 * dot(dxz, sxz), 0.0, 1.0), 1.5);
    // layer colours: day blue-green, golden hour warm silhouettes, night deep blue
    highp vec3 far = mix(vec3(0.10, 0.12, 0.30), vec3(0.50, 0.66, 0.88), day);
    highp vec3 mid = mix(vec3(0.07, 0.10, 0.22), vec3(0.38, 0.60, 0.56), day);
    highp vec3 near = mix(vec3(0.05, 0.07, 0.16), vec3(0.26, 0.47, 0.34), day);
    far = mix(far, mix(vec3(0.72, 0.42, 0.56), vec3(0.88, 0.58, 0.40), org), gold * 0.85);
    mid = mix(mid, mix(vec3(0.50, 0.30, 0.46), vec3(0.66, 0.40, 0.34), org), gold * 0.85);
    near = mix(near, mix(vec3(0.32, 0.20, 0.34), vec3(0.42, 0.27, 0.26), org), gold * 0.85);
    highp vec3 grey = vec3(dot(far, vec3(0.3, 0.59, 0.11)));
    far = mix(far, grey, rain * 0.6); mid = mix(mid, grey * 0.8, rain * 0.6); near = mix(near, grey * 0.6, rain * 0.6);
    highp vec3 c = col;
    // each layer fades into the sky with height (aerial perspective) and lights up its rim toward the sun
    if (y < h1) {
        highp float k = 0.78 + 0.17 * (1.0 - smoothstep(h1 - 0.05, h1, y));
        highp vec3 lc = far + vec3(1.0, 0.85, 0.6) * gold * side * 0.15 * smoothstep(h1 - 0.006, h1, y);
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
    highp vec3 land = mix(mix(near, col, 0.3), col, smoothstep(-0.10, 0.0, y));
    return y < 0.0 ? land : c;
}

// ---------------------------------------------------------------- Ghibli sun & moon (replace the vanilla squares)
highp vec3 sunAndMoon(highp vec3 col, highp vec3 dir, highp vec3 sunDir, highp float day, highp float gold, highp float night, highp float rain, highp float phase) {
    highp float clear = 1.0 - 0.85 * rain;
    // sun: soft round disc with a warm core
    highp float sa = acos(clamp(dot(dir, sunDir), -1.0, 1.0));
    highp float sr = 0.05;
    highp float disc = 1.0 - smoothstep(sr - 0.004, sr, sa);
    if (disc > 0.0 && sunDir.y > -0.08) {
        highp vec3 sc = mix(vec3(1.0, 0.98, 0.90), vec3(1.0, 0.80, 0.46), gold);
        sc = mix(sc, vec3(1.0), (1.0 - smoothstep(0.0, sr * 0.7, sa)) * 0.6);
        col = mix(col, sc * 1.05, disc * clear);
    }
    // moon: cream disc with phases, a few soft craters and a halo
    highp vec3 md = -sunDir;
    if (md.y > -0.08) {
        highp float ma = acos(clamp(dot(dir, md), -1.0, 1.0));
        highp float mr = 0.045;
        highp float fullness = 0.5 + 0.5 * cos(phase / 8.0 * 6.2831853);
        col += vec3(0.55, 0.70, 1.0) * exp(-ma * ma / 0.004) * 0.10 * (0.3 + 0.7 * fullness) * night * clear;
        if (ma < mr) {
            highp vec3 right = normalize(cross(md, vec3(0.0, 0.0, 1.0)));
            highp vec3 up2 = cross(right, md);
            highp vec2 p = vec2(dot(dir, right), dot(dir, up2)) / mr;
            highp float c = cos(phase / 8.0 * 6.2831853);
            highp float side = phase < 4.0 ? -1.0 : 1.0;
            highp float lit = step(-c * sqrt(max(0.0, 1.0 - p.y * p.y)), side * p.x);
            highp float crater = smoothstep(0.62, 0.7, noise(p * 3.0 + 4.0)) * 0.12;
            highp vec3 mc = vec3(0.99, 0.96, 0.86) * (1.0 - crater);
            highp float edge = 1.0 - smoothstep(0.85, 1.0, length(p));
            highp vec3 darkSide = col * 1.08 + vec3(0.01, 0.015, 0.03);
            col = mix(col, mix(darkSide, mc, lit), edge * clear * (0.35 + 0.65 * (1.0 - day)));
        }
        col += vec3(0.02, 0.04, 0.09) * fullness * night;
    }
    return col;
}


// towering summer cumulonimbus on the horizon (Laputa-style), drawn in the cloud pass.
// The puffy toon shapes are pre-baked into a sprite atlas (Sampler1), so the shader only does a few texel reads:
// cheap, and it draws the same on phone GPUs as on PCs.
const int F_MEGA = 1024;
const int F_LINING = 2048;
const int F_TLIGHT = 4096;
const int F_ROWS = 8192;
const int F_CIRRUS = 16384;
const int F_NOPAINT = 32768;
const int F_RAYS3D = 65536;
bool tflag(int b) {
    return (int(TextureMat[3].x + 0.5) & b) != 0;
}

// ---- experimental cumulonimbus: depth rows, one mega storm, lightning inside, silver lining + sun beams, mackerel sky
highp vec4 towerTex(highp float px, highp float py, highp float pick) {
    int ip = int(pick);
    return texelFetch(Sampler1, ivec2(int(px) + (ip - (ip / 8) * 8) * 320, (ip / 8) * 256 + 255 - int(py)), 0);
}

// tower slot k (0..11): row = k / 4 (0 near .. 2 far)
void towerSlot(int k, highp float sd, highp float amt, out highp float c, out highp float W, out highp float haze, out highp float pick) {
    highp float fk = float(k);
    int row = k / 4;
    highp float fi = float(k - row * 4);
    highp float rs = row == 0 ? 1.0 : (row == 1 ? 0.62 : 0.40);
    c = fi * 1.5708 + hash2(vec2(sd + float(row) * 13.1, fi * 3.7)) * 1.5 - 3.1416 + float(row) * 0.6;
    W = (0.095 + 0.035 * hash2(vec2(sd + 7.0 + float(row) * 5.3, fi))) * (0.75 + 0.25 * amt) * rs;
    if (k == 0 && tflag(F_MEGA)) W *= 2.1;
    haze = row == 0 ? 0.0 : (row == 1 ? 0.30 : 0.55);
    pick = mod(fk * 5.0 + floor(sd * 10.0) * 3.0, 16.0);
}

// sprite lookup for slot k: the mega slot reads the big, detailed mega sprites
highp vec4 slotTex(int k, highp float du, highp float y, highp float side, highp float W, highp float pick, highp float sd, out bool inside) {
    // true angles on the sky: azimuth shrinks with height (cos of elevation), height is the elevation angle
    highp float yc = clamp(y, -0.999, 0.999);
    du *= sqrt(1.0 - yc * yc);
    y = asin(yc);
    if (k == 0 && tflag(F_MEGA)) {
        highp float px = du / W * 96.0 * side + 240.0;
        highp float py = y / W * 96.0;
        inside = px >= 0.0 && px < 480.0 && py >= 0.0 && py < 480.0;
        if (!inside) return vec4(0.0);
        int m = int(mod(floor(sd * 10.0), 6.0));
        return texelFetch(Sampler1, ivec2(int(px) + m * 480, 512 + 479 - int(py)), 0);
    }
    highp float px = du / W * 64.0 * side + 160.0;
    highp float py = y / W * 64.0;
    inside = px >= 0.0 && px < 320.0 && py >= 0.0 && py < 256.0;
    if (!inside) return vec4(0.0);
    return towerTex(px, py, pick);
}

highp float towerCover(highp float az, highp float y, highp float sunAz, highp float sd, highp float amt) {
    for (int k = 0; k < 12; k++) {
        if (k >= (tflag(F_ROWS) ? 12 : 4)) break;
        highp float c, W, haze, pick;
        towerSlot(k, sd, amt, c, W, haze, pick);
        highp float du = az - c;
        du -= 6.2832 * floor((du + 3.1416) / 6.2832);
        highp float side = sin(sunAz - c) >= 0.0 ? 1.0 : -1.0;
        bool inside;
        if (slotTex(k, du, y, side, W, pick, sd, inside).a > 0.7) {
            return 1.0;
        }
    }
    return 0.0;
}

highp vec4 towers(highp vec3 dir, highp vec3 sunDir, highp float day, highp float gold, highp float night, highp float rain, highp float org, highp float amtStyle, highp float seed) {
    highp vec4 res = vec4(0.0);
    highp float amt = amtStyle - floor(amtStyle / 2.0) * 2.0;
    if (amt < 0.01 || dir.y < -0.03 || dir.y > 1.2) {
        return res;
    }
    highp float az = atan(dir.z, dir.x);
    highp float sunAz = atan(sunDir.z, sunDir.x);
    highp vec3 c0 = mix(vec3(0.36, 0.41, 0.55), vec3(0.38, 0.33, 0.50), gold);
    highp vec3 c1 = mix(vec3(0.59, 0.65, 0.81), vec3(0.62, 0.50, 0.66), gold);
    highp vec3 c2 = mix(vec3(0.80, 0.84, 0.94), mix(vec3(0.90, 0.68, 0.64), vec3(0.88, 0.58, 0.62), org), gold);
    highp vec3 c3 = mix(vec3(0.95, 0.96, 0.98), mix(vec3(1.00, 0.82, 0.62), vec3(1.00, 0.70, 0.58), org), gold);
    highp vec3 c4 = mix(vec3(1.00, 0.99, 0.96), mix(vec3(1.00, 0.91, 0.74), vec3(1.00, 0.82, 0.68), org), gold);
    highp vec3 hazeC = mix(vec3(0.80, 0.86, 0.95), vec3(0.98, 0.72, 0.62), gold);
    highp float sd = fract(seed * 0.1173) * 10.0;
    for (int k = 0; k < 12; k++) {
        if (k >= (tflag(F_ROWS) ? 12 : 4)) break;
        highp float c, W, haze, pick;
        towerSlot(k, sd, amt, c, W, haze, pick);
        highp float du = az - c;
        du -= 6.2832 * floor((du + 3.1416) / 6.2832);
        highp float side = sin(sunAz - c) >= 0.0 ? 1.0 : -1.0;
        bool inside;
        highp vec4 t = res.a < 0.5 ? slotTex(k, du, dir.y, side, W, pick, sd, inside) : vec4(0.0);
        highp float px = du / W * 64.0;   // sprite-space helpers for the lightning spot (64 px per unit)
        highp float py = dir.y / W * 64.0;
        if (inside && res.a < 0.5) {
            if (t.a > 0.7) {
                highp float band = floor(t.r * 4.0 + 0.5);
                highp vec3 col = band < 0.5 ? c0 : (band < 1.5 ? c1 : (band < 2.5 ? c2 : (band < 3.5 ? c3 : c4)));
                col = mix(col, vec3(1.0, 0.98, 0.92), t.g * 0.35 * max(day, gold));
                if (tflag(F_LINING)) {
                // silver lining: the sun hides behind this tower -> its edges toward the sun glow gold-white
                highp float sunDu = sunAz - c;
                sunDu -= 6.2832 * floor((sunDu + 3.1416) / 6.2832);
                highp float behind = smoothstep(1.4, 0.4, abs(sunDu) / W) * step(-0.05, sunDir.y);
                highp float edge = 0.0;
                if (behind > 0.0) {
                    highp vec2 toSun = normalize(vec2(sunDu - du, sunDir.y - dir.y) + vec2(1e-5));
                    for (int e = 1; e <= 3; e++) {
                        highp vec2 q = vec2(du, dir.y) + toSun * float(e) * W * 0.045;
                        bool ins;
                        if (slotTex(k, q.x, q.y, side, W, pick, sd, ins).a < 0.7) {
                            edge = max(edge, 1.0 - float(e - 1) / 3.0);
                        }
                    }
                }
                col = mix(col, mix(vec3(1.0, 0.98, 0.90), vec3(1.0, 0.90, 0.62), gold), edge * behind);
                col = mix(col, col * vec3(0.72, 0.66, 0.80), behind * (1.0 - edge) * gold);
                }
                col = mix(col, vec3(0.16, 0.20, 0.34), night * (1.0 - day));
                if (tflag(F_TLIGHT)) {
                // lightning glowing inside the storm cloud
                // a flash somewhere inside the cloud: violet-white core, toon bands fading out
                highp vec2 fc = vec2(-90.0 + 180.0 * hash2(vec2(sd, floor(ModelOffset.x * 9.0))), 90.0 + 130.0 * hash2(vec2(floor(ModelOffset.x * 9.0), sd)));
                highp float fd = length(vec2(px, py) - fc) / 120.0 + (noise(vec2(px, py) / 22.0 + sd) - 0.5) * 0.7;
                highp float fl = ModelOffset.x * (fd < 0.25 ? 1.0 : (fd < 0.7 ? 0.5 : (fd < 1.2 ? 0.2 : 0.0)));
                col = mix(col, mix(vec3(0.62, 0.42, 0.80), vec3(0.96, 0.88, 1.0), step(0.9, fl)), fl * 0.8);
                }
                col = mix(col, vec3(dot(col, vec3(0.3, 0.59, 0.11))) * 0.85, rain * 0.7);
                col = mix(col, hazeC, 0.25 * (1.0 - t.b) * day * (1.0 - gold));
                col = mix(col, hazeC * mix(1.0, 0.35, night), haze);             // far rows fade into the sky
                res = vec4(col, smoothstep(0.0, 0.2, amt) * smoothstep(-0.03, 0.03, dir.y));
            } else if (t.a > 0.2) {
                highp vec3 rc = mix(c1, c0, 0.4);
                res = vec4(mix(rc, hazeC, haze), 0.35 * smoothstep(0.0, 0.2, amt) * (0.4 + 0.6 * t.b) * smoothstep(-0.03, 0.03, dir.y));
            }
        }
    }
    if (tflag(F_LINING)) {
    if (res.a < 0.5 && gold > 0.05 && sunDir.y > -0.05) {
        // sun beams fanning out from behind a tower: march toward the sun, gaps let light through
        highp float dAz = sunAz - az;
        dAz -= 6.2832 * floor((dAz + 3.1416) / 6.2832);
        highp vec2 toSun = vec2(dAz, sunDir.y - dir.y);
        highp float dist = length(toSun);
        if (dist < 0.9 && towerCover(sunAz, sunDir.y, sunAz, sd, amt) > 0.5) {
            highp float lit = 0.0;
            for (int s = 1; s <= 8; s++) {
                highp vec2 p = vec2(az, dir.y) + toSun * (float(s) / 8.0);
                lit += 1.0 - towerCover(p.x, p.y, sunAz, sd, amt);
            }
            lit /= 8.0;
            highp float ang = atan(toSun.y, toSun.x);
            highp float streak = smoothstep(0.2, 0.8, 0.5 + 0.5 * sin(ang * 26.0 + sd * 5.0) * sin(ang * 11.0 + 1.3));
            highp float b = lit * streak * (1.0 - smoothstep(0.05, 1.1, dist)) * gold;
            res = vec4(mix(vec3(1.0, 0.88, 0.62), vec3(1.0, 0.80, 0.58), org), b * 0.75);
        }
    }
    }
    return res;
}



// high, thin painted cirrus streaks above everything else (cloud pass)
highp vec4 cirrus(highp vec3 d, highp float day, highp float gold, highp float night, highp float org, highp vec2 off) {
    if (d.y < 0.03) return vec4(0.0);
    highp vec2 q = d.xz / d.y * 0.9 + off * 0.05;
    q = floor(q * 60.0) / 60.0;
    highp vec2 r = vec2(q.x * 0.9 + q.y * 0.45, -q.x * 0.45 + q.y * 0.9);
    highp float streak = noise(r * vec2(0.8, 4.0)) * 0.65 + noise(r * vec2(2.0, 9.0)) * 0.35;
    highp float field = noise(q * 0.35 + 7.0);
    highp float a = smoothstep(0.6, 0.8, streak) * smoothstep(0.55, 0.75, field) * smoothstep(0.03, 0.15, d.y);
    highp vec3 c = mix(vec3(1.0), mix(vec3(1.0, 0.80, 0.82), vec3(1.0, 0.86, 0.62), org), gold);
    c = mix(c, vec3(0.30, 0.33, 0.50), night * (1.0 - day));
    return vec4(c, a * 0.45 * max(max(day, gold), 0.35));
}

// crepuscular rays under the 3D Ghibli clouds: Sampler2 = cloud footprint (which columns hold cloud)
highp float rays3d(highp vec3 dir, highp vec3 sunDir) {
    if (sunDir.y < 0.03 || dir.y < 0.0) return 0.0;
    highp float base = TextureMat[3].z;             // cloud base height above the camera (blocks)
    highp vec2 off = TextureMat[2].xy;              // camera position in footprint texels
    highp float tEnd = min(base / max(dir.y, 0.02), 1400.0);
    highp float lit = 0.0;
    for (int i = 0; i < 14; i++) {
        highp vec3 p = dir * (tEnd * (float(i) + 0.5) / 14.0);
        highp vec3 q = p + sunDir * ((base + 20.0 - p.y) / sunDir.y);
        highp vec2 uv = fract((q.xz / 4.0 + off) / 256.0);
        lit += texture(Sampler2, uv).r > 0.5 ? 0.0 : 1.0;
    }
    return lit / 14.0;
}

highp vec3 hue(highp float h) {
    return clamp(abs(fract(vec3(h) + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

// ---------------------------------------------------------------- main
void main() {
    highp vec3 dir = normalize(skyDir);
    highp vec3 sunDir = TextureMat[0].xyz;
    highp float tm = TextureMat[0].w;
    highp float day = TextureMat[1].x;
    highp float gold = TextureMat[1].y;
    highp float night = TextureMat[1].z;
    highp float rain = TextureMat[1].w;
    highp vec2 cloudOff = TextureMat[2].xy;
    highp float auroraStrength = TextureMat[2].w;
    int flags = int(TextureMat[3].x + 0.5);
    highp float coverAdj = TextureMat[3].y;
    highp float shootPeriod = TextureMat[3].z;
    highp float mode = TextureMat[3].w;
    highp float rise = ColorModulator.x;
    highp float org = ColorModulator.w;
    highp float milkyStrength = ColorModulator.y;
    int layers = int(round(ColorModulator.z));

    if (mode > 0.5) {
        highp vec4 k = tflag(F_NOPAINT) ? vec4(0.0) : clouds(dir, sunDir, day, gold, org, rain, cloudOff, coverAdj, layers);
        if (tflag(F_CIRRUS)) {
            highp vec4 ci = cirrus(dir, day, gold, TextureMat[1].z, org, cloudOff);
            k = vec4(mix(ci.rgb, k.rgb, k.a), max(k.a, ci.a));
        }
        if (tflag(F_RAYS3D) && k.a < 0.5) {
            highp float sk = pow(max(dot(dir, sunDir), 0.0), 2.0) * (0.25 + 0.4 * gold) * max(day, gold) * (1.0 - rain);
            if (sk > 0.01) {
                highp float lit = rays3d(dir, sunDir);
                highp vec3 rc = mix(vec3(1.0, 0.97, 0.88), mix(vec3(1.0, 0.74, 0.70), vec3(1.0, 0.80, 0.45), org), gold);
                k = vec4(mix(rc, k.rgb, k.a), max(k.a, lit * sk * 0.5));
            }
        }
        if ((flags & F_TOWERS) != 0) {
            highp vec4 tw = towers(dir, sunDir, day, gold, night, rain, org, ModelOffset.y, ModelOffset.z);
            // the far, low painted clouds sit behind the towers; high ones (overhead) pass in front
            highp float front = smoothstep(0.30, 0.45, dir.y);
            highp vec4 over = vec4(mix(tw.rgb, k.rgb, k.a), max(k.a, tw.a));
            highp vec4 under = vec4(mix(k.rgb, tw.rgb, tw.a), max(k.a, tw.a));
            k = mix(under, over, front);
        }
        if ((flags & F_MOUNTAINS) != 0 && dir.y < 0.26) {
            highp vec3 hs = landHeights(atan(dir.z, dir.x));
            if (dir.y < max(hs.x, max(hs.y, hs.z))) {
                k = vec4(0.0); // painted clouds sit behind the painted mountains
            }
        }
        highp float sd = dot(dir, sunDir);
        highp float rbowK = TextureMat[2].z;
        if ((flags & F_GODRAYS) != 0 && k.a < 0.5 && (gold * day > 0.05 || rbowK > 0.05) && sd > 0.35 && dir.y > 0.0 && layers != 0) {
            // beams of warm light where the sky between this pixel and the sun is free of clouds
            highp float lightK = shafts(dir, sunDir, cloudOff, COV - coverAdj - 0.12 * rain);
            highp float ray = pow((sd - 0.35) / 0.65, 2.5) * lightK * max(gold * day, rbowK * 0.6) * (1.0 - rain * 0.7);
            highp vec3 rc = mix(vec3(1.0, 0.86, 0.62), vec3(1.0, 0.93, 0.70), org);
            highp vec4 r = vec4(rc, ray * 0.45);
            k = vec4(mix(r.rgb, k.rgb, k.a), max(k.a, r.a));
        }
        k.rgb += vec3(0.55, 0.58, 0.72) * ModelOffset.x * k.a * 0.6; // lightning lights the clouds from inside
        if (k.a < 0.004) {
            discard;
        }
        fragColor = k;
        return;
    }

    highp vec3 col = mix(skyNight(dir.y), skyDay(dir.y), day);
    // golden hour creeps down from... up from the horizon: the low sky turns first, the zenith last.
    // where blue and the warm colours meet, lift toward cream so the mix never turns grey/khaki
    highp float wv = gold >= 0.999 ? 1.0 : sstep(0.0, 1.0, gold * 2.0 - max(dir.y, 0.0));
    col = mix(col, skyEvent(dir, sunDir, rise, org), wv);
    col += vec3(0.16, 0.13, 0.07) * wv * (1.0 - wv) * 4.0 * day;

    highp float sd = max(dot(dir, sunDir), 0.0);
    highp float clear = 1.0 - rain;
    // sun glow: soft by day, wide and warm at golden hour
    col += vec3(1.0, 0.92, 0.75) * (pow(sd, 6.0) * 0.10 + pow(sd, 36.0) * 0.16) * day * (1.0 - gold) * clear;
    col += mix(vec3(1.0, 0.72, 0.38), vec3(1.0, 0.85, 0.45), org) * (pow(sd, 12.0) * 0.55 + pow(sd, 90.0) * 0.6) * gold * clear;
    // moon glow
    col += vec3(0.45, 0.65, 1.00) * pow(max(dot(dir, -sunDir), 0.0), 20.0) * 0.12 * night * clear;

    if ((flags & F_RAYS) != 0 && sd > 0.08 && gold * day > 0.02) {
        highp vec3 upv = abs(sunDir.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
        highp vec3 rt = normalize(cross(sunDir, upv));
        highp vec3 up2 = cross(rt, sunDir);
        highp float ang = atan(dot(dir, up2), dot(dir, rt));
        highp float streak = noise(vec2(ang * 8.0 + tm * 0.03, 2.0));
        highp float ray = pow(sd, 6.0) * sstep(0.35, 0.9, streak);
        col += mix(vec3(1.0, 0.85, 0.65), vec3(1.0, 0.55, 0.40), gold) * ray * (0.06 + 0.20 * gold) * day * clear;
    }

    if (night > 0.01 && dir.y > -0.03) {
        highp float vis = night * clear * sstep(0.0, 0.12, dir.y);
        highp float visLow = night * clear * sstep(0.02, 0.08, dir.y);
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

    highp float rbow = TextureMat[2].z;
    if (rbow > 0.01 && dir.y > 0.0) {
        highp float ang = acos(clamp(dot(dir, -sunDir), -1.0, 1.0));
        highp float x = (ang - 0.700) / 0.042; // 0 = violet (inner), 1 = red (outer)
        highp float band = smoothstep(-0.25, 0.1, x) * (1.0 - smoothstep(0.9, 1.25, x));
        if (band > 0.0) {
            highp vec3 rc = mix(vec3(1.0), hue((1.0 - clamp(x, 0.0, 1.0)) * 0.78), 0.55);
            col = mix(col, rc, band * rbow * 0.32 * smoothstep(0.0, 0.15, dir.y) * day);
        }
    }

    // rain: grey, flat sky
    highp float lum = dot(col, vec3(0.30, 0.59, 0.11));
    col = mix(col, vec3(lum * 0.92, lum * 0.95, lum * 1.02), rain * 0.80);

    fragColor = vec4(col, 1.0);
}
