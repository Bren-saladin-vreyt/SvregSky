#version 330

precision highp float;

// SvregSky "3D Ghibli" clouds: puffy voxel cumulus, toon-lit per puff in four bands.
// Color: rgb = the puff's normal (0.5 = 0), a = face * 40 + self-shadow * 5 (face 0 top, 1 bottom, 2 +x, 3 -x, 4 +z, 5 -z)
// TextureMat[0] = sun dir, layer height | [1] = day, gold, night, rain | [2] = haze away from the sun | [3] = haze toward the sun (x, z, w)
// ColorModulator = rise, fade start, fade end, orange golden hour

layout(std140) uniform DynamicTransforms {
    highp mat4 ModelViewMat;
    highp vec4 ColorModulator;
    highp vec3 ModelOffset;
    highp mat4 TextureMat;
};

in highp vec3 relPos;
flat in highp vec4 info;
in highp float localY;

out highp vec4 fragColor;

void main() {
    highp vec3 sunDir = normalize(TextureMat[0].xyz);
    highp float layerH = TextureMat[0].w;
    highp float day = TextureMat[1].x;
    highp float gold = TextureMat[1].y;
    highp float night = TextureMat[1].z;
    highp float rain = TextureMat[1].w;
    highp vec3 hazeAway = TextureMat[2].xyz;
    highp vec3 hazeSun = vec3(TextureMat[3].x, TextureMat[3].z, TextureMat[3].w);
    highp float fadeStart = ColorModulator.y;
    highp float fadeEnd = ColorModulator.z;
    highp float org = ColorModulator.w;

    // four toon tones: deep shade, shade, mid, lit (day -> golden hour: purple bodies, cream edges)
    highp vec3 c0 = mix(vec3(0.58, 0.72, 0.96), mix(vec3(0.42, 0.30, 0.52), vec3(0.55, 0.50, 0.72), org), gold);
    highp vec3 c1 = mix(vec3(0.74, 0.85, 1.00), mix(vec3(0.58, 0.42, 0.62), vec3(0.72, 0.68, 0.86), org), gold);
    highp vec3 c2 = mix(vec3(0.89, 0.95, 1.00), mix(vec3(0.90, 0.66, 0.60), vec3(0.95, 0.84, 0.84), org), gold);
    highp vec3 c3 = mix(vec3(1.00, 1.00, 1.00), mix(vec3(1.00, 0.86, 0.64), vec3(1.00, 0.95, 0.84), org), gold);
    // night: moonlit blue-grey
    highp float nk = night * (1.0 - day) * (1.0 - gold);
    c0 = mix(c0, vec3(0.10, 0.11, 0.20), nk);
    c1 = mix(c1, vec3(0.16, 0.18, 0.30), nk);
    c2 = mix(c2, vec3(0.24, 0.27, 0.42), nk);
    c3 = mix(c3, vec3(0.36, 0.40, 0.58), nk);
    // rain: flat greys
    highp float dim = 0.30 + 0.70 * max(day, gold);
    c0 = mix(c0, vec3(0.40, 0.43, 0.49) * dim, rain * 0.8);
    c1 = mix(c1, vec3(0.52, 0.55, 0.61) * dim, rain * 0.8);
    c2 = mix(c2, vec3(0.62, 0.65, 0.71) * dim, rain * 0.8);
    c3 = mix(c3, vec3(0.70, 0.73, 0.78) * dim, rain * 0.8);

    highp float a8 = info.a * 255.0;
    int face = int(floor((a8 + 0.5) / 40.0));
    highp float sh = floor((a8 - float(face) * 40.0 + 0.5) / 5.0);
    highp vec3 n = face == 0 ? vec3(0, 1, 0) : face == 1 ? vec3(0, -1, 0) : face == 2 ? vec3(1, 0, 0)
                 : face == 3 ? vec3(-1, 0, 0) : face == 4 ? vec3(0, 0, 1) : vec3(0, 0, -1);
    highp vec3 pn = info.rgb * 255.0 - 128.0;
    pn = length(pn) > 1.0 ? normalize(pn) : n;
    highp vec3 rd = normalize(relPos);

    highp float l = dot(normalize(pn * 0.8 + n * 0.2), sunDir) * 0.5 + 0.5;
    highp float up = pn.y * 0.5 + 0.5;
    highp float hy = clamp(localY / max(layerH, 1.0), 0.0, 1.0);
    highp float v = 0.05 + 0.62 * l + 0.25 * up + 0.18 * hy - 0.07 * sh;
    // silver / golden rim: puff edges seen side-on that face the light glow (backlit clouds)
    highp float side = 1.0 - abs(dot(pn, rd));
    highp float rim = smoothstep(0.55, 0.8, side) * smoothstep(-0.25, 0.25, dot(pn, sunDir)) * smoothstep(0.3, 0.9, dot(rd, sunDir) * 0.5 + 0.5);
    v = max(v, mix(v, 0.8, rim * (1.0 - rain)));
    highp vec3 col = v > 0.74 ? c3 : (v > 0.55 ? c2 : (v > 0.36 ? c1 : c0));
    // warm light bounced up from the land onto the bellies at sunset
    if (face == 1) {
        col = mix(col, mix(vec3(0.85, 0.45, 0.45), vec3(0.95, 0.62, 0.70), org), 0.30 * gold);
    }

    // distance haze into the sky's own horizon colour
    highp float dist = length(relPos.xz);
    highp vec2 sxz = sunDir.xz / (length(sunDir.xz) + 1e-4);
    highp vec2 dxz = relPos.xz / (dist + 1e-4);
    highp float sd = pow(clamp(0.5 + 0.5 * dot(dxz, sxz), 0.0, 1.0), 1.4);
    highp vec3 haze = mix(hazeAway, hazeSun, sd);
    highp float f = clamp(dist / fadeEnd, 0.0, 1.0);
    col = mix(col, haze, smoothstep(0.15, 1.0, f) * 0.85);
    highp float a = 1.0 - smoothstep(fadeStart, fadeEnd, dist);
    if (a < 0.01) {
        discard;
    }
    fragColor = vec4(col, a);
}
