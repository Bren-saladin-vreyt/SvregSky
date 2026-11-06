#version 330

precision highp float;

// SvregSky pixel art: big screen pixels, brightness in a few bands (each colour keeps its hue), ordered dithering
// only where two bands meet, warm lights and cool darks -- like a hand-made pixel painting. Colour only (phone-safe).
// TextureMat[0] = pixel size (screen px), bands, dither (0..1), 0

layout(std140) uniform DynamicTransforms {
    highp mat4 ModelViewMat;
    highp vec4 ColorModulator;
    highp vec3 ModelOffset;
    highp mat4 TextureMat;
};

uniform highp sampler2D Sampler0; // scene colour copy (bilinear)

in highp vec2 uv;

out highp vec4 fragColor;

const highp vec3 LW = vec3(0.30, 0.59, 0.11);

highp float bayer4(highp vec2 c) {
    int x = int(mod(c.x, 4.0)), y = int(mod(c.y, 4.0));
    int i = y * 4 + x;
    // 4x4 Bayer matrix: 0 8 2 10 / 12 4 14 6 / 3 11 1 9 / 15 7 13 5
    highp float m = 0.0;
    if (i == 0) m = 0.0; else if (i == 1) m = 8.0; else if (i == 2) m = 2.0; else if (i == 3) m = 10.0;
    else if (i == 4) m = 12.0; else if (i == 5) m = 4.0; else if (i == 6) m = 14.0; else if (i == 7) m = 6.0;
    else if (i == 8) m = 3.0; else if (i == 9) m = 11.0; else if (i == 10) m = 1.0; else if (i == 11) m = 9.0;
    else if (i == 12) m = 15.0; else if (i == 13) m = 7.0; else if (i == 14) m = 13.0; else m = 5.0;
    return (m + 0.5) / 16.0;
}

void main() {
    highp vec2 size = vec2(textureSize(Sampler0, 0));
    highp float P = max(TextureMat[0].x, 1.0);
    highp float levels = TextureMat[0].y;
    highp float dith = TextureMat[0].z;
    // every P x P screen pixels share one colour, taken from a small average so it doesn't sparkle
    highp vec2 cell = floor(gl_FragCoord.xy / P);
    highp vec2 cst = (cell + 0.5) * P / size;
    highp vec2 q = 0.25 * P / size;
    highp vec3 c = texture(Sampler0, cst).rgb * 0.5
            + (texture(Sampler0, cst + q).rgb + texture(Sampler0, cst - q).rgb
            + texture(Sampler0, cst + vec2(q.x, -q.y)).rgb + texture(Sampler0, cst + vec2(-q.x, q.y)).rgb) * 0.125;
    // painter's palette: a touch more contrast and colour, warm lights, cool darks
    highp float l = dot(c, LW);
    c = mix(vec3(l), c, 1.15);
    c = mix(c, c * vec3(1.06, 1.0, 0.90), smoothstep(0.45, 0.9, l));
    c = mix(c, c * vec3(0.88, 0.94, 1.12), 1.0 - smoothstep(0.1, 0.45, l));
    // brightness bands, dithered only at the step between two bands (dots are whole big pixels)
    highp float t = (bayer4(cell) - 0.5) * dith;
    highp float L = dot(c, LW);
    highp float lq = floor(L * levels + 0.5 + t) / levels;
    c = c * (lq / max(L, 1e-3));
    // and a gentle per-channel snap so the palette stays limited
    c = mix(c, floor(c * 16.0 + 0.5) / 16.0, 0.6);
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
