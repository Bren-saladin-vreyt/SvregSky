#version 330

// SvregSky 3D clouds: stepped voxel cumulus with flat toon shading in the Cirrus sky palette.
// Color attribute: r = face (0 top, 1 bottom, 2 +x, 3 -x, 4 +z, 5 -z) * 40 / 255, g = column height / max height

#moj_import <minecraft:dynamictransforms.glsl>

in vec3 relPos;
flat in vec4 faceInfo;
in float localY;

out vec4 fragColor;

void main() {
    vec3 sunDir = TextureMat[0].xyz;
    float day = TextureMat[1].x;
    float gold = TextureMat[1].y;
    float rain = TextureMat[1].w;
    float org = ColorModulator.w;
    float fadeStart = TextureMat[2].x;
    float fadeEnd = TextureMat[2].y;
    float colH = TextureMat[2].z;

    // palettes: night -> day, then golden hour (rose over magenta, periwinkle-white over orange like the reference)
    vec3 lit = mix(vec3(0.36, 0.38, 0.55), vec3(1.00, 1.00, 0.99), day);
    vec3 mid = mix(vec3(0.22, 0.23, 0.36), vec3(0.84, 0.91, 1.00), day);
    vec3 shade = mix(vec3(0.12, 0.12, 0.22), vec3(0.70, 0.79, 0.98), day);
    vec3 haze = mix(vec3(0.14, 0.16, 0.44), vec3(0.62, 0.84, 0.98), day);
    lit = mix(lit, mix(vec3(1.00, 0.90, 0.78), vec3(0.94, 0.96, 1.00), org), gold);
    mid = mix(mid, mix(vec3(0.86, 0.68, 0.76), vec3(0.73, 0.80, 1.00), org), gold);
    shade = mix(shade, mix(vec3(0.48, 0.36, 0.58), vec3(0.62, 0.68, 0.98), org), gold);
    haze = mix(haze, mix(vec3(0.86, 0.40, 0.46), vec3(0.96, 0.66, 0.24), org), gold);
    float dim = 0.30 + 0.70 * day;
    lit = mix(lit, vec3(0.66, 0.69, 0.74) * dim, rain * 0.8);
    mid = mix(mid, vec3(0.52, 0.55, 0.61) * dim, rain * 0.8);
    shade = mix(shade, vec3(0.38, 0.41, 0.47) * dim, rain * 0.8);
    haze = mix(haze, vec3(0.50, 0.54, 0.60) * dim, rain * 0.8);

    int face = int(faceInfo.r * 255.0 / 40.0 + 0.5);
    float lvf = faceInfo.g;
    vec3 col;
    if (face == 1) {
        col = mix(shade, mid, 0.45);                         // underside: cool shadow
    } else if (face == 0) {
        col = mix(mid, lit, 0.55 + 0.45 * lvf);              // tops: brightest on the highest steps
    } else {
        vec3 n = face == 2 ? vec3(1, 0, 0) : face == 3 ? vec3(-1, 0, 0) : face == 4 ? vec3(0, 0, 1) : vec3(0, 0, -1);
        vec2 sxz = sunDir.xz / (length(sunDir.xz) + 1e-4);
        float sunFace = dot(n.xz, sxz);
        vec3 sideCol = sunFace > 0.3 ? lit * vec3(1.0, 0.98, 0.95) : sunFace > -0.3 ? mid : shade;
        float rel = clamp(localY / max(lvf * colH, 0.001), 0.0, 1.0);
        col = mix(sideCol, mix(sideCol, lit, 0.45), step(0.55, rel)); // light band on the upper half of each side
    }

    float dist = length(relPos.xz);
    float fog = clamp(dist / fadeEnd, 0.0, 1.0);
    col = mix(col, haze, fog * fog * 0.75);
    float a = 1.0 - smoothstep(fadeStart, fadeEnd, dist);
    if (a < 0.01 && TextureMat[3].w < 0.5) {
        discard;
    }
    fragColor = vec4(col, a);
    if (TextureMat[3].w > 0.5) {
        fragColor = vec4(1.0, 0.0, 0.0, 1.0); // debug
    }
}
