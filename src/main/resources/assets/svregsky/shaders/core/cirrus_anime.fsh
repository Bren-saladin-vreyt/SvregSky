#version 330

precision highp float;

// SvregSky anime look, from a copy of the scene (colour only, so it works on phones):
//  - coloured shadows: dark areas lean toward blue / violet instead of grey-black, like anime shadow colours
//  - anime outline: lines where the colour changes a lot, 1..3 px, that can "boil" (wobble a little and redraw
//    8 times a second) like hand-drawn lines
//  - dream glow: a soft Orton glow, the gentle haze of old Ghibli film
// TextureMat[0] = shadow tint rgb, shadow strength
// TextureMat[1] = outline width (px, 0 = off), outline strength, boil (0/1), outline colour mode (0 auto, 1 fixed)
// TextureMat[2] = glow strength, time, 0, 0
// TextureMat[3] = fixed outline colour rgb, 0

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

highp float hash(highp vec2 p) {
    highp vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

highp float vnoise(highp vec2 p) {
    highp vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

highp vec3 at(highp vec2 st) {
    return texture(Sampler0, st).rgb;
}

// colour difference: luminance counts most, hue a bit (so a green-on-blue edge still shows)
highp float diff(highp vec3 a, highp vec3 b) {
    highp vec3 d = a - b;
    return abs(dot(d, LW)) + 0.35 * length(d - dot(d, LW));
}

void main() {
    highp vec2 size = vec2(textureSize(Sampler0, 0));
    highp vec2 px = 1.0 / size;
    highp vec2 st = gl_FragCoord.xy * px;
    highp float res = max(size.y / 720.0, 0.75);
    highp vec3 col = at(st);
    highp float tm = TextureMat[2].y;

    // ---------------------------------------------------------------- dream glow
    highp float glowK = TextureMat[2].x;
    if (glowK > 0.0) {
        highp vec3 sum = vec3(0.0);
        for (int i = 0; i < 12; i++) {
            highp float fi = float(i);
            highp float ang = fi * 2.39996 + 0.4;
            highp float r = (3.0 + fi * 1.6) * res;
            sum += at(st + vec2(cos(ang), sin(ang)) * r * px);
        }
        highp vec3 blur = sum / 12.0;
        // screen blend of the soft copy, a touch brighter where it is bright (light blooms through the haze)
        highp vec3 soft = blur * (0.55 + 0.6 * smoothstep(0.35, 0.9, dot(blur, LW)));
        highp vec3 screen = 1.0 - (1.0 - col) * (1.0 - soft * 0.65);
        col = mix(col, screen, glowK);
        // the whole picture a little softer and lifted, like film
        col = mix(col, blur, 0.18 * glowK);
    }

    // ---------------------------------------------------------------- coloured shadows
    highp float shK = TextureMat[0].w;
    if (shK > 0.0) {
        highp float l = dot(col, LW);
        highp float w = (1.0 - smoothstep(0.06, 0.48, l)) * smoothstep(0.0, 0.03, l);
        highp vec3 tinted = col * TextureMat[0].rgb;
        tinted *= l / max(dot(tinted, LW), 1e-4);          // keep the brightness, change only the colour
        tinted += TextureMat[0].rgb * 0.035 * (1.0 - smoothstep(0.0, 0.2, l)); // the deepest shade is never pure black
        col = mix(col, tinted, w * shK);
    }

    // ---------------------------------------------------------------- anime outline
    highp float ow = TextureMat[1].x;
    if (ow > 0.0) {
        highp vec2 q = gl_FragCoord.xy;
        highp float wob = 0.0;
        if (TextureMat[1].z > 0.5) {
            // boiling: the line shifts a little and is redrawn 8 times a second
            highp float frame = floor(mod(tm, 3600.0) * 8.0);
            highp vec2 cell = q / (18.0 * res);
            highp vec2 j = vec2(vnoise(cell + frame * 7.31), vnoise(cell + 19.7 + frame * 3.17)) - 0.5;
            q += j * 1.2 * res;
            wob = vnoise(cell * 1.7 + frame * 5.1);
        }
        highp vec2 s0 = q * px;
        highp float r = ow * res;
        // two rings: an edge in the scene shows at both; the grain inside a block texture mostly does not
        highp vec3 c0 = at(s0);
        highp float e1 = 0.0, e2 = 0.0;
        for (int i = 0; i < 4; i++) {
            highp float a = float(i) * 1.5708 + 0.785;
            highp vec2 d = vec2(cos(a), sin(a));
            e1 = max(e1, diff(c0, at(s0 + d * r * px)));
            e2 = max(e2, diff(c0, at(s0 + d * 2.0 * r * px)));
        }
        highp float edge = smoothstep(0.09, 0.24, min(e1, e2 * 0.85));
        edge *= TextureMat[1].y * (0.75 + 0.5 * wob);
        // only the darker side of an edge gets the line, so lines sit on the object, not in the sky
        highp float lHere = dot(c0, LW);
        highp vec3 nb = at(s0 + vec2(r, 0.0) * px) + at(s0 - vec2(r, 0.0) * px) + at(s0 + vec2(0.0, r) * px) + at(s0 - vec2(0.0, r) * px);
        edge *= smoothstep(-0.02, 0.04, dot(nb * 0.25, LW) - lHere);
        highp vec3 lineC = TextureMat[1].w > 0.5 ? TextureMat[3].rgb : col * col * vec3(0.55, 0.50, 0.62); // auto: a deep version of the colour
        col = mix(col, lineC, clamp(edge, 0.0, 1.0));
    }

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
