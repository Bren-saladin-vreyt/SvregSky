#version 300 es
precision highp float;
uniform vec2 res;
uniform float fov, yaw, pitch, tm;
uniform vec3 top, hor, sunDir;
uniform float show;
out vec4 o;
// ---- the painted hills' silhouette, copied from cirrus_sky.fsh (same math, so the line matches exactly) ----
// keep in sync with landFar / landMid / landNear there
const highp float FALLS_A = 0.45;
highp float featLake = 0.0, featPines = 0.0;
highp float hHash(highp vec2 p) {
    highp vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
highp float hNoise(highp vec2 p) {
    highp vec2 i = floor(p);
    highp vec2 f = fract(p);
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    highp float a = hHash(i);
    highp float b = hHash(i + vec2(1.0, 0.0));
    highp float c = hHash(i + vec2(0.0, 1.0));
    highp float d = hHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
highp float wrapA(highp float x) {
    return atan(sin(x), cos(x));
}
highp float ridge(highp float a, highp float freq, highp float seed, highp float sharp) {
    // periodic 1D fbm around the horizon (a = azimuth in radians)
    highp float v = 0.0, amp = 0.55, f = freq;
    for (int i = 0; i < 4; i++) {
        highp float x = a * f + seed * 7.13;
        highp float n = hNoise(vec2(cos(a) * f, sin(a) * f) * 0.5 + vec2(seed * 3.1, float(i) * 5.7));
        v += amp * mix(n, 1.0 - abs(2.0 * n - 1.0), sharp);
        amp *= 0.5;
        f *= 2.1;
    }
    return v;
}

highp float mesa(highp float a) {
    highp float d = abs(wrapA(a - FALLS_A));
    return 0.07 * (1.0 - smoothstep(0.055, 0.07, d)) + 0.02 * (1.0 - smoothstep(0.07, 0.12, d));
}

highp float spires(highp float a) {
    highp float k = a * 9.0;
    highp float i = floor(k), f = fract(k) - 0.5;
    highp float h = hHash(vec2(i, 17.0));
    highp float w = 0.28 + 0.2 * hHash(vec2(i, 5.0));
    highp float hh = (0.06 + 0.07 * hHash(vec2(i, 9.0))) * step(0.93, h);
    return hh * pow(max(0.0, 1.0 - abs(f - (hHash(vec2(i, 3.0)) - 0.5) * 0.3) / w), 1.8);
}

highp float pineShape(highp float f, highp float H) {
    highp float hb = H * max(0.0, 1.0 - abs(f) * 1.05);
    return max(0.0, hb - H * 0.10 * fract(hb / H * 3.5)) + H * 0.08 * step(abs(f), 0.06); // + a thin trunk tip
}

highp float crowns(highp float a) {
    highp float k = a * 48.0;
    highp float i = floor(k), f = fract(k) * 2.0 - 1.0;
    highp float t = hHash(vec2(i, 23.0));
    highp float r = (0.004 + 0.006 * hHash(vec2(i, 21.0))) * step(0.25, t);
    highp float round_ = r * sqrt(max(0.0, 1.0 - f * f * 0.9));
    // pines: some cells grow a tall pointed pine instead (two thin ones when the cell is a pair)
    highp float isPine = step(0.62, t) * featPines;
    highp float H = 0.012 + 0.012 * hHash(vec2(i, 27.0));
    highp float pine = hHash(vec2(i, 29.0)) < 0.5 ? pineShape(f * 1.3, H) : max(pineShape(f * 2.4 + 1.1, H * 0.85), pineShape(f * 2.4 - 1.1, H));
    return mix(round_, pine, isPine);
}

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

highp float nearBase(highp float a) {
    highp float flat1 = 1.0 - smoothstep(0.08, 0.16, abs(atan(sin(a - 3.4), cos(a - 3.4))));
    highp float flat2 = featLake * (1.0 - smoothstep(0.30, 0.45, abs(atan(sin(a - 1.2), cos(a - 1.2)))));
    return 0.007 * (1.0 - flat2 * 0.8) + 0.032 * ridge(a + 4.1, 5.0, 3.0, 0.05) * (1.0 - 0.6 * flat1) * (1.0 - 0.9 * flat2);
}

highp float landNear(highp float a) {
    return nearBase(a) + crowns(a) * smoothstep(0.05, 0.08, abs(atan(sin(a - 3.4), cos(a - 3.4)))) * smoothstep(0.03, 0.05, abs(atan(sin(a - 5.9), cos(a - 5.9)))) * (1.0 - featLake * (1.0 - smoothstep(0.30, 0.40, abs(atan(sin(a - 1.2), cos(a - 1.2))))));
}

// the top of the painted hills in this direction (as a view-direction y, like the sky shader compares it)
highp float hillTop(highp vec3 dir) {
    highp float a = atan(dir.z, dir.x);
    return max(landFar(a), max(landMid(a), landNear(a)));
}
// ---- end of the copied hills ----
vec3 viewDir() {
  vec2 ndc = (gl_FragCoord.xy / res) * 2.0 - 1.0;
  float f = 1.0 / tan(fov * 0.5);
  vec3 vd = normalize(vec3(ndc.x * res.x / res.y / f, ndc.y / f, -1.0));
  float cp = cos(pitch), sp = sin(pitch), cy = cos(yaw), sy = sin(yaw);
  vec3 r = vec3(cy, 0.0, sy), fw = vec3(sy * cp, sp, -cy * cp);
  vec3 u = cross(r, fw);
  return normalize(r * vd.x + u * vd.y - fw * vd.z);
}
// simple toon hills for the preview (the real ones are painted by cirrus_sky.fsh): three layers, lit rim, haze
vec3 layerCol(vec3 base, float y, float h, float rimW) {
  vec3 c = base;
  if (y > h - rimW) c = base * 1.22 + vec3(0.06, 0.06, 0.0);
  return c;
}
void main() {
  featPines = 1.0;
  vec3 d = viewDir();
  vec3 col = mix(hor, top, pow(clamp(d.y * 1.4, 0.0, 1.0), 0.6));
  if (d.y < -0.01) col = vec3(0.30, 0.55, 0.30);
  float a = atan(d.z, d.x);
  float hf = landFar(a), hm = landMid(a), hn = landNear(a);
  if (show > 1.5) { if (d.y < -0.003) col = vec3(0.22, 0.55, 0.85); o = vec4(col, 1.0); return; }
  if (d.y < hf) col = mix(layerCol(vec3(0.52, 0.62, 0.82), d.y, hf, 0.006), hor, 0.35);
  if (d.y < hm) col = mix(layerCol(vec3(0.36, 0.60, 0.56), d.y, hm, 0.005), hor, 0.18);
  if (d.y < hn) col = layerCol(vec3(0.27, 0.52, 0.30), d.y, hn, 0.004);
  o = vec4(col, 1.0);
}
