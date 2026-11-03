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
// detail level: far away a cloud pixel covers many blocks, so the bubbles fade toward a smooth average (no sparkling)
highp float lodK = 1.0;

highp float coverage(highp vec2 xz) {
    highp float c = n2(xz / 170.0) * 0.55 + n2(xz / 60.0 + 7.0) * 0.45;
    return smoothstep(0.71 - 0.1 * amount, 0.98 - 0.1 * amount, c); // wide ramp: cloud cores are 'thicker' than edges -> domes
}

// the high layer (altocumulus streaks) sits this far above the cloud base
const highp float HIGH0 = 255.0, HIGH1 = 285.0;

// low/mid cumulus, built from big bubbles: every 48-block cell inside the cloud cover holds one wide bubble on the
// flat base and one more stacked somewhere above it, each its own size and height, so the outline is a crowd of
// round lobes (not a dome, not a pillar). Small baked bubbles then cover their surface.
const highp float CELLW = 48.0;

highp float lobes(highp vec3 p, highp float base) {
    highp vec2 g = p.xz / CELLW;
    highp vec2 gi = floor(g);
    highp float best = -1.0;
    for (int k = 0; k < 9; k++) {
        highp vec2 c = gi + vec2(float(k - (k / 3) * 3 - 1), float(k / 3 - 1));
        highp vec2 cxz = (c + 0.2 + 0.6 * vec2(h2(c), h2(c + 17.3))) * CELLW;
        highp float cv = coverage(cxz);
        if (cv <= 0.02) {
            continue;
        }
        highp float r = (28.0 + 22.0 * h2(c + 5.1)) * (0.6 + 0.4 * sqrt(cv));
        // the bubble sitting on the base: wider than tall
        highp vec3 q = (p - vec3(cxz.x, base + r * 0.45, cxz.y)) / vec3(r * 1.25, r * 0.8, r * 1.25);
        best = max(best, 1.0 - length(q));
        // a second bubble stacked on top, off-centre, at a random height (some cells tower, most stay low)
        highp float r2 = r * (0.55 + 0.35 * h2(c + 9.7));
        highp float lift = r * 0.6 + layerH * 0.7 * sqrt(cv) * h2(c + 3.3);
        highp vec2 off = (vec2(h2(c + 11.0), h2(c + 13.0)) - 0.5) * r;
        q = (p - vec3(cxz.x + off.x, base + lift + r2 * 0.4, cxz.y + off.y)) / vec3(r2, r2 * 0.85, r2);
        best = max(best, 1.0 - length(q));
        // in the thick middle of a cloud a third bubble crowns the stack
        if (cv > 0.45) {
            highp float r3 = r2 * (0.6 + 0.3 * h2(c + 31.0));
            highp vec2 off3 = off * -0.6 + (vec2(h2(c + 41.0), h2(c + 43.0)) - 0.5) * r2;
            q = (p - vec3(cxz.x + off3.x, base + lift + r2 * 0.9 + r3 * 0.3, cxz.y + off3.y)) / vec3(r3, r3 * 0.9, r3);
            best = max(best, 1.0 - length(q));
        }
    }
    return best;
}

// each cloud field gets its own base height
highp float cloudBase(highp vec2 xz) {
    return 38.0 * n2(xz / 300.0 + 11.0);
}

// room above the layer for the tallest stacks (a third bubble can reach ~210 blocks above the base)
const highp float TOPM = 100.0;

// the smooth cloud body: big bubbles cut by the flat base, no small bubbles. Light, normals and self-shadow read only
// this, so the shading changes slowly over the surface and does not shimmer when the camera or the pixel grid moves.
highp float body(highp vec3 p, highp float base) {
    if (p.y > base + layerH + TOPM) {
        return -1.0;
    }
    return min(lobes(p, base), (p.y - base) / 16.0); // continuous flat bottom (no step in the field)
}

highp float cumulus(highp vec3 p) {
    highp float base = cloudBase(p.xz);
    if (p.y < base - 2.0) {
        return -1.0;
    }
    highp float d = body(p, base);
    if (d < -0.35) {
        return d;
    }
    // small bubbles bulge the outline (the cauliflower edge), none on the flat bottom
    highp float bubK = smoothstep(base, base + 6.0, p.y);
    return d + (erosionTex(p) - 0.52) * 0.45 * bubK * lodK - 0.02;
}

// high thin streaks stretched along the wind, in patches
highp float highLayer(highp vec3 p) {
    if (p.y < HIGH0 || p.y > HIGH1) {
        return -1.0;
    }
    highp vec2 q = vec2(p.x * 0.35, p.z);                            // stretched along x (the wind)
    highp float c = n2(q / 120.0 + 31.0) * 0.6 + n2(q / 40.0 + 17.0) * 0.4;
    highp float cov = smoothstep(0.74 - 0.06 * amount, 0.88, c) * smoothstep(0.62, 0.78, n2(p.xz / 600.0 + 5.0));
    if (cov <= 0.0) {
        return -1.0;
    }
    highp float hf = (p.y - HIGH0) / (HIGH1 - HIGH0);
    highp float prof = smoothstep(0.0, 0.35, hf) * (1.0 - smoothstep(0.55, 1.0, hf));
    highp float e = erosionTex(vec3(p.x * 0.5, p.y * 2.0, p.z) + 40.0);
    return cov * prof - (1.0 - e) * 0.55 + 0.1;
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
    highp float reach = ColorModulator.y;
    highp float org = ColorModulator.w;
    // after sunset the moon (opposite the sun) lights the clouds
    highp vec3 lightDir = sunDir.y > -0.08 ? sunDir : -sunDir;

    // view ray for this (big) pixel, turned into the world by the camera rotation
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
    // no per-pixel jitter: it is tied to the screen, so every camera turn re-rolled it and the clouds sparkled.
    // The hit is refined by bisection instead, so the step phase barely shows.
    highp float t = tA;
    highp float tPrev = t;
    int maxSteps = int(ColorModulator.z);
    for (int i = 0; i < 110; i++) {
        if (t > tB || i >= maxSteps) {
            break;
        }
        highp vec3 p = ro + rd * t;
        lodK = 1.0 - 0.85 * smoothstep(150.0, 800.0, t);
        highp float d = density(p);
        // a margin that grows with distance: far wisps thinner than a pixel are skipped instead of sparkling
        highp float hitK = 0.05 + 0.12 * (1.0 - lodK);
        if (d > hitK) {
            highp float t0 = tPrev, t1 = t;
            for (int k = 0; k < 7; k++) {
                highp float tm = 0.5 * (t0 + t1);
                if (density(ro + rd * tm) > hitK) {
                    t1 = tm;
                } else {
                    t0 = tm;
                }
            }
            t = t1;
            p = ro + rd * t;
            highp float detail = lodK;
            // the scallops fade sooner than the outline bubbles: far away they would be single sparkling pixels
            highp float scalK = 1.0 - smoothstep(140.0, 520.0, t);
            highp float v;
            if (p.y > HIGH0 - 1.0) {
                // high streaks: thin and flat, lit mostly by how much they face the sun
                v = 0.62 + 0.18 * max(0.0, dot(lightDir, vec3(0.0, 1.0, 0.0))) + 0.10 * dot(rd, lightDir);
            } else {
                // --- painted toon light from the smooth body (big bubbles only) ---
                highp float base = cloudBase(p.xz);
                lodK = 0.0;
                // sphere-like normal of the big bubble this pixel sits on: each bubble gets its own bright cap and
                // its own curved shadow line, like the painted lobes in the references
                const highp float e = 3.0;
                highp float d1 = body(p + vec3(e, -e, -e), base), d2 = body(p + vec3(-e, -e, e), base);
                highp float d3 = body(p + vec3(-e, e, -e), base), d4 = body(p + vec3(e, e, e), base);
                highp vec3 n = -normalize(vec3(d1 - d2 - d3 + d4, -d1 - d2 + d3 + d4, -d1 + d2 - d3 + d4) + vec3(0.0, 1e-4, 0.0));
                // cloud between this point and the light, and cloud piled on top: soft blue self-shadow
                highp float occl = 0.0;
                for (int k = 1; k <= 3; k++) {
                    occl += max(0.0, body(p + lightDir * float(k) * 14.0, base));
                }
                highp float up = max(0.0, body(p + vec3(0.0, 18.0, 0.0), base));
                // higher in the cloud = whiter, the flat underside stays blue
                highp float hf = clamp((p.y - base) / (layerH * 0.7), 0.0, 1.0);
                // big round scallops along the shadow line (2x the bubble size, so they span several pixels and hold still)
                highp float scal = erosionTex(p * 0.55 + vec3(31.0, 7.0, 13.0)) - 0.5;
                highp float ndl = dot(n, lightDir);
                v = 0.56 + 0.26 * ndl + 0.10 * n.y + 0.16 * hf - 0.24 * occl - 0.12 * up + 0.34 * scal * scalK;
                // looking toward the light the clouds glow pale (light shining through the thin parts), as in the references
                highp float toward = max(0.0, dot(rd, lightDir));
                v += 0.24 * toward * toward;
                // underside facing straight down: one flat blue tone
                v -= 0.12 * smoothstep(0.4, 0.9, -n.y);
                // a thin lighter edge where the outline turns toward the light (the moonlit/sunlit rim in the references)
                highp float sideOn = 1.0 - abs(dot(n, rd));
                v += 0.20 * smoothstep(0.6, 0.85, sideOn) * smoothstep(-0.1, 0.45, ndl) * (1.0 - rain * 0.7);
            }
            lodK = detail;
            // far away one cloud pixel spans many bubbles: settle on the middle of the pale band, so neighbouring
            // pixels don't flip between white and blue (the sparkle over the far cloud field)
            v = mix(v, 0.54, 0.85 * smoothstep(280.0, 900.0, t));

            // four flat tones with crisp (1-2 pixel) steps between them
            highp float k1 = smoothstep(0.29, 0.31, v), k2 = smoothstep(0.45, 0.47, v), k3 = smoothstep(0.61, 0.63, v);

            // day: white caps, pale and mid blue, a deeper blue in the deepest shadow
            highp vec3 c0 = vec3(0.50, 0.68, 0.94), c1 = vec3(0.66, 0.82, 0.99), c2 = vec3(0.84, 0.93, 1.00), c3 = vec3(1.0);
            // golden hour: lavender shadows, peach and gold where the low sun hits (warmer when the sky is orange)
            highp vec3 g0 = mix(vec3(0.50, 0.40, 0.62), vec3(0.56, 0.50, 0.72), org);
            highp vec3 g1 = mix(vec3(0.66, 0.52, 0.68), vec3(0.72, 0.66, 0.84), org);
            highp vec3 g2 = mix(vec3(0.94, 0.72, 0.58), vec3(0.96, 0.84, 0.84), org);
            highp vec3 g3 = mix(vec3(1.00, 0.88, 0.62), vec3(1.00, 0.95, 0.84), org);
            c0 = mix(c0, g0, gold);
            c1 = mix(c1, g1, gold);
            c2 = mix(c2, g2, gold);
            c3 = mix(c3, g3, gold);
            // night: slate grey-violet, lighter where the moon catches the edges
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
            // distance haze into the horizon colour (far clouds fade into the sky like in the references)
            highp vec2 sxz = sunDir.xz / (length(sunDir.xz) + 1e-4);
            highp vec2 dxz = rd.xz / (length(rd.xz) + 1e-4);
            highp vec3 haze = mix(hazeAway, hazeSun, pow(clamp(0.5 + 0.5 * dot(dxz, sxz), 0.0, 1.0), 1.4));
            col = mix(col, haze, smoothstep(0.25, 1.0, t / reach) * 0.8);
            // distance, log-encoded into alpha (always > 0 for a hit)
            highp float enc = 1.0 - log2(1.0 + t) / log2(1.0 + 4096.0);
            fragColor = vec4(col, max(2.0 / 255.0, enc));
            return;
        }
        tPrev = t;
        // big strides through empty sky (no coverage), small ones near cloud
        t += max(2.5, (d < -0.5 ? 16.0 : 3.0) * (1.0 + t / 600.0));
    }
}
