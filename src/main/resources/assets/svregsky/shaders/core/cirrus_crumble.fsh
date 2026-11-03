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

highp float layerH, amount;
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

void main() {
    highp vec3 sunDir = normalize(TextureMat[0].xyz);
    layerH = TextureMat[0].w;
    highp float day = TextureMat[1].x, gold = TextureMat[1].y, night = TextureMat[1].z, rain = TextureMat[1].w;
    highp vec3 hazeAway = TextureMat[2].xyz;
    amount = TextureMat[2].w;
    highp vec3 hazeSun = vec3(TextureMat[3].x, TextureMat[3].z, TextureMat[3].w);
    // TextureMat[3].y packs two values: 100 x afterglow (0..99, the blue hour after sunset) + the puff churn (0..64)
    highp float afterglow = floor(TextureMat[3].y / 100.0) / 99.0;
    churn = mod(TextureMat[3].y, 100.0);
    highp float bufH = max(ColorModulator.x, 1.0);
    highp float reach = ColorModulator.y;
    highp float org = ColorModulator.w;
    // after sunset the moon (opposite the sun) lights the clouds
    highp vec3 lightDir = sunDir.y > -0.08 ? sunDir : -sunDir;
    // blocks covered by one cloud pixel per block of distance
    highp float footK = 2.0 / (ProjMat[1][1] * bufH);

    highp vec3 vd = normalize(vec3((uv * 2.0 - 1.0) / vec2(ProjMat[0][0], ProjMat[1][1]), -1.0));
    highp vec3 rd = normalize(transpose(mat3(ModelViewMat)) * vd);
    highp vec3 ro = ModelOffset;

    fragColor = vec4(0.0);
    if (abs(rd.y) < 1e-4) {
        return;
    }
    highp float a = (0.0 - ro.y) / rd.y, b = (HIGH1 - ro.y) / rd.y;
    highp float tA = max(min(a, b), 0.0), tB = min(max(a, b), reach);
    if (tB <= tA) {
        return;
    }
    highp float t = tA;
    highp float tPrev = t;
    int maxSteps = int(ColorModulator.z);
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
            col = mix(col, haze, smoothstep(0.25, 1.0, t / reach) * 0.8);
            highp float enc = 1.0 - log2(1.0 + t) / log2(1.0 + 4096.0);
            fragColor = vec4(col, max(2.0 / 255.0, enc));
            return;
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
}
