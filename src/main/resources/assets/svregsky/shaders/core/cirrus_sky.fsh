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
const int F_HILLWIND = 131072;
const int F_TRAIN = 262144;
const int F_BIGMOON = 524288;
const int F_VILLAGE = 1048576;
const int F_BIRDS = 2097152;
const int F_AIRSHIP = 4194304;
const int F_FALLS = 8388608;

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

// set in main(): sky time (seconds, wraps every hour) and the menu toggles
highp float tmLand;
highp float windWaves, trainOn, featBirds, featVillage;
const highp float TAU = 6.2831853;
highp float featAir, featFalls, featLake;

highp float wrapA(highp float x) {
    return atan(sin(x), cos(x));
}

highp float segD(highp vec2 p, highp vec2 a, highp vec2 b) {
    highp vec2 pa = p - a, ba = b - a;
    highp float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}


// ---------------------------------------------------------------- stage-2 terrain bits used by the heights
// a flat-topped cliff (mesa) in the far range with a waterfall pouring off its lip
const highp float FALLS_A = 0.45;

highp float mesa(highp float a) {
    highp float d = abs(wrapA(a - FALLS_A));
    return 0.07 * (1.0 - smoothstep(0.055, 0.07, d)) + 0.02 * (1.0 - smoothstep(0.07, 0.12, d));
}

// wind sweeping over the grass of the hills: bright bands travelling along the slopes
highp float hillWind(highp float a, highp float y, highp float seed) {
    highp float ph = a * 11.0 - mod(tmLand * 0.349066, TAU) + noise(vec2(a * 3.0 + seed, y * 30.0)) * 2.5 + y * 40.0;
    return smoothstep(0.75, 0.93, sin(mod(ph, TAU)) * 0.5 + 0.5) * windWaves;
}

// a little train running along the foot of the far hills: cars, a smoking engine, lit windows at night
highp vec4 train(highp float a, highp float y0, highp float night, highp float gold) {
    if (trainOn < 0.5) {
        return vec4(0.0);
    }
    highp float ta = mod(tmLand * 0.0122173, TAU);         // engine azimuth, 7 loops an hour
    highp float d = atan(sin(a - ta), cos(a - ta));           // >0 ahead of the engine
    // the track runs along the near ridge (without the tree crowns), the train is drawn in front of it
    highp float yT = 0.007 + 0.032 * ridge(ta + 4.1, 5.0, 3.0, 0.05) - 0.001;
    highp float y = y0;
    highp float len = 0.16;
    highp vec3 body = mix(vec3(0.20, 0.24, 0.34), vec3(0.30, 0.20, 0.24), gold);
    body = mix(body, vec3(0.04, 0.05, 0.10), night);
    highp float c = 0.0;
    highp vec3 col = body;
    if (d < 0.0 && d > -len && y > yT && y < yT + 0.016) {
        highp float car = fract(-d / 0.031);
        c = step(0.08, car);                                  // gaps between cars
        // windows: a row of small squares, warm at night
        highp float wx = fract(-d / 0.0062);
        highp float win = step(0.3, wx) * step(wx, 0.75) * step(yT + 0.0075, y) * step(y, yT + 0.0125) * step(0.15, car) * step(car, 0.95);
        col = mix(body, mix(body * 1.4, vec3(1.0, 0.82, 0.45), night), win);
    }
    // engine: a bit taller with a chimney
    if (d >= 0.0 && d < 0.024 && y > yT && y < yT + 0.018 + step(0.011, d) * step(d, 0.016) * 0.007) {
        c = 1.0;
        col = body * 0.85;
    }
    // smoke puffs drifting back over the train
    for (int i = 0; i < 6; i++) {
        highp float fi = float(i);
        highp vec2 pc = vec2(0.0135 - fi * 0.018, yT + 0.029 + fi * 0.005);
        highp float r = 0.005 + fi * 0.002;
        highp float sd = length(vec2(d, y) - pc);
        if (sd < r) {
            c = max(c, 0.85 - fi * 0.11);
            col = mix(vec3(0.95, 0.96, 1.0), vec3(1.0, 0.86, 0.76), gold) * (1.0 - night * 0.65);
        }
    }
    return vec4(col, c);
}

// ---------------------------------------------------------------- painted Ghibli hills v2
// layers: far range with occasional sharp spires (snow on the tallest), rounded mid hills, a near ridge with round tree crowns.
// Each layer is toon-lit in two tones by which way its slope faces the sun, and melts into the haze toward its base.
highp float spires(highp float a) {
    highp float k = a * 9.0;
    highp float i = floor(k), f = fract(k) - 0.5;
    highp float h = hash2(vec2(i, 17.0));
    highp float w = 0.28 + 0.2 * hash2(vec2(i, 5.0));
    highp float hh = (0.06 + 0.07 * hash2(vec2(i, 9.0))) * step(0.93, h);
    return hh * pow(max(0.0, 1.0 - abs(f - (hash2(vec2(i, 3.0)) - 0.5) * 0.3) / w), 1.8);
}

highp float featPines;

// tall pointed pine: a tiered triangle (zigzag edges), f = -1..1 across the tree
highp float pineShape(highp float f, highp float H) {
    highp float hb = H * max(0.0, 1.0 - abs(f) * 1.05);
    return max(0.0, hb - H * 0.10 * fract(hb / H * 3.5)) + H * 0.08 * step(abs(f), 0.06); // + a thin trunk tip
}

highp float crowns(highp float a) {
    highp float k = a * 48.0;
    highp float i = floor(k), f = fract(k) * 2.0 - 1.0;
    highp float t = hash2(vec2(i, 23.0));
    highp float r = (0.004 + 0.006 * hash2(vec2(i, 21.0))) * step(0.25, t);
    highp float round_ = r * sqrt(max(0.0, 1.0 - f * f * 0.9));
    // pines: some cells grow a tall pointed pine instead (two thin ones when the cell is a pair)
    highp float isPine = step(0.62, t) * featPines;
    highp float H = 0.012 + 0.012 * hash2(vec2(i, 27.0));
    highp float pine = hash2(vec2(i, 29.0)) < 0.5 ? pineShape(f * 1.3, H) : max(pineShape(f * 2.4 + 1.1, H * 0.85), pineShape(f * 2.4 - 1.1, H));
    return mix(round_, pine, isPine);
}

// is this spot (just under the near ridge line) inside a pine tree? (for darker colouring)
highp float pineHere(highp float a) {
    highp float k = a * 48.0;
    highp float i = floor(k);
    return step(0.62, hash2(vec2(i, 23.0))) * featPines;
}

highp float landMid(highp float a);

// little pine forests on the slopes of the mid hills: a dark wooded patch with rows of toon pine tops sticking out
highp vec4 midForest(highp float a, highp float y, highp float h2, highp float sideK, highp float gold, highp float night, highp float rain, highp vec3 hill) {
    if (featPines < 0.5 || y > h2 + 0.02) {
        return vec4(0.0);
    }
    highp float patchK = smoothstep(0.62, 0.7, noise(vec2(a * 9.0, 7.0)));
    if (patchK <= 0.0) {
        return vec4(0.0);
    }
    // forest tones derived from the hill colour: a deeper, bluer green, lit side a bit lighter
    highp vec3 dark = hill * vec3(0.62, 0.72, 0.80);
    highp vec3 lit = hill * vec3(0.82, 0.92, 0.92);
    highp float litK = (0.3 + 0.7 * sideK) * (1.0 - rain);
    // the wooded band hugs the slope from the ridge down
    highp float top = h2 - 0.004;
    highp float depth = (0.012 + 0.012 * noise(vec2(a * 30.0, 3.0))) * patchK + 0.004 * (noise(vec2(a * 140.0, 5.0)) - 0.5);
    highp vec4 o = vec4(0.0);
    if (y < top && y > top - depth) {
        o = vec4(dark, 1.0);
    }
    // rows of pine tops along the upper edge and inside the band
    for (int row = 0; row < 4; row++) {
        highp float fr = float(row);
        highp float freq = 75.0 - fr * 6.0;
        highp float k = a * freq + fr * 0.41;
        highp float i = floor(k), f = fract(k) * 2.0 - 1.0;
        if (hash2(vec2(i, 41.0 + fr)) > 0.85) {
            continue;
        }
        highp float ca = (i + 0.5 - fr * 0.41) / freq;
        highp float base = landMid(ca) - 0.005 - fr * 0.006;
        highp float H = 0.009 + 0.004 * hash2(vec2(i, 43.0 + fr));
        highp float ty = y - base;
        if (ty > 0.0 && ty < pineShape(f * 1.1, H) && hash2(vec2(i, 47.0 + fr)) < patchK) {
            o = vec4(mix(dark, lit, step(0.0, f) * litK), 1.0);
        }
    }
    return o;
}

// one big round green hill (Totoro-like) in a fixed direction
highp float hero(highp float a) {
    highp float d = abs(atan(sin(a - 2.3), cos(a - 2.3)));
    return 0.11 * pow(max(0.0, 1.0 - d / 0.42), 0.6) * smoothstep(0.42, 0.25, d) + 0.035 * max(0.0, 1.0 - d / 0.42);
}

highp float landFar(highp float a) {
    return 0.04 + 0.28 * pow(ridge(a, 2.0, 1.0, 0.85), 1.7) + spires(a) + mesa(a);
}

highp float landMid(highp float a) {
    return 0.022 + 0.14 * pow(ridge(a + 1.7, 3.0, 2.0, 0.0), 1.25) + hero(a);
}

highp float nearBase(highp float a);

highp float landNear(highp float a) {
    return nearBase(a) + crowns(a) * smoothstep(0.05, 0.08, abs(atan(sin(a - 3.4), cos(a - 3.4)))) * smoothstep(0.03, 0.05, abs(atan(sin(a - 5.9), cos(a - 5.9)))) * (1.0 - featLake * (1.0 - smoothstep(0.30, 0.40, abs(atan(sin(a - 1.2), cos(a - 1.2))))));
}

highp float nearBase(highp float a) {
    highp float flat1 = 1.0 - smoothstep(0.08, 0.16, abs(atan(sin(a - 3.4), cos(a - 3.4))));
    highp float flat2 = featLake * (1.0 - smoothstep(0.30, 0.45, abs(atan(sin(a - 1.2), cos(a - 1.2)))));
    return 0.007 * (1.0 - flat2 * 0.8) + 0.032 * ridge(a + 4.1, 5.0, 3.0, 0.05) * (1.0 - 0.6 * flat1) * (1.0 - 0.9 * flat2);
}

highp vec3 landHeights(highp float a) {
    return vec3(landFar(a), landMid(a), landNear(a));
}

// painted toon tone for one layer: a lit rim along the top (stronger on the sun side), broad brush-like shadow patches lower down
highp vec3 paintLayer(highp vec3 base, highp float a, highp float y, highp float h, highp float rim, highp float seed, highp float sideK, highp float lightK, highp float gold) {
    highp vec3 lit = base * 1.2 + mix(vec3(0.08, 0.08, -0.02), vec3(0.14, 0.06, 0.0), gold);
    highp vec3 shade = base * 0.82 + vec3(0.0, 0.015, 0.05);
    highp float depth = (h - y) / max(h, 1e-3);
    highp float patchN = noise(vec2(a * 7.0 + seed, depth * 3.0 + seed * 2.0)) * 0.7 + noise(vec2(a * 19.0 + seed, depth * 7.0)) * 0.3;
    highp vec3 c = mix(base, shade, step(0.58, patchN) * step(0.18, depth) * lightK);
    c = mix(c, lit, step(y, h) * step(h - rim, y) * (0.35 + 0.65 * sideK) * lightK);
    // wind sweeping the grass (near and mid hills only: seed > 3)
    c = mix(c, lit * 1.12 + vec3(0.03, 0.04, 0.0), hillWind(a, y, seed) * step(3.0, seed) * step(0.06, depth) * lightK * 0.85);
    // shadows of passing clouds drift across the slopes
    highp vec2 q = vec2(a * 9.0 - mod(tmLand * 0.02, 500.0) + seed, y * 70.0 + seed * 1.7);
    highp float cs = noise(q) * 0.7 + noise(q * 2.3 + 5.0) * 0.3;
    return mix(c, c * vec3(0.74, 0.80, 0.93), step(0.64, cs) * step(0.1, depth) * lightK * 0.9);
}


// a V of birds gliding along the horizon, wings flapping
highp float birds(highp float a, highp float y) {
    if (featBirds < 0.5) {
        return 0.0;
    }
    highp float fa = mod(tmLand * 0.0157080, TAU) + 1.0, fy = 0.085 + 0.015 * sin(mod(tmLand * 0.0506, TAU));
    highp float m = 0.0;
    for (int i = 0; i < 9; i++) {
        highp float k = float((i + 1) / 2);
        highp float side = mod(float(i), 2.0) < 0.5 ? 1.0 : -1.0;
        highp vec2 c = vec2(fa - k * 0.026, fy + side * k * 0.013);
        highp vec2 p = vec2(wrapA(a - c.x), y - c.y);
        if (abs(p.x) > 0.02 || abs(p.y) > 0.02) {
            continue;
        }
        highp float flap = sin(mod(tmLand * 7.0, TAU) + float(i) * 1.7) * 0.005;
        highp float d = min(segD(p, vec2(-0.0095, flap), vec2(0.0, -0.001)), segD(p, vec2(0.0, -0.001), vec2(0.0095, flap)));
        m = max(m, 1.0 - smoothstep(0.0009, 0.0015, d));
    }
    return m;
}




// a little village on the near ridge: houses with coloured roofs, a turning windmill, chimney smoke, lit windows at night
highp vec4 village(highp float a, highp float y0, highp float night, highp float gold) {
    highp float y = y0;
    if (featVillage < 0.5) {
        return vec4(0.0);
    }
    highp float d = wrapA(a - 3.4) / 1.6;
    if (abs(d) > 0.09 || y > 0.12) {
        return vec4(0.0);
    }
    highp float yb = nearBase(a) - 0.0015;
    y = yb + (y - yb) / 1.6;
    highp vec3 wall = mix(vec3(0.95, 0.92, 0.84), vec3(1.0, 0.82, 0.66), gold);
    wall = mix(wall, vec3(0.10, 0.11, 0.20), night);
    for (int i = 0; i < 6; i++) {
        highp float fi = float(i);
        highp float cx = (fi - 2.5) * 0.0145 + (hash2(vec2(fi, 4.0)) - 0.5) * 0.004;
        highp float hw = 0.0042 + 0.0018 * hash2(vec2(fi, 6.0));
        highp float wh = 0.0055 + 0.0025 * hash2(vec2(fi, 8.0));
        highp float px = d - cx, py = y - yb;
        highp vec3 roof = hash2(vec2(fi, 2.0)) < 0.4 ? vec3(0.80, 0.30, 0.22) : (hash2(vec2(fi, 2.0)) < 0.75 ? vec3(0.25, 0.50, 0.55) : vec3(0.85, 0.55, 0.25));
        roof = mix(roof, vec3(0.07, 0.07, 0.14), night);
        if (abs(px) < hw && py > 0.0 && py < wh) {
            highp float win = step(abs(px), hw * 0.45) * step(wh * 0.35, py) * step(py, wh * 0.7) * step(0.5, fract(px / hw * 1.5));
            return vec4(mix(wall, vec3(1.0, 0.78, 0.40), win * night), 1.0);
        }
        if (py >= wh && py < wh + (hw * 1.25 - abs(px)) * 0.95 && abs(px) < hw * 1.25) {
            return vec4(roof, 1.0);
        }
        if (i == 2 && abs(px - hw * 0.5) < 0.0009 && py > wh && py < wh + 0.0075) {
            return vec4(roof * 0.7, 1.0);
        }
        if (i == 2) {
            for (int j = 0; j < 4; j++) {
                highp float fj = float(j);
                highp vec2 sc = vec2(hw * 0.5 - fj * 0.0035 - mod(tmLand * 0.004, 0.0035), wh + 0.009 + fj * 0.0028);
                if (length(vec2(px, py) - sc) < 0.0014 + fj * 0.0006) {
                    return vec4(mix(vec3(0.96), vec3(0.4, 0.42, 0.5), night), 0.8 - fj * 0.15);
                }
            }
        }
    }
    // windmill
    highp vec2 wp = vec2(d - 0.062, y - yb);
    if (wp.y > 0.0 && wp.y < 0.017 && abs(wp.x) < 0.0035 - wp.y * 0.1) {
        return vec4(wall * 0.92, 1.0);
    }
    highp vec2 hub = wp - vec2(0.0, 0.017);
    highp float ang = mod(tmLand * 0.7994, TAU);
    highp float bl = 1.0;
    for (int k = 0; k < 4; k++) {
        highp float t = ang + float(k) * 1.5708;
        bl = min(bl, segD(hub, vec2(0.0), vec2(cos(t), sin(t)) * 0.012));
    }
    if (bl < 0.0011 && length(hub) < 0.0125) {
        return vec4(mix(vec3(0.45, 0.32, 0.22), vec3(0.06, 0.06, 0.12), night), 1.0);
    }
    return vec4(0.0);
}

// ---------------------------------------------------------------- stage 2: airship & balloons, waterfall, far shower, valley lake

// a Ghibli-style airship: a long toon-shaded envelope with seams, tail fins, a spinning propeller and a gondola with windows
highp vec4 airship(highp vec2 p, highp float day, highp float gold, highp float night) {
    highp float rx = 0.050, ry = 0.0155;
    if (abs(p.x) > 0.075 || abs(p.y) > 0.035) {
        return vec4(0.0);
    }
    highp vec3 lit = mix(vec3(0.97, 0.93, 0.83), vec3(1.0, 0.84, 0.66), gold);
    highp vec3 midC = mix(vec3(0.84, 0.82, 0.80), vec3(0.88, 0.66, 0.60), gold);
    highp vec3 shd = mix(vec3(0.62, 0.64, 0.74), vec3(0.58, 0.42, 0.52), gold);
    highp vec3 dark = mix(vec3(0.36, 0.30, 0.30), vec3(0.32, 0.22, 0.24), gold);
    lit = mix(lit, vec3(0.16, 0.18, 0.30), night);
    midC = mix(midC, vec3(0.12, 0.14, 0.24), night);
    shd = mix(shd, vec3(0.08, 0.09, 0.17), night);
    dark = mix(dark, vec3(0.05, 0.05, 0.10), night);
    // envelope: rounder nose, slimmer tail
    highp float sx = p.x > 0.0 ? rx : rx * 1.08;
    highp float e = pow(abs(p.x) / sx, 2.2) + (p.y * p.y) / (ry * ry);
    if (e < 1.0) {
        highp float v = p.y / ry;
        highp vec3 c = v > 0.35 ? lit : (v > -0.35 ? midC : shd);
        c = mix(c, c * 0.86, step(0.86, fract(p.x / 0.0125 + 0.5)) * step(-0.6, v)); // panel seams
        c = mix(c, lit * 1.04, step(0.93, e) * step(0.2, v));                        // rim light along the top
        c = mix(c, vec3(0.75, 0.25, 0.22) * (1.0 - night * 0.8), step(abs(p.x - rx * 0.55), 0.0035) * step(abs(v), 0.55)); // a red band
        return vec4(c, 1.0);
    }
    // tail fins (top, bottom) and the side fin seen edge-on
    highp float tx = -p.x - rx * 0.78;
    if (tx > 0.0 && tx < 0.022) {
        highp float k = tx / 0.022;
        if (p.y > 0.0 && p.y < ry * 0.6 + k * 0.012 && p.y > ry * 0.6 * (1.0 - k) - 0.004) {
            return vec4(mix(dark, vec3(0.8, 0.3, 0.25) * (1.0 - night), 0.4), 1.0);
        }
        if (p.y < 0.0 && -p.y < ry * 0.6 + k * 0.010 && -p.y > ry * 0.6 * (1.0 - k) - 0.004) {
            return vec4(dark, 1.0);
        }
        if (abs(p.y) < 0.0012 + k * 0.0012) {
            return vec4(dark * 0.9, 1.0);
        }
    }
    // propeller: a blurred spinning disc behind the tail
    highp vec2 pp = p - vec2(-rx * 1.08 - 0.022 - 0.002, 0.0);
    if (abs(pp.x) < 0.0012 && abs(pp.y) < 0.008) {
        highp float spin = abs(sin(mod(tmLand * 23.0, TAU) + pp.y * 300.0));
        return vec4(dark, 0.35 + 0.45 * spin);
    }
    // gondola with a row of windows, hung on two struts
    highp vec2 g = p - vec2(0.004, -ry - 0.0068);
    if (abs(g.x) < 0.013 && abs(g.y) < 0.0034) {
        highp float win = step(0.45, fract(g.x / 0.0042)) * step(abs(g.y), 0.0013) * step(abs(g.x), 0.011);
        highp vec3 c = g.y > 0.0012 ? midC : shd;
        return vec4(mix(c, mix(vec3(0.30, 0.34, 0.45), vec3(1.0, 0.80, 0.42), night), win), 1.0);
    }
    if (p.y < -ry * 0.9 && p.y > -ry - 0.0035 && (abs(p.x + 0.004) < 0.0007 || abs(p.x - 0.012) < 0.0007)) {
        return vec4(dark, 1.0);
    }
    // a blinking red light on top at night
    highp float blink = step(0.5, fract(tmLand * 0.7));
    if (length(p - vec2(0.0, ry + 0.0012)) < 0.0016 && night > 0.3) {
        return vec4(vec3(1.0, 0.25, 0.2), blink);
    }
    return vec4(0.0);
}

// a hot-air balloon: striped teardrop, ropes and a wicker basket
highp vec4 balloon(highp vec2 q, highp float r, highp vec3 c1, highp vec3 c2, highp float gold, highp float night) {
    if (abs(q.x) > r * 1.3 || q.y > r * 1.2 || q.y < -r * 2.4) {
        return vec4(0.0);
    }
    highp float w = q.y > 0.0 ? sqrt(max(0.0, r * r - q.y * q.y)) : r * max(0.0, 1.0 - (-q.y) / (r * 1.25)) * (1.0 + 0.25 * (-q.y) / r) * (q.y > -r * 0.6 ? 1.0 : 0.95);
    if (q.y > -r * 1.25 && abs(q.x) < w) {
        highp float u = q.x / max(w, 1e-4);
        highp vec3 c = mix(c1, c2, step(0.5, fract(u * 1.6 + 0.5)));
        c *= u > 0.35 ? 0.80 : (u < -0.2 ? 1.08 : 1.0);           // toon: shaded side, lit side
        c = mix(c, c * 1.15, step(0.75, q.y / r));                 // highlight cap
        c = mix(c, mix(c, vec3(1.0, 0.7, 0.5), 0.3), gold);
        return vec4(mix(c, vec3(0.10, 0.10, 0.18), night * 0.85), 1.0);
    }
    highp float by = q.y + r * 1.85;
    if (abs(by) < r * 0.22 && abs(q.x) < r * 0.28) {
        return vec4(mix(vec3(0.50, 0.33, 0.20), vec3(0.06, 0.06, 0.10), night), 1.0);
    }
    if (q.y < -r * 1.2 && q.y > -r * 1.65 && (abs(q.x - r * 0.25) < r * 0.05 || abs(q.x + r * 0.25) < r * 0.05)) {
        return vec4(mix(vec3(0.35, 0.28, 0.22), vec3(0.05, 0.05, 0.1), night), 1.0);
    }
    return vec4(0.0);
}

highp vec4 airships(highp float a, highp float y, highp float day, highp float gold, highp float night) {
    if (featAir < 0.5) {
        return vec4(0.0);
    }
    // the airship crosses the whole horizon about twice an hour, bobbing gently
    highp float aa = mod(tmLand * 0.0034907, TAU) + 4.0;
    highp vec2 p = vec2(wrapA(a - aa), y - 0.15 - 0.003 * sin(mod(tmLand * 0.31, TAU)));
    highp vec4 s = airship(p, day, gold, night);
    if (s.a > 0.0) {
        return s;
    }
    highp vec2 q1 = vec2(wrapA(a - (mod(tmLand * 0.0017453, TAU) + 1.9)), y - 0.11 - 0.004 * sin(mod(tmLand * 0.2, TAU)));
    s = balloon(q1, 0.0115, vec3(0.92, 0.32, 0.26), vec3(0.98, 0.84, 0.36), gold, night);
    if (s.a > 0.0) {
        return s;
    }
    highp vec2 q2 = vec2(wrapA(a - (mod(tmLand * 0.0026180, TAU) + 4.5)), y - 0.17 - 0.004 * sin(mod(tmLand * 0.17 + 2.0, TAU)));
    return balloon(q2, 0.0085, vec3(0.30, 0.58, 0.86), vec3(0.96, 0.96, 0.92), gold, night);
}

highp vec4 waterfall(highp float a, highp float y, highp float h1, highp float hMid, highp float gold, highp float night) {
    if (featFalls < 0.5) {
        return vec4(0.0);
    }
    highp float d = wrapA(a - FALLS_A);
    if (abs(d) > 0.05 || y > h1) {
        return vec4(0.0);
    }
    highp float lip = h1 - 0.006;
    highp float drop = max(0.0, lip - y);                   // how far below the lip
    highp vec3 rock = vec3(0.0);
    highp vec3 wc = mix(vec3(0.90, 0.96, 1.0), vec3(1.0, 0.88, 0.74), gold);
    wc = mix(wc, vec3(0.42, 0.48, 0.66), night);
    // the water arcs out a little off the lip, then falls straight, widening
    highp float cx = 0.0025 * (1.0 - exp(-drop * 80.0)) + 0.0004 * sin(y * 150.0);
    highp float w = 0.0018 + drop * 0.018;
    highp float x = d - cx;
    highp vec4 o = vec4(0.0);
    // dark wet rock behind the falls
    if (abs(x) < w * 1.7 && y < lip + 0.002) {
        o = vec4(vec3(0.0), 0.18 * (1.0 - night * 0.5));
    }
    if (abs(x) < w && y < lip + 0.0015) {
        highp float strand = noise(vec2(x / w * 3.0 + 7.0, 1.0));
        highp float flow = fract(y * 95.0 + mod(tmLand * 1.6, 100.0) + strand * 2.0);
        highp vec3 c = mix(wc * 0.78, wc, step(0.45, strand));               // two-tone strands
        c = mix(c, vec3(1.0), step(0.82, flow) * step(0.3, strand) * (1.0 - night * 0.6)); // white streaks running down
        c = mix(c, wc * 0.7, step(0.8, abs(x) / w));                         // darker edges
        o = vec4(c, 0.8);
    }
    // toon mist puffs where it disappears behind the mid hills
    highp float my = hMid + 0.006;
    for (int i = 0; i < 4; i++) {
        highp float fi = float(i);
        highp float ph = fract(tmLand * 0.15 + fi * 0.25);
        highp vec2 c = vec2(cx + (fi - 1.5) * 0.005, my + ph * 0.01);
        highp float r = 0.004 + 0.0025 * hash2(vec2(fi, 3.0)) - ph * 0.0015;
        if (length(vec2(d, y) - c) < r) {
            o = vec4(mix(vec3(1.0), vec3(0.5, 0.55, 0.7), night), 0.85 * (1.0 - ph * 0.6));
        }
    }
    return o;
}

// a far shower: a toon cumulonimbus base with slanted rain falling onto the hills, on some dry days
highp float curtainA, curtainK;

highp vec4 showerCloud(highp float a, highp float y, highp float gold, highp float night) {
    if (curtainK < 0.01) {
        return vec4(0.0);
    }
    highp float d = wrapA(a - curtainA);
    if (abs(d) > 0.4 || y < 0.09 || y > 0.32) {
        return vec4(0.0);
    }
    highp float yb = 0.115 + 0.006 * noise(vec2(a * 60.0, 3.0));   // ragged flat base
    // the body is a union of round heads, taller in the middle
    highp float top = -1.0;
    for (int i = 0; i < 7; i++) {
        highp float fi = float(i);
        highp float cx = (fi - 3.0) * 0.048 + (hash2(vec2(fi, 11.0)) - 0.5) * 0.02;
        highp float r = 0.065 + 0.035 * hash2(vec2(fi, 13.0)) - abs(fi - 3.0) * 0.012;
        highp float cy = yb + 0.02 + (3.0 - abs(fi - 3.0)) * 0.018;
        highp float dx = d - cx;
        if (abs(dx) < r) {
            top = max(top, cy + sqrt(r * r - dx * dx) * 0.85);
        }
    }
    if (y < yb || y > top) {
        return vec4(0.0);
    }
    highp vec3 light = mix(vec3(0.74, 0.77, 0.86), vec3(0.92, 0.70, 0.64), gold);
    highp vec3 shade = mix(vec3(0.50, 0.53, 0.63), vec3(0.54, 0.40, 0.48), gold);
    highp vec3 belly = mix(vec3(0.34, 0.37, 0.47), vec3(0.38, 0.28, 0.36), gold);
    highp float hk = (y - yb) / max(top - yb, 1e-3);
    highp vec3 c = hk > 0.8 ? light : (hk > 0.32 ? shade : belly);
    c = mix(c, light * 1.05, step(top - 0.004, y));                  // bright rim on the heads
    c = mix(c, vec3(0.10, 0.11, 0.20), night * 0.85);
    return vec4(c, curtainK);
}

// x = how much rain veils this spot, y = a thin light streak
highp vec2 showerRain(highp float a, highp float y) {
    if (curtainK < 0.01 || y > 0.12) {
        return vec2(0.0);
    }
    highp float d = wrapA(a - curtainA) + (0.12 - y) * 0.35;      // slanted by the wind
    highp float wid = 0.19 * (0.85 + 0.15 * noise(vec2(y * 25.0, 4.0)));
    highp float edge = 1.0 - smoothstep(wid * 0.6, wid, abs(d));
    highp float streak = step(0.78, fract((a + y * 0.35) * 160.0 + mod(tmLand * 0.6, 50.0) + noise(vec2(a * 40.0, y * 8.0)) * 3.0));
    highp float column = 0.7 + 0.3 * noise(vec2(a * 18.0, 2.0));
    return vec2(edge * column * curtainK, streak);
}

// a lake in the valley in front of the mid hills, mirroring them, with ripple glints and a light column toward the sun or moon
const highp float LAKE_A = 1.2;

highp float lakeTop(highp float a) {
    if (featLake < 0.5) {
        return 0.0;
    }
    highp float d = wrapA(a - LAKE_A);
    return 0.06 * sqrt(max(0.0, 1.0 - d * d / 0.16)) + (noise(vec2(a * 40.0, 2.0)) - 0.5) * 0.002;
}


// ---------------------------------------------------------------- stage 4: Ghibli icons
highp float featTree, featPoles, featMeadow, featTorii;
const highp float treeScale = 0.6;

// a huge lone camphor-like tree on top of the big round hill: thick trunk, a cloud of round leafy masses, toon lit
const highp float TREE_A = 2.3;
highp vec4 loneTree(highp float a, highp float y, highp float sideK, highp float gold, highp float night, highp float rain) {
    if (featTree < 0.5) {
        return vec4(0.0);
    }
    highp float d = wrapA(a - TREE_A);
    if (abs(d) > 0.16) {
        return vec4(0.0);
    }
    highp float base = landMid(TREE_A) - 0.004;
    highp vec2 p = vec2(d, y - base) / treeScale;
    if (p.y < -0.002 || p.y > 0.14) {
        return vec4(0.0);
    }
    highp vec3 leafD = mix(mix(vec3(0.14, 0.36, 0.22), vec3(0.36, 0.22, 0.28), gold), vec3(0.04, 0.06, 0.13), night);
    highp vec3 leafM = mix(mix(vec3(0.24, 0.52, 0.28), vec3(0.58, 0.36, 0.30), gold), vec3(0.06, 0.09, 0.17), night);
    highp vec3 leafL = mix(mix(vec3(0.46, 0.72, 0.36), vec3(0.95, 0.62, 0.40), gold), vec3(0.09, 0.12, 0.22), night);
    highp vec3 bark = mix(mix(vec3(0.30, 0.22, 0.18), vec3(0.32, 0.18, 0.18), gold), vec3(0.04, 0.04, 0.09), night);
    highp float sway = sin(mod(tmLand * 0.6, TAU)) * 0.0006;
    // canopy: overlapping round masses; light from the sun side and from above
    highp float best = -1.0;
    highp vec2 bc = vec2(0.0);
    highp float br = 0.0;
    for (int i = 0; i < 9; i++) {
        highp float fi = float(i);
        highp float ang = fi * 2.39996;
        highp float rr = 0.014 + 0.022 * sqrt(fi / 9.0);
        highp vec2 c = vec2(cos(ang) * rr * 1.5 + sway * (1.0 + fi * 0.2), 0.058 + sin(ang) * rr * 0.75 + (fi < 0.5 ? 0.012 : 0.0));
        highp float r = 0.024 - fi * 0.0009;
        highp float k = r - length(p - c);
        if (k > best) {
            best = k;
            bc = c;
            br = r;
        }
    }
    if (best > 0.0) {
        highp vec2 q = (p - bc) / br;
        highp float l = dot(normalize(q + vec2(0.0, 0.0001)), normalize(vec2(sideK - 0.5, 0.8)));
        highp vec3 c = l > 0.45 ? leafL : (l > -0.25 ? leafM : leafD);
        c = mix(c, leafD, step(best, 0.0012) * step(q.y, 0.2)); // dark contour under each mass
        c = mix(c, leafD, (1.0 - rain) * 0.0 + rain * 0.3);
        return vec4(c, 1.0);
    }
    // trunk with two spreading limbs
    highp float tw = 0.0042 + max(0.0, 0.012 - p.y) * 0.25;
    if (p.y < 0.05 && abs(p.x + p.y * 0.04) < tw) {
        return vec4(mix(bark, bark * 1.35, step(0.0, p.x) * sideK), 1.0);
    }
    if (segD(p, vec2(0.0, 0.03), vec2(-0.02, 0.05)) < 0.0018 || segD(p, vec2(0.0, 0.034), vec2(0.022, 0.052)) < 0.0016) {
        return vec4(bark, 1.0);
    }
    return vec4(0.0);
}

// wooden power poles with sagging wires along the near ridge (the Totoro countryside)
highp vec4 powerPoles(highp float a, highp float y, highp float gold, highp float night) {
    if (featPoles < 0.5) {
        return vec4(0.0);
    }
    highp float a0 = 4.15, a1 = 5.35, sp = 0.12;
    highp float da = wrapA(a - a0);
    if (da < -0.01 || da > a1 - a0 + 0.01) {
        return vec4(0.0);
    }
    highp vec3 wood = mix(mix(vec3(0.28, 0.22, 0.20), vec3(0.30, 0.18, 0.20), gold), vec3(0.03, 0.03, 0.08), night);
    highp float k = floor(da / sp + 0.5);
    highp float pa = a0 + k * sp;
    highp float x = wrapA(a - pa);
    highp float gb = nearBase(pa) - 0.001;
    highp float H = 0.042;
    // pole and cross-arm
    if (abs(x) < 0.0013 && y > gb && y < gb + H) {
        return vec4(wood, 1.0);
    }
    if (abs(x) < 0.0075 && abs(y - (gb + H - 0.004)) < 0.0011) {
        return vec4(wood, 1.0);
    }
    // two wires between this pole and the next, sagging in the middle
    highp float f = da / sp - floor(da / sp);
    highp float pA = a0 + floor(da / sp) * sp;
    highp float yA = nearBase(pA) - 0.001 + H - 0.004, yB = nearBase(pA + sp) - 0.001 + H - 0.004;
    if (pA + sp > a1 + 0.001) {
        return vec4(0.0);
    }
    for (int w = 0; w < 2; w++) {
        highp float off = float(w) * 0.0022;
        highp float yw = mix(yA, yB, f) - 4.0 * f * (1.0 - f) * 0.007 - off;
        if (abs(y - yw) < 0.0006) {
            return vec4(wood * 0.8, 0.9);
        }
    }
    return vec4(0.0);
}

// a meadow of tall grass and flowers on the near ridge, swaying with the wind
highp vec4 meadow(highp float a, highp float y, highp float h3, highp float gold, highp float night) {
    if (featMeadow < 0.5) {
        return vec4(0.0);
    }
    highp float patchK = smoothstep(0.45, 0.55, noise(vec2(a * 3.0, 11.0)));
    if (patchK <= 0.0) {
        return vec4(0.0);
    }
    highp float nb = nearBase(a);
    // grass blades poking above the ridge, bending with the wind wave
    highp float k = a * 900.0;
    highp float i = floor(k), f = fract(k);
    highp float bh = (0.0025 + 0.003 * hash2(vec2(i, 51.0))) * patchK;
    highp float bend = (0.4 + 0.6 * hillWind(a, nb, 9.0)) * 0.6;
    highp float ty = y - nb;
    highp float bx = abs(f - 0.5 - ty / max(bh, 1e-4) * bend * 0.5);
    highp vec3 grass = mix(mix(vec3(0.42, 0.66, 0.28), vec3(0.70, 0.50, 0.30), gold), vec3(0.05, 0.08, 0.14), night);
    if (ty > 0.0 && ty < bh && bx < 0.18 * (1.0 - ty / bh) + 0.05) {
        return vec4(grass, 1.0);
    }
    // flower dots scattered just below the ridge line
    if (ty < 0.0 && ty > -0.012) {
        highp vec2 g = vec2(a * 380.0, y * 600.0);
        highp vec2 gi = floor(g);
        highp float h = hash2(gi + 3.0);
        if (h > 1.0 - 0.16 * patchK && length(fract(g) - 0.5) < 0.38) {
            highp vec3 fc = h > 0.97 ? vec3(1.0, 0.95, 0.55) : (h > 0.94 ? vec3(1.0, 0.70, 0.78) : vec3(0.98, 0.98, 0.95));
            return vec4(mix(fc, vec3(0.2, 0.22, 0.35), night * 0.8), 0.9);
        }
    }
    return vec4(0.0);
}

// a small red torii gate on the near ridge
const highp float TORII_A = 5.9;
highp vec4 torii(highp float a, highp float y, highp float gold, highp float night) {
    if (featTorii < 0.5) {
        return vec4(0.0);
    }
    highp float x = wrapA(a - TORII_A) * 0.5;
    if (abs(x) > 0.02) {
        return vec4(0.0);
    }
    highp float gb = nearBase(TORII_A) - 0.001;
    highp float ty = (y - gb) * 0.5;
    highp vec3 red = mix(mix(vec3(0.85, 0.24, 0.16), vec3(0.95, 0.38, 0.22), gold), vec3(0.10, 0.05, 0.08), night);
    highp vec3 blk = mix(vec3(0.12, 0.10, 0.12), vec3(0.03, 0.03, 0.06), night);
    highp float H = 0.016;
    if (ty < 0.0 || ty > H + 0.004) {
        return vec4(0.0);
    }
    // posts (slightly leaning in)
    highp float px = abs(x) - (0.0075 - ty * 0.05);
    if (abs(px) < 0.0011 && ty < H) {
        return vec4(red, 1.0);
    }
    // nuki: lower straight beam
    if (abs(ty - H * 0.72) < 0.0007 && abs(x) < 0.0095) {
        return vec4(red, 1.0);
    }
    // kasagi: top beam, black cap, ends curving up
    highp float curve = x * x * 6.0;
    if (abs(ty - (H + curve)) < 0.0011 && abs(x) < 0.0135) {
        return vec4(ty > H + curve ? blk : red, 1.0);
    }
    if (abs(ty - (H + curve) - 0.0016) < 0.0006 && abs(x) < 0.014) {
        return vec4(blk, 1.0);
    }
    return vec4(0.0);
}

highp vec3 layerColor(int k, highp float day, highp float gold, highp float night, highp float org, highp float rain) {
    highp vec3 far = mix(vec3(0.12, 0.15, 0.34), vec3(0.47, 0.62, 0.86), day);
    highp vec3 mid = mix(vec3(0.08, 0.12, 0.26), vec3(0.30, 0.58, 0.46), day);
    highp vec3 near = mix(vec3(0.05, 0.08, 0.18), vec3(0.20, 0.50, 0.24), day);
    far = mix(far, mix(vec3(0.70, 0.44, 0.60), vec3(0.88, 0.58, 0.42), org), gold * 0.85);
    mid = mix(mid, mix(vec3(0.50, 0.32, 0.50), vec3(0.66, 0.42, 0.34), org), gold * 0.85);
    near = mix(near, mix(vec3(0.30, 0.20, 0.34), vec3(0.42, 0.28, 0.24), org), gold * 0.85);
    highp vec3 grey = vec3(dot(far, vec3(0.3, 0.59, 0.11)));
    far = mix(far, grey, rain * 0.6);
    mid = mix(mid, grey * 0.8, rain * 0.6);
    near = mix(near, grey * 0.6, rain * 0.6);
    return k == 0 ? far : (k == 1 ? mid : near);
}

// far range and mid hills
highp vec3 farMid(highp vec3 col, highp float a, highp float y, highp float sideK, highp float day, highp float gold, highp float night, highp float org, highp float rain) {
    highp float h1 = landFar(a), h2 = landMid(a);
    highp float lightK = (1.0 - rain) * (1.0 - night * 0.7);
    highp vec3 c = col;
    if (y < h1) {
        highp vec3 lc = paintLayer(layerColor(0, day, gold, night, org, rain), a, y, h1, 0.008, 1.0, sideK, lightK, gold);
        highp float tip = step(0.035, spires(a)) * step(h1 - 0.03, y);
        lc = mix(lc, mix(vec3(0.93, 0.96, 1.0), vec3(1.0, 0.88, 0.82), gold), tip * (1.0 - night) * (1.0 - rain) * 0.9);
        highp float mist = 0.55 * (1.0 - smoothstep(0.0, h1 * 0.85 + 0.02, y));
        c = mix(col, lc, 0.85 - mist);
        highp vec4 wf = waterfall(a, y, h1, h2, gold, night);
        c = mix(c, wf.rgb, wf.a);
    }
    // a far shower: its cloud and rain sit in front of the far range, the rain also veils the mid hills
    highp vec4 sc = showerCloud(a, y, gold, night);
    c = mix(c, sc.rgb, sc.a);
    highp vec2 rn = showerRain(a, y);
    highp vec3 rainC = mix(vec3(0.33, 0.36, 0.46), vec3(0.12, 0.13, 0.22), night);
    highp vec3 rainL = mix(vec3(0.70, 0.74, 0.82), vec3(0.30, 0.32, 0.45), night);
    c = mix(c, rainC, rn.x * 0.6);
    c = mix(c, rainL, rn.x * rn.y * 0.45);
    if (y < h2) {
        highp vec3 lc = paintLayer(layerColor(1, day, gold, night, org, rain), a, y, h2, 0.008, 4.0, sideK, lightK, gold);
        highp float mist = 0.25 * (1.0 - smoothstep(0.0, h2 * 0.8 + 0.01, y));
        c = mix(col, lc, 0.95 - mist);
        highp vec4 fo = vec4(0.0); // small mid-hill forests were dropped (they read as dark shrubs from afar)
        c = mix(c, fo.rgb, fo.a * (0.95 - mist));
        c = mix(c, rainC, rn.x * 0.4);
        c = mix(c, rainL, rn.x * rn.y * 0.3);
    }
    return c;
}

// the painted landscape above the horizon
highp vec3 landLayers(highp vec3 col, highp float a, highp float y, highp vec3 dir, highp vec3 sunDir, highp float day, highp float gold, highp float night, highp float org, highp float rain) {
    highp vec2 dxz = normalize(dir.xz + vec2(1e-5));
    highp vec2 sxz = normalize(sunDir.xz + vec2(1e-5));
    highp float sideK = pow(clamp(0.5 + 0.5 * dot(dxz, sxz), 0.0, 1.0), 1.5);
    highp float lightK = (1.0 - rain) * (1.0 - night * 0.7);
    highp vec3 c = farMid(col, a, y, sideK, day, gold, night, org, rain);
    highp float yL = lakeTop(a);
    if (yL > 0.002 && y < yL) {
        // mirror image of the far range and mid hills, broken by ripples
        highp float rip = (noise(vec2(a * 300.0, y * 1100.0 + mod(tmLand * 1.5, 1000.0))) - 0.5) * 0.0016;
        highp vec3 refl = farMid(col, a, 2.0 * yL - y + rip, sideK, day, gold, night, org, rain);
        highp vec3 deep = mix(mix(vec3(0.18, 0.42, 0.66), vec3(0.45, 0.30, 0.45), gold), vec3(0.04, 0.06, 0.14), night);
        highp float far = smoothstep(0.0, yL, y);                     // the far side mirrors more (fresnel)
        highp vec3 water = mix(deep, refl * vec3(0.85, 0.94, 1.0), 0.35 + 0.35 * far);
        // toon ripple bands: lighter horizontal strokes, denser toward the far side
        highp float band = step(0.68 - 0.1 * far, noise(vec2(a * 45.0 + mod(tmLand * 0.05, 100.0), y * 650.0)));
        water = mix(water, mix(water * 1.25 + vec3(0.05, 0.07, 0.09), water * 1.1, night), band * 0.7 * (1.0 - rain));
        // light column on the water toward the sun (day, sunset) or the moon (night)
        highp vec3 ld = night > 0.5 ? -sunDir : sunDir;
        highp float la = atan(ld.z, ld.x);
        highp float colK = (1.0 - smoothstep(0.012, 0.03 + (yL - y) * 0.6, abs(wrapA(a - la)))) * step(-0.05, ld.y) * (1.0 - rain);
        highp float dash = step(0.55, noise(vec2(a * 260.0, y * 1600.0 + mod(tmLand * 2.0, 1000.0))));
        highp vec3 glow = mix(mix(vec3(1.0, 0.98, 0.9), vec3(1.0, 0.75, 0.45), gold), vec3(0.95, 0.92, 0.8), night);
        water = mix(water, glow, colK * dash * 0.85);
        // scattered sparkle dashes elsewhere (day only)
        water = mix(water, vec3(1.0), step(0.965, noise(vec2(a * 520.0, y * 2600.0 + mod(tmLand * 2.0, 1000.0)))) * (1.0 - night) * (1.0 - rain) * 0.6);
        // the far shoreline: a bright line of light
        water = mix(water, mix(vec3(0.97, 0.98, 1.0), vec3(0.45, 0.5, 0.65), night), step(yL - 0.0011, y) * 0.8);
        c = water;
    }
    highp vec4 lt = loneTree(a, y, sideK, gold, night, rain);
    c = mix(c, lt.rgb, lt.a);
    highp float h3 = landNear(a);
    highp vec4 tg = torii(a, y, gold, night);
    c = mix(c, tg.rgb, tg.a);
    highp vec4 pw = powerPoles(a, y, gold, night);
    c = mix(c, pw.rgb, pw.a);
    if (y < h3) {
        highp vec3 lc = paintLayer(layerColor(2, day, gold, night, org, rain), a, y, h3, 0.003, 9.0, sideK, lightK, gold);
        // the tall pines on the ridge: darker blue-green, lit on the sun side
        if (pineHere(a) > 0.5 && y > nearBase(a) + 0.0015) {
            highp vec3 pd = mix(mix(vec3(0.10, 0.28, 0.22), vec3(0.30, 0.18, 0.26), gold), vec3(0.03, 0.05, 0.11), night);
            highp vec3 pl = mix(mix(vec3(0.20, 0.43, 0.30), vec3(0.48, 0.30, 0.30), gold), vec3(0.05, 0.08, 0.16), night);
            highp float fx = fract(a * 48.0) * 2.0 - 1.0;
            lc = mix(pd, pl, step(0.0, fx) * (0.3 + 0.7 * sideK) * lightK);
        }
        highp float mist = 0.12 * (1.0 - smoothstep(0.0, h3 * 0.7 + 0.004, y));
        c = mix(col, lc, 1.0 - mist);
    }
    highp vec4 md = meadow(a, y, h3, gold, night);
    c = mix(c, md.rgb, md.a);
    highp vec4 vl = village(a, y, night, gold);
    c = mix(c, vl.rgb, vl.a);
    highp vec4 tr = train(a, y, night, gold);
    c = mix(c, tr.rgb, tr.a);
    highp vec4 ap = airships(a, y, day, gold, night);
    c = mix(c, ap.rgb, ap.a);
    highp float bd = birds(a, y);
    c = mix(c, mix(vec3(0.16, 0.18, 0.26), vec3(0.30, 0.18, 0.22), gold) * (1.0 - night * 0.5), bd * (1.0 - night * 0.6));
    return c;
}

highp vec3 horizonLand(highp vec3 col, highp vec3 dir, highp vec3 sunDir, highp float day, highp float gold, highp float night, highp float org, highp float rain) {
    highp float a = atan(dir.z, dir.x);
    highp float y = dir.y;
    if (y > 0.5) {
        return col;
    }
    if (y >= 0.0) {
        return landLayers(col, a, y, dir, sunDir, day, gold, night, org, rain);
    }
    highp vec3 near = layerColor(2, day, gold, night, org, rain);
    return mix(mix(near, col, 0.3), col, smoothstep(-0.10, 0.0, y));
}

// ---------------------------------------------------------------- Ghibli sun & moon (replace the vanilla squares)
// landTop: height of the painted hills in this direction (sun and moon set behind them); bigMoon: huge amber moon while it rises
highp vec3 sunAndMoon(highp vec3 col, highp vec3 dir, highp vec3 sunDir, highp float day, highp float gold, highp float night, highp float rain, highp float phase, highp float landTop, highp float bigMoon) {
    if (dir.y < landTop) {
        return col;
    }
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
        // "rising" is measured from the top of the painted hills in the moon's direction, so the huge amber moon
        // is what you see as it clears the ridge (without hills: from the horizon)
        highp float moonLand = 0.0;
        if (landTop > -0.5) {
            highp vec3 hm = landHeights(atan(md.z, md.x));
            moonLand = max(hm.x, max(hm.y, hm.z));
        }
        highp float riseK = smoothstep(moonLand - 0.02, moonLand + 0.22, md.y);
        highp float mr = 0.045 * mix(mix(2.8, 1.0, riseK), 1.0, 1.0 - bigMoon);
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
            highp vec3 mc = mix(vec3(1.0, 0.68, 0.38), vec3(0.99, 0.96, 0.86), mix(riseK, 1.0, 1.0 - bigMoon)) * (1.0 - crater);
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

// cloud pass: ModelOffset.x = lightning flash (negative = only the far storm flashes, -2 more while a bolt shows),
// ModelOffset.y = tower amount + 2 * (storm direction index * 41 + storm amount * 40), ModelOffset.z = tower seed
highp float gFlash, gStormFlash, gBolt, gStorm, gTower;
highp vec2 gStormDir;
void decodeCloudPass() {
    highp float x = ModelOffset.x;
    gFlash = max(x, 0.0);
    highp float v = max(-x, 0.0);
    gBolt = v >= 1.999 ? 1.0 : 0.0;
    gStormFlash = v - 2.0 * gBolt;
    highp float q = floor(ModelOffset.y * 0.5 + 0.0001);
    gTower = ModelOffset.y - 2.0 * q;
    highp float ai = floor(q / 41.0 + 0.0001);
    gStorm = (q - ai * 41.0) / 40.0;
    highp float ang = ai * 5.0 * 3.14159265 / 180.0;
    gStormDir = vec2(cos(ang), sin(ang));
}

highp float stormSide(highp vec3 d) {
    highp vec2 h = normalize(d.xz + vec2(1e-5));
    return smoothstep(0.25, 0.85, dot(h, gStormDir));
}

// the far storm: a dark cumulonimbus wall with cauliflower tops, an anvil when close, rain curtains and lightning inside
highp vec4 stormWall(highp vec3 d, highp vec3 sunDir, highp float day, highp float gold, highp float night, highp float org, highp float tm) {
    highp vec2 h = normalize(d.xz + vec2(1e-5));
    highp float side = dot(h, gStormDir);
    if (side < -0.1 || d.y < -0.05) return vec4(0.0);
    highp float az = atan(h.x * gStormDir.y - h.y * gStormDir.x, side);
    highp float w = smoothstep(-0.05, 0.75, side);
    highp float bump = 0.0;
    for (int sc = 0; sc < 2; sc++) {
        highp float fq = sc == 0 ? 5.0 : 13.0;
        highp float k = floor(az * fq);
        for (int j = -1; j <= 1; j++) {
            highp float id = k + float(j);
            highp float c = (id + 0.5 + (hash2(vec2(id, fq)) - 0.5) * 0.5) / fq;
            highp float r = (0.55 + 0.55 * hash2(vec2(id, fq + 3.0))) / fq;
            highp float hh = (sc == 0 ? 0.55 : 0.18) + (sc == 0 ? 0.45 : 0.14) * hash2(vec2(id, fq + 7.0));
            highp float xx = (az - c) / r;
            bump = max(bump, (sc == 0 ? 0.0 : 0.62) + hh * sqrt(max(0.0, 1.0 - xx * xx)));
        }
    }
    highp float top = (0.04 + 0.30 * gStorm) * w * bump;
    // the anvil: a flat sheet spreading from the top when the storm is close
    highp float anvil = smoothstep(0.55, 1.0, gStorm) * smoothstep(0.5, 0.95, side);
    highp float anvilTop = top + anvil * (0.025 + 0.012 * noise(vec2(az * 6.0, 2.0)));
    bool inAnvil = anvil > 0.0 && d.y >= top && d.y < anvilTop && abs(az) < 0.35 + 0.5 * gStorm;
    if (d.y > top && !inAnvil) return vec4(0.0);
    highp float v = clamp(d.y / max(top, 1e-3), 0.0, 1.0);
    highp vec3 lit = mix(vec3(0.96, 0.96, 0.98), mix(vec3(0.98, 0.68, 0.72), vec3(1.0, 0.72, 0.46), org), gold);
    highp vec3 mid = mix(vec3(0.50, 0.54, 0.66), vec3(0.54, 0.40, 0.56), gold);
    highp vec3 dark = mix(vec3(0.24, 0.27, 0.38), vec3(0.27, 0.19, 0.33), gold);
    highp float nk = night * (1.0 - day) * (1.0 - gold);
    lit = mix(lit, vec3(0.30, 0.33, 0.48), nk);
    mid = mix(mid, vec3(0.16, 0.18, 0.28), nk);
    dark = mix(dark, vec3(0.08, 0.09, 0.15), nk);
    highp float l = v + 0.18 * (noise(vec2(az * 30.0, d.y * 40.0)) - 0.5) + 0.15 * dot(h, normalize(sunDir.xz + vec2(1e-5)));
    highp vec3 c = l > 0.80 ? lit : (l > 0.62 ? mix(mid, lit, 0.45) : (l > 0.30 ? mid : dark));
    if (inAnvil) c = mix(mid, lit, 0.55);
    // rain curtains hanging from the dark base
    highp float shaft = smoothstep(0.55, 0.75, noise(vec2(az * 40.0, 3.0))) * (1.0 - smoothstep(0.0, 0.35, v));
    c = mix(c, mix(vec3(0.55, 0.58, 0.66), vec3(0.12, 0.13, 0.20), nk), shaft * 0.6);
    // lightning glowing inside
    highp float fx = 0.25 * (hash2(vec2(floor(tm * 0.7), 5.0)) - 0.5);
    highp float fz = exp(-pow((az - fx) * 7.0, 2.0) - pow((v - 0.45) * 2.5, 2.0));
    c += vec3(0.75, 0.82, 1.0) * gStormFlash * fz * 0.9;
    highp float a = smoothstep(0.0, 0.25, w) * (0.75 + 0.25 * gStorm);
    return vec4(c, a);
}

// a bolt from the storm's base down to the horizon
highp float stormBolt(highp vec3 d, highp float tm) {
    if (gBolt < 0.5 || gStorm < 0.3) return 0.0;
    highp vec2 h = normalize(d.xz + vec2(1e-5));
    highp float az = atan(h.x * gStormDir.y - h.y * gStormDir.x, dot(h, gStormDir));
    highp float seed = floor(tm * 2.0);
    highp float x = 0.3 * (hash2(vec2(seed, 1.7)) - 0.5) + 0.012 * (noise(vec2(d.y * 90.0, seed)) - 0.5) + 0.02 * (noise(vec2(d.y * 25.0, seed + 5.0)) - 0.5);
    highp float top = (0.04 + 0.30 * gStorm) * 0.45;
    if (d.y > top || d.y < -0.01) return 0.0;
    highp float e = abs(az - x);
    return e < 0.0035 ? 1.0 : (e < 0.009 ? 0.35 : 0.0);
}
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
                highp vec2 fc = vec2(-90.0 + 180.0 * hash2(vec2(sd, floor(gFlash * 9.0))), 90.0 + 130.0 * hash2(vec2(floor(gFlash * 9.0), sd)));
                highp float fd = length(vec2(px, py) - fc) / 120.0 + (noise(vec2(px, py) / 22.0 + sd) - 0.5) * 0.7;
                highp float fl = gFlash * (fd < 0.25 ? 1.0 : (fd < 0.7 ? 0.5 : (fd < 1.2 ? 0.2 : 0.0)));
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
    highp vec2 off = vec2(TextureMat[2].w, ColorModulator.y); // camera position in footprint texels
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
    tmLand = tm;
    windWaves = (flags & F_HILLWIND) != 0 ? 1.0 : 0.0;
    trainOn = (flags & F_TRAIN) != 0 ? 1.0 : 0.0;
    featVillage = (flags & F_VILLAGE) != 0 ? 1.0 : 0.0;
    featBirds = (flags & F_BIRDS) != 0 ? 1.0 : 0.0;
    featAir = (flags & F_AIRSHIP) != 0 ? 1.0 : 0.0;
    featFalls = (flags & F_FALLS) != 0 ? 1.0 : 0.0;
    // the rainbow slot also carries the lake (bit 0) and today's far shower (direction index 1..32, 0 = none)
    highp float rbRaw = TextureMat[2].z;
    highp float rbCode = floor(rbRaw * 0.5 + 0.001);
    highp float rainbowL = rbRaw - rbCode * 2.0;
    featLake = mod(rbCode, 2.0);
    highp float showerIdx = mod(floor(rbCode * 0.5 + 0.001), 64.0);
    highp float icons = floor(rbCode / 128.0 + 0.001); // pines, lone tree, power poles, meadow, torii
    featPines = mod(icons, 2.0);
    featTree = mod(floor(icons / 2.0 + 0.001), 2.0);
    featPoles = mod(floor(icons / 4.0 + 0.001), 2.0);
    featMeadow = mod(floor(icons / 8.0 + 0.001), 2.0);
    featTorii = mod(floor(icons / 16.0 + 0.001), 2.0);
    curtainK = showerIdx > 0.5 ? 1.0 - TextureMat[1].w : 0.0;
    curtainA = (showerIdx - 1.0) / 32.0 * TAU;
    highp float coverAdj = TextureMat[3].y;
    highp float shootPeriod = TextureMat[3].z;
    highp float mode = TextureMat[3].w;
    highp float rise = ColorModulator.x;
    highp float org = ColorModulator.w;
    highp float milkyStrength = ColorModulator.y;
    int layers = int(round(ColorModulator.z));
    highp float seaA = 0.0, seaH = 0.0;
    if (mode > 0.5) {
        // cloud pass: the layer slot also carries the cloud sea (amount, camera height above it)
        highp float lv = floor(ColorModulator.z + 0.5);
        seaH = floor(lv / 256.0 + 0.0001);
        highp float lr = lv - seaH * 256.0;
        highp float a31 = floor(lr / 8.0 + 0.0001);
        seaA = a31 / 31.0;
        layers = int(lr - a31 * 8.0 + 0.5) - 4;
    }
    highp float silverA = 0.0, blueA = 0.0;
    if (mode <= 0.5) {
        // sky pass: the layer slot also carries the silver night clouds and the blue hour
        highp float lv = floor(ColorModulator.z + 0.5);
        highp float b31 = floor(lv / 256.0 + 0.0001);
        highp float lr = lv - b31 * 256.0;
        highp float s31 = floor(lr / 8.0 + 0.0001);
        silverA = s31 / 31.0;
        blueA = b31 / 31.0;
        layers = int(lr - s31 * 8.0 + 0.5) - 4;
    }

    if (mode > 0.5) {
        decodeCloudPass();
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
            highp vec4 tw = towers(dir, sunDir, day, gold, night, rain, org, gTower, ModelOffset.z);
            // the far, low painted clouds sit behind the towers; high ones (overhead) pass in front
            highp float front = smoothstep(0.30, 0.45, dir.y);
            highp vec4 over = vec4(mix(tw.rgb, k.rgb, k.a), max(k.a, tw.a));
            highp vec4 under = vec4(mix(k.rgb, tw.rgb, tw.a), max(k.a, tw.a));
            k = mix(under, over, front);
        }
        if (gStorm > 0.001) {
            // the far storm: behind the high painted clouds, in front of the low ones; the sky darkens on its side
            highp float dk = gStorm * stormSide(dir) * 0.5 * (1.0 - smoothstep(0.05, 0.9, dir.y)) * (1.0 - rain);
            highp vec3 dc = mix(mix(vec3(0.45, 0.52, 0.64), vec3(0.50, 0.38, 0.50), gold), vec3(0.06, 0.07, 0.12), night * (1.0 - day) * (1.0 - gold));
            k = vec4(mix(dc, k.rgb, k.a), max(k.a, dk));
            highp vec4 sw = stormWall(dir, sunDir, day, gold, night, org, tm);
            sw.a *= 1.0 - rain;
            highp float frontS = smoothstep(0.30, 0.45, dir.y);
            highp vec4 overS = vec4(mix(sw.rgb, k.rgb, k.a), max(k.a, sw.a));
            highp vec4 underS = vec4(mix(k.rgb, sw.rgb, sw.a), max(k.a, sw.a));
            k = mix(underS, overS, frontS);
            highp float bl = stormBolt(dir, tm) * (1.0 - rain);
            k = vec4(mix(k.rgb, vec3(1.0, 1.0, 0.96), bl), max(k.a, bl));
            // painted clouds on the storm side turn grey-blue
            k.rgb = mix(k.rgb, k.rgb * vec3(0.70, 0.74, 0.86), gStorm * stormSide(dir) * 0.6);
        }
        if ((flags & F_MOUNTAINS) != 0 && dir.y < 0.26) {
            highp vec3 hs = landHeights(atan(dir.z, dir.x));
            if (dir.y < max(hs.x, max(hs.y, hs.z))) {
                k = vec4(0.0); // painted clouds sit behind the painted mountains
            }
        }
        if (seaA > 0.01 && seaH > 2.0 && dir.y < -0.002) {
            // the cloud sea out to the horizon (the terrain shader draws it over the land nearby)
            highp float tS = seaH / (-dir.y);
            highp vec2 qS = dir.xz * tS * 0.018 + vec2(tm * 0.02, tm * 0.007);
            highp float h0 = noise(qS) * 0.6 + noise(qS * 2.3 + 3.1) * 0.3 + noise(qS * 5.1 + 7.7) * 0.1;
            highp float hx = noise(qS + vec2(0.12, 0.0)) * 0.6 + noise((qS + vec2(0.12, 0.0)) * 2.3 + 3.1) * 0.3;
            highp float hz = noise(qS + vec2(0.0, 0.12)) * 0.6 + noise((qS + vec2(0.0, 0.12)) * 2.3 + 3.1) * 0.3;
            highp vec3 cn = normalize(vec3(-(hx - h0 * 0.9) * 7.0, 1.0, -(hz - h0 * 0.9) * 7.0));
            highp float lS = dot(cn, normalize(sunDir + vec3(0.0, 0.15, 0.0))) * 0.35 + 0.45 + (h0 - 0.45) * 1.1;
            highp vec3 c0 = mix(vec3(0.58, 0.72, 0.96), vec3(0.42, 0.30, 0.52), gold), c1 = mix(vec3(0.74, 0.85, 1.0), vec3(0.58, 0.42, 0.62), gold);
            highp vec3 c2 = mix(vec3(0.89, 0.95, 1.0), vec3(0.90, 0.66, 0.60), gold), c3 = mix(vec3(1.0), vec3(1.0, 0.86, 0.64), gold);
            highp vec3 cs = lS > 0.78 ? c3 : (lS > 0.62 ? c2 : (lS > 0.45 ? c1 : c0));
            cs *= 0.25 + 0.75 * max(day, gold);
            highp float aS = seaA;
            highp vec4 sea = vec4(cs, aS);
            k = vec4(mix(sea.rgb, k.rgb, k.a), max(k.a, sea.a));
        }
        highp float sd = dot(dir, sunDir);
        highp float rbowK = rainbowL;
        if ((flags & F_GODRAYS) != 0 && k.a < 0.5 && (gold * day > 0.05 || rbowK > 0.05) && sd > 0.35 && dir.y > 0.0 && layers != 0) {
            // beams of warm light where the sky between this pixel and the sun is free of clouds
            highp float lightK = shafts(dir, sunDir, cloudOff, COV - coverAdj - 0.12 * rain);
            highp float ray = pow((sd - 0.35) / 0.65, 2.5) * lightK * max(gold * day, rbowK * 0.6) * (1.0 - rain * 0.7);
            highp vec3 rc = mix(vec3(1.0, 0.86, 0.62), vec3(1.0, 0.93, 0.70), org);
            highp vec4 r = vec4(rc, ray * 0.45);
            k = vec4(mix(r.rgb, k.rgb, k.a), max(k.a, r.a));
        }
        k.rgb += vec3(0.55, 0.58, 0.72) * gFlash * k.a * 0.6; // lightning lights the clouds from inside
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
        highp float landTop = (flags & F_MOUNTAINS) != 0 ? -0.4 : -1.0; // > -0.5: the hills are on
        if ((flags & F_MOUNTAINS) != 0 && dir.y < 0.4) {
            highp vec3 hsM = landHeights(atan(dir.z, dir.x));
            landTop = max(hsM.x, max(hsM.y, hsM.z));
        }
        col = sunAndMoon(col, dir, sunDir, day, gold, night, rain, ModelOffset.y, landTop, (flags & F_BIGMOON) != 0 ? 1.0 : 0.0);
    }
    if (blueA > 0.01) {
        // the blue hour: deep soft blue overhead, a last pink glow where the sun went down,
        // and on the other side the pink "belt of Venus" over the blue-grey shadow of the earth
        highp float h = max(dir.y, 0.0);
        highp vec2 hd = normalize(dir.xz + vec2(1e-5)), sh = normalize(sunDir.xz + vec2(1e-5));
        highp float toward = max(dot(hd, sh), 0.0), away = max(-dot(hd, sh), 0.0);
        highp vec3 bc = mix(vec3(0.42, 0.40, 0.66), vec3(0.10, 0.16, 0.42), pow(h, 0.45));
        bc = mix(bc, vec3(0.95, 0.58, 0.52), pow(toward, 3.0) * (1.0 - smoothstep(0.0, 0.22, h)) * 0.75);
        bc = mix(bc, vec3(0.80, 0.56, 0.70), smoothstep(0.05, 0.10, h) * (1.0 - smoothstep(0.12, 0.25, h)) * away * 0.5);
        bc = mix(bc, vec3(0.30, 0.34, 0.56), (1.0 - smoothstep(0.0, 0.07, h)) * away * 0.6);
        col = mix(col, bc, blueA * 0.85 * (dir.y > -0.05 ? 1.0 : 0.6));
    }
    if (silverA > 0.01 && dir.y > 0.01 && dir.y < 0.4) {
        // silver night clouds: thin rippled streaks glowing blue-silver low on the sun's side
        highp vec2 q = dir.xz / (dir.y + 0.05) * 0.6;
        highp vec2 rq = vec2(q.x * 0.8 + q.y * 0.6, -q.x * 0.6 + q.y * 0.8);
        highp float waves = noise(rq * vec2(1.0, 7.0) + vec2(tm * 0.004, 0.0)) * 0.55 + noise(rq * vec2(2.6, 16.0)) * 0.30 + noise(rq * vec2(6.0, 30.0)) * 0.15;
        highp float field = noise(q * 0.25 + 4.0);
        highp float sideS = pow(max(dot(normalize(dir.xz + vec2(1e-5)), normalize(sunDir.xz + vec2(1e-5))), 0.0), 1.5);
        highp float sa = smoothstep(0.50, 0.72, waves) * smoothstep(0.35, 0.6, field) * sideS * smoothstep(0.01, 0.06, dir.y) * (1.0 - smoothstep(0.24, 0.40, dir.y));
        highp vec3 sc = mix(vec3(0.55, 0.80, 1.0), vec3(0.88, 0.96, 1.0), smoothstep(0.62, 0.85, waves));
        col = mix(col, sc, sa * silverA * 0.85);
    }
    // lightning glowing inside the storm clouds
    col += vec3(0.50, 0.55, 0.72) * ModelOffset.x * (0.4 + 0.6 * rain) * smoothstep(-0.05, 0.3, dir.y);

    if ((flags & F_MOUNTAINS) != 0) {
        col = horizonLand(col, dir, sunDir, day, gold, night, org, rain);
    }

    highp float rbow = rainbowL;
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
