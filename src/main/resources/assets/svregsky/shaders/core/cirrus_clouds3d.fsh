#version 330

// SvregSky 3D clouds: stepped voxel cumulus with flat toon shading in the Cirrus sky palette.
// Color attribute: r = face (0 top, 1 bottom, 2 +x, 3 -x, 4 +z, 5 -z) * 40 / 255, g = column height / max height
// TextureMat[0] = sun dir, max column height | [1] = day, gold, night, rain | [2] = haze away from sun, mode (0 hybrid, 1 full)
// TextureMat[3] = haze toward the sun (x, z, w) | ColorModulator = rise, fade start, fade end, orange golden hour

#moj_import <minecraft:dynamictransforms.glsl>

in vec3 relPos;
flat in vec4 faceInfo;
in float localY;

out vec4 fragColor;

void main() {
    vec3 sunDir = TextureMat[0].xyz;
    float colH = TextureMat[0].w;
    float day = TextureMat[1].x;
    float gold = TextureMat[1].y;
    float rain = TextureMat[1].w;
    vec3 hazeAway = TextureMat[2].xyz;
    float full = TextureMat[2].w;
    vec3 hazeSun = vec3(TextureMat[3].x, TextureMat[3].z, TextureMat[3].w);
    float fadeStart = ColorModulator.y;
    float fadeEnd = ColorModulator.z;
    float org = ColorModulator.w;

    // palettes: night -> day, then golden hour (rose over the magenta sky, white + periwinkle over the orange sky)
    vec3 top = mix(vec3(0.36, 0.38, 0.55), vec3(1.00, 1.00, 1.00), day);
    vec3 lit = mix(vec3(0.32, 0.34, 0.50), vec3(0.93, 0.96, 1.00), day);
    vec3 mid = mix(vec3(0.22, 0.23, 0.36), vec3(0.84, 0.91, 1.00), day);
    vec3 shade = mix(vec3(0.12, 0.12, 0.22), vec3(0.72, 0.81, 0.98), day);
    top = mix(top, mix(vec3(1.00, 0.92, 0.82), vec3(0.96, 0.97, 1.00), org), gold);
    lit = mix(lit, mix(vec3(1.00, 0.86, 0.76), vec3(0.88, 0.91, 1.00), org), gold);
    mid = mix(mid, mix(vec3(0.86, 0.68, 0.76), vec3(0.76, 0.82, 1.00), org), gold);
    shade = mix(shade, mix(vec3(0.50, 0.38, 0.60), vec3(0.67, 0.76, 0.98), org), gold);
    float dim = 0.30 + 0.70 * day;
    top = mix(top, vec3(0.70, 0.73, 0.78) * dim, rain * 0.8);
    lit = mix(lit, vec3(0.66, 0.69, 0.74) * dim, rain * 0.8);
    mid = mix(mid, vec3(0.52, 0.55, 0.61) * dim, rain * 0.8);
    shade = mix(shade, vec3(0.40, 0.43, 0.49) * dim, rain * 0.8);

    int face = int(faceInfo.r * 255.0 / 40.0 + 0.5);
    float lvf = faceInfo.g;
    vec2 sxz = sunDir.xz / (length(sunDir.xz) + 1e-4);
    vec3 col;
    if (face == 1) {
        col = mix(shade, mid, full > 0.5 ? 0.35 : 0.45);                 // underside: cool, soft shadow
    } else if (face == 0) {
        col = mix(lit, top, 0.5 + 0.5 * lvf);                           // tops: brightest on the highest steps
    } else {
        vec2 n = face == 2 ? vec2(1, 0) : face == 3 ? vec2(-1, 0) : face == 4 ? vec2(0, 1) : vec2(0, -1);
        float sunFace = dot(n, sxz);
        vec3 sideCol = sunFace > 0.3 ? lit : sunFace > -0.3 ? mid : shade;
        float rel = clamp(localY / max(lvf * colH, 0.001), 0.0, 1.0);
        col = mix(sideCol, mix(sideCol, top, 0.45), step(0.55, rel));   // light band on the upper half of each side
    }

    // distance haze into the sky's own horizon colour (yellow toward the sun at sunset, like the reference)
    float dist = length(relPos.xz);
    vec2 dxz = relPos.xz / (dist + 1e-4);
    float side = pow(clamp(0.5 + 0.5 * dot(dxz, sxz), 0.0, 1.0), 1.4);
    vec3 haze = mix(hazeAway, hazeSun, side);
    float f = clamp(dist / fadeEnd, 0.0, 1.0);
    float hz = full > 0.5 ? smoothstep(0.08, 1.0, f) * 0.88 : f * f * 0.75;
    col = mix(col, haze, hz);
    float a = 1.0 - smoothstep(fadeStart, fadeEnd, dist);
    if (a < 0.01) {
        discard;
    }
    fragColor = vec4(col, a);
}
