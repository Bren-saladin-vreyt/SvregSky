#version 330

precision highp float;

// SvregSky "Raymarch Crumble" clouds, drawn into a small (1/2 .. 1/8 screen) buffer.
// Shape from baked textures only (CrumbleNoise): a weather map gives each cloud a dome-shaped body (flat base, round
// top), then two sizes of round puffs bulge its outline into a Ghibli cauliflower. Every detail size fades out once
// one cloud pixel covers too many blocks to draw it in about 3 pixels, so no puff is ever a lone sparkling pixel.
// The first point inside a cloud is toon-lit in four flat tones. Output: rgb = colour, a = encoded distance (0 = none).
// TextureMat[0] = sun dir, layer height | [1] = day, gold, night, rain | [2] = haze away (xyz), amount | [3] = haze toward the sun (x, z, w)
// ColorModulator = buffer height (pixels), reach, steps, org | ModelOffset = camera in cloud space (x, height above the cloud base, z)

layout(std140) uniform DynamicTransforms {
    highp mat4 ModelViewMat;
    highp vec4 ColorModulator;
    highp vec3 ModelOffset;
    highp mat4 TextureMat;
};

layout(std140) uniform Projection {
    highp mat4 ProjMat;
};

uniform highp sampler2D Sampler1; // the painted hills' silhouette strip (cirrus_hillmask)
uniform highp sampler2D Sampler0; // CrumbleNoise atlas: 32 detail slices of 66 x 66 on top, the 258 x 258 weather map below (sharp, then blurred)

in highp vec2 uv;

out highp vec4 fragColor;

const highp float ATLAS_W = 2112.0, ATLAS_H = 324.0;
// one weather texel = 5 blocks: clouds about as tall as they are wide (towering domes, not slabs)
const highp float WTILE = 1280.0;

// detail, tile 128 x 64 x 128 blocks: r = medium puffs, g = small puffs, b = wisps. Two bilinear reads.
highp vec3 detailTex(highp vec3 p) {
    highp float fy = fract(p.y / 64.0) * 32.0 - 0.5;
    highp float s0 = floor(fy);
    highp float wy = fy - s0;
    highp float sa = mod(s0, 32.0), sb = mod(s0 + 1.0, 32.0);
    highp vec2 xz = fract(p.xz / 128.0) * 64.0 + 1.0;
    highp vec3 a = texture(Sampler0, vec2((sa * 66.0 + xz.x) / ATLAS_W, xz.y / ATLAS_H)).rgb;
    highp vec3 b = texture(Sampler0, vec2((sb * 66.0 + xz.x) / ATLAS_W, xz.y / ATLAS_H)).rgb;
    return mix(a, b, wy);
}

// weather map, tile WTILE x WTILE blocks: r = cover, g = how tall, b = base offset. One bilinear read.
highp vec3 weather(highp vec2 xz) {
    highp vec2 q = fract(xz / WTILE) * 256.0 + 1.0;
    return texture(Sampler0, vec2(q.x / ATLAS_W, (66.0 + q.y) / ATLAS_H)).rgb;
}

highp float layerH = 125.0, amount = 1.0;
// aerial fade strength (0..2, the menu slider), packed as the fraction of ColorModulator.z (steps + strength / 4)
highp float fadeK = 1.5;
// living clouds: how far (blocks, 0..64) the puffs have risen through the cloud bodies -- they churn slowly upward
// like real cumulus. The detail tile is 64 blocks tall, so wrapping at 64 is seamless.
highp float churn = 0.0;
// how strongly each puff size is drawn at the current distance (1 near, 0 once it would be under ~3 pixels)
highp float kM = 1.0, kS = 1.0;
// 0 near, 1 where one cloud pixel spans many blocks: thin cloud there would be a blinking dot, so it is dropped
highp float farK = 0.0, blurK = 0.0;

// the high layer (altocumulus streaks) sits this far above the cloud base
const highp float HIGH0 = 255.0, HIGH1 = 285.0;

// far away the pre-blurred weather map is read: clouds too small to cover ~3 pixels melt into their neighbours or
// vanish smoothly instead of blinking as lone dots. One read near or far, two in between.
highp vec3 weatherL(highp vec2 xz) {
    if (blurK <= 0.0) {
        return weather(xz);
    }
    highp vec2 q = fract(xz / WTILE) * 256.0 + 1.0;
    highp vec3 wb = texture(Sampler0, vec2((258.0 + q.x) / ATLAS_W, (66.0 + q.y) / ATLAS_H)).rgb;
    return blurK >= 1.0 ? wb : mix(weather(xz), wb, blurK);
}

// clouds lean with the wind: their tops drift this many blocks along +x per block of height (shear)
const highp float SHEAR = 0.12;
// lowest cloud base above the layer floor
const highp float BASE0 = 14.0;

// set by bodyAt for the march: how far (blocks) the point is from a cloud body -- vertically, above the dome or below
// the belly of this column (gGapV), or in any direction from cover thick enough to grow a body (gGapH). 0 = near.
highp float gGapV = 0.0, gGapH = 0.0;
// with the horizon bank on, the overhead clouds thin out toward their reach, so the horizon belongs to the bank
// (crisp towers there instead of a hazy band of far small clouds)
bool gBank = false;
highp vec2 gCam = vec2(0.0);
highp float gReach = 1500.0;

// the cloud body, 0..1. No flat base any more: each cloud is an egg standing on its narrow end upside down -- a short
// rounded belly below its widest point, a tall dome above. Each cloud has its own belly height, and the thin edges
// float higher than the core, so seen from below the underside is a curved, lumpy surface.
// Also gives the weather sample (w, for the stride logic) and how far below the widest point we are (under, 0..1).
highp float bodyAt(highp vec3 p, out highp vec3 w, out highp float under) {
    under = 0.0;
    gGapV = 0.0;
    gGapH = 0.0;
    // lean downwind with height (shear)
    highp vec2 xs = p.xz - vec2(SHEAR * p.y, 0.0);
    highp vec3 w0 = weatherL(xs);
    w = w0;
    if (w0.r < 0.47 - 0.06 * amount) {
        return 0.0; // open sky well away from any cloud: one read is enough
    }
    highp vec3 wc = w0;
    highp float lo = 0.55 - 0.06 * amount;
    highp float c = smoothstep(lo, 0.80 - 0.06 * amount, wc.r); // wide ramp: cover thickens slowly -> domes
    if (gBank) {
        c *= 1.0 - smoothstep(0.45, 0.85, distance(xs, gCam) / gReach);
    }
    if (c <= 0.0) {
        // the cover can rise at most ~0.02 per block (measured 0.017 in the baked map), and a step moves the lookup by
        // at most 1.12 blocks per block (sideways plus the shear): no body within this distance
        gGapH = (lo - wc.r) * 44.0;
        return 0.0;
    }
    highp float top = 40.0 + (layerH + 60.0) * (0.35 + 0.65 * wc.g);
    highp float belly = 0.24 * top;
    highp float mid = BASE0 + 40.0 * wc.b + belly + 14.0 * (1.0 - c);
    // this column's body spans [mid - belly * hbMax, mid + (top - belly) * htMax] (where the profile is still > 0)
    highp float yHi = mid + (top - belly) * exp2(log2(c / 1.3) * 0.6667);
    highp float yLo = mid - belly * exp2(log2(c * c / 0.9) * 0.6667);
    gGapV = max(p.y - yHi, yLo - p.y);
    if (p.y < mid) {
        highp float hb = (mid - p.y) / belly;
        under = clamp(hb, 0.0, 1.0);
        return max(0.0, c * c - 0.9 * hb * sqrt(hb)); // the belly hangs from the thick core only: pinched, tail-like
    }
    highp float ht = (p.y - mid) / (top - belly);
    return max(0.0, c - 1.3 * ht * sqrt(ht));        // dome
}

// signed cloud field (> 0 inside). The thicker the body, the lower the bar a puff must clear: the core is solid, and
// toward the edge only puff centres survive, so the outline is a crowd of round lobes (Ghibli cauliflower) -- on the
// belly too, a little softer there. Faded puff sizes read as their average (0.5), so clouds keep their size far away.
highp float field(highp float bw, highp vec3 d, highp float under, highp float ks) {
    // small puffs only roughen the surface of thick body: on the thin rim they would make loose crumbs that blink
    highp float sk = ks * smoothstep(0.3, 0.6, bw);
    highp float pz = mix(0.5, d.r, kM) * 0.72 + mix(0.5, d.g, sk) * 0.28;
    pz = mix(pz, 0.5, 0.25 * under);
    // a higher bar on the thin rim: a puff there survives whole or not at all (no one-pixel slivers)
    return pz + 1.15 * bw - 1.0 - 0.07 * (1.0 - smoothstep(0.25, 0.55, bw));
}

// puffs are read stretched along the wind (x) and a little flattened, and drift with the shear like the body
highp vec3 puffCoord(highp vec3 p) {
    return vec3((p.x - SHEAR * p.y) * 0.7, p.y * 1.15 - churn, p.z * 0.9);
}

highp float cumulus(highp vec3 p) {
    if (p.y < 0.0 || p.y > BASE0 + 60.0 + layerH + 100.0) {
        return -1.0;
    }
    highp vec3 w;
    highp float under;
    highp float bw = bodyAt(p, w, under);
    if (bw <= 0.0) {
        // no body: big strides only well away from any cloud, fine steps near one (so thin edge puffs are never skipped)
        return w.r < 0.53 - 0.06 * amount ? -1.0 : -0.3;
    }
    return field(bw, detailTex(puffCoord(p)), under, kS);
}

// high thin streaks stretched along the wind, in patches
highp float highLayer(highp vec3 p) {
    if (p.y < HIGH0 || p.y > HIGH1) {
        return -1.0;
    }
    // long thin streaks: the weather map stretched 5x along the wind, only its highest peaks
    highp vec3 wq = weather(vec2(p.x * 0.2, p.z) * 0.8 + 700.0);
    highp float cov = smoothstep(0.70 - 0.03 * amount, 0.80, wq.r) * smoothstep(0.45, 0.65, wq.g);
    if (cov <= 0.0) {
        return -1.0;
    }
    highp float hf = (p.y - HIGH0) / (HIGH1 - HIGH0);
    highp float prof = smoothstep(0.0, 0.35, hf) * (1.0 - smoothstep(0.55, 1.0, hf));
    // soft frayed ends from the medium puffs (stretched too), faded with distance like everything else
    highp float e = mix(0.5, detailTex(vec3(p.x * 0.25, p.y * 2.0, p.z) + 40.0).r, kM);
    return cov * prof * 1.4 + (e - 0.5) * 0.5 - 0.25 - 0.1 * farK;
}

highp float density(highp vec3 p) {
    return p.y > HIGH0 - 1.0 ? highLayer(p) : cumulus(p);
}

// the painted hills' silhouette, baked by cirrus_hillmask into Sampler1 (1024 x 1, R = top / 0.6)
highp float hillTop(highp vec3 dir) {
    return texture(Sampler1, vec2(atan(dir.z, dir.x) / 6.2831853 + 0.5, 0.5)).r * 0.6;
}

// ---- horizon cloud bank ----
// A wall of towering puff clouds all around the horizon, behind the painted hills (like the anime skies where the
// sky overhead is clear and big cumulus stand along the horizon). It is not marched: every direction near the horizon
// reads a 2D cloud field laid around the camera -- masses from one row of the same weather map, the same puffs, the
// same toon light and palette -- so it costs a few texture reads per pixel. It drifts with the wind (ro carries it),
// grows with the living-clouds cycle (layerH, amount) and churns with the puffs.
// Ring: 7680 blocks around (6 weather tiles, 60 detail tiles: seamless), radius ~1222 blocks.
const highp float BANK_C = 7680.0, BANK_R = 1222.0, BANK_P = 0.35; // BANK_P: puff scale (bigger puffs far away)
highp float bankField(highp float u, highp float e, highp float kMb, highp float kSb, out highp float body, out highp vec3 dt) {
    // u / 3: the weather row wraps exactly (7680 / 3 = 2 tiles), so there is no seam behind the camera
    highp vec3 w = weather(vec2(u / 3.0, 9000.0));
    // a second, slower row decides where a tall tower rises out of the bank
    highp vec3 w2 = weather(vec2(u / 6.0 + 300.0, 9300.0));
    highp float c = smoothstep(0.32 - 0.05 * amount, 0.60 - 0.05 * amount, w.r);
    body = 0.0;
    dt = vec3(0.5);
    // heights vary: long low banks everywhere, middling heaps, and now and then a tall tower (in view height units),
    // all growing with the daily cycle
    highp float tower = smoothstep(0.46, 0.66, w2.r);
    // rarest of all: a giant thunderhead (the puff-style replacement for the painted mega cumulonimbus): a huge
    // column whose top spreads into a flat anvil, wider than the column under it
    highp float w3 = weather(vec2(u / 6.0 + 600.0, 9600.0)).r;
    // the anvil only overhangs its own column a little (no floating slabs)
    highp float megaCore = smoothstep(0.63, 0.73, w3), megaWide = smoothstep(0.61, 0.69, w3);
    highp float life = layerH / 125.0;
    // the thunderhead column rises from any bank under it (its height does not wait for thick cover)
    c = max(c, 0.9 * smoothstep(0.0, 0.6, megaCore));
    if (c <= 0.0 && megaWide <= 0.0) {
        return -1.0;
    }
    highp float H = (0.05 + 0.13 * c * (0.35 + 0.65 * w.g) + 0.30 * tower * c * c + 0.34 * megaCore * megaCore) * life;
    highp float hb = e / H;
    if (hb >= 0.0) {
        body = hb <= 1.0 ? max(0.0, c - 1.3 * hb * sqrt(hb)) : 0.0;
    } else {
        // a round lumpy belly under the base (no flat cut): it sinks behind the hills or the horizon line
        highp float hd = -hb / 0.16;
        body = max(0.0, c - 1.1 * hd * hd);
    }
    // the very top melts away softly instead of being cut at the bank's ceiling
    body *= 1.0 - smoothstep(0.40, 0.56, e);
    // the anvil: a slab at the top of the thunderhead's height, flat underneath, slightly domed on top
    // it sits where the column's puffs end (the column's visible top is ~55% of H), so it caps the column
    highp float Hm = (0.05 + 0.13 * 0.9 + 0.34) * life;
    highp float aBot = 0.50 * Hm, aTop = aBot + (0.05 + 0.03 * c) * life;
    if (megaWide > 0.0 && megaCore > 0.6 && e > aBot && e < aTop) {
        highp float s = (e - aBot) / (aTop - aBot);
        highp float slab = (1.0 - pow(abs(2.0 * s - 1.0), 3.0)) * megaWide * (0.7 + 0.3 * c);
        body = max(body, slab);
    }
    if (body <= 0.0) {
        return -1.0;
    }
    dt = detailTex(vec3(u * BANK_P, (e + 0.01) * BANK_R * BANK_P - churn, 37.0));
    highp float sk = kSb * smoothstep(0.3, 0.6, body);
    highp float pz = mix(0.5, dt.r, kMb) * 0.72 + mix(0.5, dt.g, sk) * 0.28;
    return pz + 1.15 * body - 1.0 - 0.07 * (1.0 - smoothstep(0.25, 0.55, body));
}

// scene light, set at the start of main() (paint() and the horizon bank read them)
highp vec3 sunDir = vec3(0.0, 1.0, 0.0), hazeAway = vec3(0.0), hazeSun = vec3(0.0);
highp float day = 0.0, gold = 0.0, night = 0.0, rain = 0.0, org = 0.0, afterglow = 0.0;
bool gSteps = false;

// 4x4 ordered dither (0..1) on the cloud buffer's pixels
highp float bayer2(highp vec2 a) {
    a = floor(a);
    return fract(dot(a, vec2(0.5, a.y * 0.75)));
}
highp float bayer4(highp vec2 a) {
    return bayer2(0.5 * a) * 0.25 + bayer2(a);
}

// the four flat toon tones for a light value v (day -> golden hour -> night, rain greys, afterglow), then the
// distance haze (hzK = how much of it)
highp vec3 paint(highp float v, highp vec3 rd, highp float hzK) {
    highp float k1 = smoothstep(0.29, 0.31, v), k2 = smoothstep(0.45, 0.47, v), k3 = smoothstep(0.61, 0.63, v);
    highp vec3 c0 = vec3(0.50, 0.68, 0.94), c1 = vec3(0.66, 0.82, 0.99), c2 = vec3(0.84, 0.93, 1.00), c3 = vec3(1.0);
    highp vec3 g0 = mix(vec3(0.50, 0.40, 0.62), vec3(0.56, 0.50, 0.72), org);
    highp vec3 g1 = mix(vec3(0.66, 0.52, 0.68), vec3(0.72, 0.66, 0.84), org);
    highp vec3 g2 = mix(vec3(0.94, 0.72, 0.58), vec3(0.96, 0.84, 0.84), org);
    highp vec3 g3 = mix(vec3(1.00, 0.88, 0.62), vec3(1.00, 0.95, 0.84), org);
    c0 = mix(c0, g0, gold);
    c1 = mix(c1, g1, gold);
    c2 = mix(c2, g2, gold);
    c3 = mix(c3, g3, gold);
    highp float nk = night * (1.0 - day) * (1.0 - gold);
    c0 = mix(c0, vec3(0.20, 0.21, 0.31), nk);
    c1 = mix(c1, vec3(0.28, 0.29, 0.40), nk);
    c2 = mix(c2, vec3(0.40, 0.41, 0.54), nk);
    c3 = mix(c3, vec3(0.58, 0.60, 0.72), nk);
    highp float dim = 0.30 + 0.70 * max(day, gold);
    c0 = mix(c0, vec3(0.40, 0.43, 0.49) * dim, rain * 0.8);
    c1 = mix(c1, vec3(0.52, 0.55, 0.61) * dim, rain * 0.8);
    c2 = mix(c2, vec3(0.62, 0.65, 0.71) * dim, rain * 0.8);
    c3 = mix(c3, vec3(0.70, 0.73, 0.78) * dim, rain * 0.8);
    highp vec3 col = mix(mix(mix(c0, c1, k1), c2, k2), c3, k3);
    if (gSteps) {
        // stepped clouds, like a pixel painting (palette taken from painted cumulus): six flat tones from dark
        // slate under the bellies through grey and beige to warm cream on the sunlit tops, with only a narrow band
        // of dots (ordered dither) where two tones meet
        highp float dd = (bayer4(gl_FragCoord.xy) - 0.5) * 0.028;
        highp float sun = day * (1.0 - gold) * (1.0 - rain);
        highp vec3 t0 = mix(c0, vec3(0.36, 0.47, 0.57), sun);
        highp vec3 t1 = mix(mix(c0, c1, 0.5), vec3(0.47, 0.55, 0.62), sun);
        highp vec3 t2 = mix(c1, vec3(0.62, 0.65, 0.67), sun);
        highp vec3 t3 = mix(c2, vec3(0.79, 0.75, 0.69), sun);
        highp vec3 t4 = mix(mix(c2, c3, 0.6), vec3(0.91, 0.84, 0.73), sun);
        highp vec3 t5 = mix(c3, vec3(0.99, 0.93, 0.83), sun);
        col = t0;
        col = mix(col, t1, step(0.22 + dd, v));
        col = mix(col, t2, step(0.31 + dd, v));
        col = mix(col, t3, step(0.40 + dd, v));
        col = mix(col, t4, step(0.50 + dd, v));
        col = mix(col, t5, step(0.62 + dd, v));
    }
    highp vec2 sxz = sunDir.xz / (length(sunDir.xz) + 1e-4);
    highp vec2 dxz = rd.xz / (length(rd.xz) + 1e-4);
    if (afterglow > 0.0) {
        // afterglow: just after sunset the clouds keep the light -- bright parts glow peach-pink, the shade
        // turns violet, strongest on the side where the sun went down
        highp vec3 aHi = vec3(1.00, 0.72, 0.60), aMid = vec3(0.80, 0.50, 0.66), aLo = vec3(0.40, 0.33, 0.60), aDeep = vec3(0.28, 0.25, 0.48);
        highp vec3 ag = mix(mix(mix(aDeep, aLo, k1), aMid, k2), aHi, k3);
        highp float sunSide = 0.5 + 0.5 * dot(dxz, sxz);
        // only the sun side keeps the warm cap; away from it the bright band cools to pink-violet
        ag = mix(ag, mix(ag, aMid, 0.6), (1.0 - sunSide) * k3);
        col = mix(col, ag, afterglow * (1.0 - rain) * (0.55 + 0.4 * sunSide));
    }
    highp vec3 haze = mix(hazeAway, hazeSun, pow(clamp(0.5 + 0.5 * dot(dxz, sxz), 0.0, 1.0), 1.4));
    col = mix(col, haze, hzK);
    return col;
}

void main() {
    sunDir = normalize(TextureMat[0].xyz);
    layerH = TextureMat[0].w;
    day = TextureMat[1].x;
    gold = TextureMat[1].y;
    night = TextureMat[1].z;
    rain = TextureMat[1].w;
    hazeAway = TextureMat[2].xyz;
    amount = TextureMat[2].w;
    hazeSun = vec3(TextureMat[3].x, TextureMat[3].z, TextureMat[3].w);
    // TextureMat[3].y packs: 10000 x bits (1 painted hills, 2 lake, 4 pines, 8 horizon bank, 16 stepped tones) + 100 x afterglow
    // (0..99, the blue hour after sunset) + the puff churn (0..64)
    highp float bits = floor(TextureMat[3].y / 10000.0);
    highp float rest = mod(TextureMat[3].y, 10000.0);
    afterglow = floor(rest / 100.0) / 99.0;
    churn = mod(rest, 100.0);
    bool hillsOn = mod(bits, 2.0) > 0.5;
    bool bankOn = mod(floor(bits / 8.0), 2.0) > 0.5;
    gSteps = mod(floor(bits / 16.0), 2.0) > 0.5;
    gBank = bankOn && ModelOffset.y < 0.0;
    highp float bufH = max(ColorModulator.x, 1.0);
    highp float reach = ColorModulator.y;
    gReach = reach;
    org = ColorModulator.w;
    // after sunset the moon (opposite the sun) lights the clouds
    highp vec3 lightDir = sunDir.y > -0.08 ? sunDir : -sunDir;
    // blocks covered by one cloud pixel per block of distance
    highp float footK = 2.0 / (ProjMat[1][1] * bufH);

    highp vec3 vd = normalize(vec3((uv * 2.0 - 1.0) / vec2(ProjMat[0][0], ProjMat[1][1]), -1.0));
    highp vec3 rd = normalize(transpose(mat3(ModelViewMat)) * vd);
    highp vec3 ro = ModelOffset;
    gCam = ro.xz;

    fragColor = vec4(0.0);
    if (abs(rd.y) < 1e-4) {
        return;
    }
    // the painted hills stand in front of every cloud: nothing is drawn below their line
    // the painted hills stand in front of every cloud: their line is only worked out for pixels that found a cloud
    highp float hTop = -2.0; // -2 = not computed yet, -1 = no hills here
    // low rays (where most hills are) check at once and skip the march; higher ones only if they find a cloud
    if (hillsOn && rd.y < 0.36) {
        hTop = hillTop(rd);
        if (rd.y < hTop) {
            return;
        }
    }
    highp float a = (0.0 - ro.y) / rd.y, b = (HIGH1 - ro.y) / rd.y;
    highp float tA = max(min(a, b), 0.0), tB = min(max(a, b), reach);
    highp float t = tA;
    highp float tPrev = t;
    // what was hit (light value, haze, distance); hitT < 0 = nothing yet
    highp float hitV = 0.0, hitHaze = 0.0, hitT = -1.0;
    int maxSteps = int(ColorModulator.z);
    fadeK = fract(ColorModulator.z) * 4.0;
    for (int i = 0; i < 110; i++) {
        if (t > tB || i >= maxSteps) {
            break;
        }
        highp vec3 p = ro + rd * t;
        // a puff must span about 3 cloud pixels to be drawn: small puffs ~13 blocks, medium ~26
        highp float foot = t * footK;
        kS = 1.0 - smoothstep(2.6, 4.6, foot);
        kM = 1.0 - smoothstep(6.5, 11.0, foot);
        farK = smoothstep(4.0, 14.0, foot);
        blurK = smoothstep(2.5, 7.0, foot);
        highp float d = density(p);
        if (d > 0.04) {
            if (hTop < -1.5) {
                hTop = hillsOn && rd.y < 0.6 ? hillTop(rd) : -1.0;
            }
            if (rd.y < hTop) {
                return;
            }
            highp float t0 = tPrev, t1 = t;
            for (int k = 0; k < 7; k++) {
                highp float tm = 0.5 * (t0 + t1);
                if (density(ro + rd * tm) > 0.04) {
                    t1 = tm;
                } else {
                    t0 = tm;
                }
            }
            t = t1;
            p = ro + rd * t;
            highp float v;
            if (p.y > HIGH0 - 1.0) {
                v = 0.62 + 0.18 * max(0.0, lightDir.y) + 0.10 * dot(rd, lightDir);
            } else {
                highp vec3 pc = puffCoord(p);
                highp vec3 w;
                highp float under, uu;
                highp float b0 = bodyAt(p, w, under);
                highp vec3 dt = detailTex(pc);
                // normal of the body plus the medium puffs: every puff gets its own lit cap and curved shadow line,
                // the belly puffs too. Three one-sided samples around the hit (the hit itself is the fourth).
                // The step is close to a weather texel (5 blocks) so it doesn't jump by columns.
                const highp float e = 6.0;
                highp vec3 wx;
                highp float kMs = kM;
                kM *= 0.5; // the light sees the puffs half as strong: smooth curved bands, not dark ovals
                highp float f0 = field(b0, dt, under, 0.0);
                highp vec3 ox = vec3(e, 0.0, 0.0), oy = vec3(0.0, e, 0.0), oz = vec3(0.0, 0.0, e);
                highp float fx = field(bodyAt(p + ox, wx, uu), detailTex(puffCoord(p + ox)), uu, 0.0);
                highp float fy = field(bodyAt(p + oy, wx, uu), detailTex(puffCoord(p + oy)), uu, 0.0);
                highp float fz = field(bodyAt(p + oz, wx, uu), detailTex(puffCoord(p + oz)), uu, 0.0);
                highp vec3 n = -normalize(vec3(fx - f0, fy - f0, fz - f0) + vec3(0.0, 1e-4, 0.0));
                kM = kMs;
                // body between this point and the light: soft blue self-shadow (body only, two samples)
                highp float occl = 1.5 * (bodyAt(p + lightDir * 16.0, wx, uu) + bodyAt(p + lightDir * 36.0, wx, uu)) * 0.6667;
                // height inside the cloud: the dome gets whiter toward the top (this also stands in for 'cloud piled above')
                highp float hf = clamp(1.0 - under, 0.0, 1.0) * clamp(b0 * 1.5, 0.0, 1.0);
                // puff centres bright, the creases between puffs a step darker (the painted cauliflower look)
                highp float puff = (dt.r - 0.5) * kM + (dt.g - 0.5) * 0.5 * kS;
                // the seams where puffs meet: a thin darker line around each puff (the painted outline inside the cloud)
                highp float seam = smoothstep(0.36, 0.22, dt.r) * kM;
                highp float ndl = dot(n, lightDir);
                v = 0.525 + 0.26 * ndl + 0.08 * n.y + 0.14 * hf - 0.18 * occl + 0.14 * puff - 0.16 * seam;
                // the belly is not left dark: light bounced up from the bright ground and sky keeps it a pale blue,
                // and each belly puff still shows its own curved line (the puff term above), as in the references
                highp float down = smoothstep(0.1, 0.8, -n.y);
                v += 0.20 * down;
                v = mix(v, max(v, 0.47 + 0.14 * puff - 0.10 * seam), down);
                highp float toward = max(0.0, dot(rd, lightDir));
                v += 0.22 * toward * toward;            // looking toward the light the clouds glow pale
                highp float sideOn = 1.0 - abs(dot(n, rd));
                v += 0.18 * smoothstep(0.6, 0.85, sideOn) * smoothstep(-0.1, 0.45, ndl) * (1.0 - rain * 0.7);
            }
            // far away one pixel spans many puffs: settle on the middle of the pale band (no white/blue flipping)
            v = mix(v, 0.54, 0.85 * smoothstep(9.0, 22.0, t * footK));
            // aerial perspective: the farther and the lower a cloud, the more air in front of it -- its tones press
            // together (4 bands near, 1-2 pale ones far) and its colour melts into the horizon
            highp float aer = min(1.0, fadeK * smoothstep(0.10, 0.95, t / reach) * (0.55 + 0.45 * (1.0 - smoothstep(0.03, 0.40, rd.y))));
            v = mix(v, 0.57, 0.7 * aer);

            hitV = v;
            hitHaze = max(smoothstep(0.25, 1.0, t / reach) * 0.8, 0.75 * aer);
            hitT = t;
            break;
        }
        tPrev = t;
        highp float stepT = max(2.5, (d < -0.5 ? 16.0 : 3.0) * (1.0 + t / 600.0));
        // near a cloud but surely outside its body: jump to where the body could begin (never past the open-sky stride)
        if (d == -0.3) {
            // gGapV: above the dome or below the belly of this column -> the ray needs this much travel to get there;
            // gGapH: cover too thin for a body -> no body within this distance in any direction
            highp float jump = max(gGapV / max(abs(rd.y), 0.08), gGapH);
            stepT = clamp(jump, stepT, 16.0 * (1.0 + t / 600.0));
        }
        t += stepT;
    }
    // nothing overhead in this direction: the horizon bank behind (only from below the cloud layer)
    if (hitT < 0.0 && bankOn && rd.y > -0.002 && rd.y < 0.58 && ro.y < 0.0) {
        if (hTop < -1.5) {
            hTop = hillsOn && rd.y < 0.6 ? hillTop(rd) : -1.0;
        }
        if (rd.y < hTop) {
            return;
        }
        highp float th = atan(rd.z, rd.x);
        highp float u = th / 6.2831853 * BANK_C + ro.x * 0.08 + 0.5 * BANK_C;
        // behind the hills the bank's foot rides up with the ridge line (smoothly, ignoring single spires), so the
        // towers always stand above the hills instead of hiding behind them; at sea it sits right on the horizon
        // behind hills the base sits a little below the ridge, so the hills cover the foot and only the lumpy
        // tops show (a base exactly on the ridge looked cut along smooth slopes)
        highp float e = rd.y - (hTop > -0.5 ? 0.45 * min(hTop, 0.22) : 0.0) + 0.012;
        // puff fade by pixel size (one pixel covers footK radians; the puffs are BANK_R * BANK_P blocks per radian)
        highp float footB = footK * BANK_R * BANK_P;
        highp float kSb = 1.0 - smoothstep(2.6, 4.6, footB), kMb = 1.0 - smoothstep(6.5, 11.0, footB);
        highp float body;
        highp vec3 dt;
        highp float f0 = bankField(u, e, kMb, kSb, body, dt);
        if (f0 > 0.04) {
            // light: the wall's own 2D slope (half-strength puffs, like overhead) turned into a 3D normal that bulges
            // toward the viewer; the sun lights it by how it sits around the ring
            highp float bb;
            highp vec3 dd;
            highp float du = 6.0 / BANK_P, de = 6.0 / (BANK_R * BANK_P);
            highp float fu = bankField(u + du, e, kMb, kSb, bb, dd), fe = bankField(u, e + de, kMb, kSb, bb, dd);
            highp vec2 g = vec2(f0 - fu, f0 - fe);              // points out of the cloud (field falls outward)
            highp vec2 n2 = g / (length(g) + 0.03);
            highp float nz = sqrt(max(0.0, 1.0 - dot(n2, n2) * 0.75));
            highp vec2 dirH = vec2(cos(th), sin(th)), tang = vec2(-sin(th), cos(th));
            highp vec3 lightDir = sunDir.y > -0.08 ? sunDir : -sunDir;
            highp float ndl = n2.x * dot(lightDir.xz, tang) + n2.y * lightDir.y - nz * dot(lightDir.xz, dirH);
            highp float hf = clamp((e + 0.01) / 0.18, 0.0, 1.0) * clamp(body * 1.5, 0.0, 1.0);
            highp float puff = (dt.r - 0.5) * kMb + (dt.g - 0.5) * 0.5 * kSb;
            highp float seam = smoothstep(0.36, 0.22, dt.r) * kMb;
            highp float v = 0.56 + 0.26 * ndl + 0.10 * n2.y + 0.12 * hf + 0.14 * puff - 0.16 * seam;
            highp float toward = max(0.0, dot(rd, lightDir));
            v += 0.20 * toward * toward;
            v += 0.16 * smoothstep(0.75, 0.95, length(n2)) * smoothstep(-0.1, 0.45, ndl) * (1.0 - rain * 0.7);
            // a light veil of distance haze: far, but crisp and white like real horizon cumulus
            // the horizon bank is the farthest of all: a lighter version of the same fade, so the towers still read
            hitV = mix(v, 0.57, min(0.2 * fadeK, 0.6));
            hitHaze = min(0.22 * fadeK + 0.0, 0.7);
            hitT = 4000.0;
        }
    }
    if (hitT >= 0.0) {
        fragColor = vec4(paint(hitV, rd, hitHaze), max(2.0 / 255.0, 1.0 - log2(1.0 + hitT) / log2(1.0 + 4096.0)));
    }
}
