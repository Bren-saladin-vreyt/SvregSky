#version 330

precision highp float;

// SvregSky ambient particles. UV.x selects the shape: [0,1] soft glow, [2,3] leaf / petal, [4,5] sparkle,
// [6,7] butterfly, [8,9] ripple ring, [10,11] foam blob, [12,13] snowflake, [14] bird, [16] dragonfly,
// [18] dandelion seed, [20] fish, [22] puddle, [24] light streak on water, [26] lamp halo

layout(std140) uniform DynamicTransforms {
    highp mat4 ModelViewMat;
    highp vec4 ColorModulator;
    highp vec3 ModelOffset;
    highp mat4 TextureMat;
};

in highp vec2 uv;
in highp vec4 vColor;

out highp vec4 fragColor;

void main() {
    highp float kind = floor(uv.x * 0.5);
    highp vec2 p = vec2(uv.x - kind * 2.0, uv.y) * 2.0 - 1.0;
    highp float r2 = dot(p, p);
    highp vec4 c = vColor;
    if (kind < 0.5) {
        // firefly: bright core + soft halo
        highp float a = exp(-r2 * 9.0) + 0.35 * exp(-r2 * 2.5);
        c.rgb = mix(c.rgb, vec3(1.0, 1.0, 0.85), exp(-r2 * 30.0));
        c.a *= clamp(a, 0.0, 1.0);
    } else if (kind < 1.5) {
        // leaf / petal: pointed oval with a darker middle vein, flat toon colour
        highp float leaf = p.x * p.x + (p.y * p.y) / (0.30 * (1.0 - 0.45 * p.x * p.x));
        if (leaf > 1.0) {
            discard;
        }
        highp float vein = 1.0 - smoothstep(0.0, 0.12, abs(p.y)) * 1.0;
        c.rgb *= 1.0 - 0.18 * vein * step(-0.8, p.x) * step(p.x, 0.8);
        c.rgb *= 0.88 + 0.12 * step(0.0, p.y); // two-tone shading
    } else if (kind > 2.5 && kind < 3.5) {
        // butterfly: two pairs of rounded wings and a thin body
        highp vec2 q = vec2(abs(p.x), p.y);
        highp float upper = length((q - vec2(0.45, -0.25)) / vec2(0.5, 0.55));
        highp float lower = length((q - vec2(0.35, 0.45)) / vec2(0.35, 0.4));
        highp float body = step(abs(p.x), 0.08) * step(abs(p.y), 0.7);
        if (min(upper, lower) > 1.0 && body < 0.5) {
            discard;
        }
        c.rgb *= body > 0.5 ? 0.35 : (upper < 0.55 || lower < 0.5 ? 1.08 : 0.92);
    } else if (kind > 3.5 && kind < 4.5) {
        // ripple: thin ring
        highp float r = sqrt(r2);
        c.a *= 1.0 - smoothstep(0.0, 0.12, abs(r - 0.8));
    } else if (kind > 4.5 && kind < 5.5) {
        // foam: lumpy soft blob
        highp float ang = atan(p.y, p.x);
        highp float edge = 0.75 + 0.12 * sin(ang * 5.0 + c.r * 20.0) + 0.08 * sin(ang * 9.0);
        c.a *= 1.0 - smoothstep(edge - 0.25, edge, sqrt(r2));
        c.rgb = vec3(1.0);
    } else if (kind > 5.5 && kind < 6.5) {
        // snowflake: soft round flake with a bright middle
        c.a *= 1.0 - smoothstep(0.3, 1.0, sqrt(r2));
    } else if (kind > 6.5 && kind < 7.5) {
        // bird: a soft "m" of two curved wings; red = wing beat (up/down)
        highp float beat = c.r * 2.0 - 1.0;
        highp float x = abs(p.x);
        highp float wingY = beat * 0.45 * x - 0.35 * x * x * (1.0 - abs(beat)) + 0.1;
        highp float th = 0.16 * (1.0 - 0.7 * x);
        highp float body = exp(-dot(p * vec2(4.0, 3.0), p * vec2(4.0, 3.0)));
        if (abs(p.y - wingY) > th && body < 0.4) {
            discard;
        }
        c.rgb = vec3(c.g * 0.0 + 0.18, 0.20, 0.27);
    } else if (kind > 7.5 && kind < 8.5) {
        // dragonfly: slim body along x, four clear wings; red = wing flutter
        highp float bodyD = abs(p.y) < 0.07 && p.x > -0.9 && p.x < 0.95 ? 1.0 : 0.0;
        highp float head = step(length(p - vec2(0.85, 0.0)), 0.14);
        highp float fl = 0.75 + 0.25 * c.r;
        highp vec2 w1 = (vec2(p.x - 0.25, abs(p.y) - 0.42 * fl) / vec2(0.18, 0.40 * fl));
        highp vec2 w2 = (vec2(p.x + 0.05, abs(p.y) - 0.38 * fl) / vec2(0.16, 0.36 * fl));
        highp float wing = step(min(dot(w1, w1), dot(w2, w2)), 1.0);
        if (bodyD < 0.5 && head < 0.5 && wing < 0.5) {
            discard;
        }
        highp vec3 bodyC = vec3(c.r * 0.0 + 0.25, c.g, c.b);
        c.rgb = (bodyD + head > 0.5) ? vec3(0.2, 0.25, 0.3) + vec3(0.0, c.g, c.b) * 0.7 : vec3(0.85, 0.92, 1.0);
        c.a *= (bodyD + head > 0.5) ? 1.0 : 0.45;
    } else if (kind > 8.5 && kind < 9.5) {
        // dandelion seed: a fluffy white star of fine hairs on a short stem
        highp float ang = atan(p.y - 0.2, p.x);
        highp float rr = length(p - vec2(0.0, 0.2));
        highp float hairs = (1.0 - smoothstep(0.0, 0.25, abs(sin(ang * 7.0)) * rr * 3.0)) * step(rr, 0.75) * step(0.0, p.y - 0.05);
        highp float stem = step(abs(p.x), 0.05) * step(p.y, 0.2) * step(-0.75, p.y);
        highp float a = max(max(hairs * 0.85, stem), exp(-rr * rr * 60.0));
        c.a *= a;
    } else if (kind > 9.5 && kind < 10.5) {
        // fish (side view): an oval body with a forked tail, silver-blue with a light belly
        highp float bodyF = dot(p * vec2(1.4, 2.6), p * vec2(1.4, 2.6));
        highp float tail = step(p.x, -0.55) * step(abs(p.y), (-p.x - 0.55) * 1.4) * step(-1.0, p.x);
        if (bodyF > 1.0 && tail < 0.5) {
            discard;
        }
        c.rgb *= p.y < -0.05 ? 1.25 : 0.9;
        if (length(p - vec2(0.45, 0.08)) < 0.06) {
            c.rgb = vec3(0.1);
        }
    } else if (kind > 10.5 && kind < 11.5) {
        // puddle: soft irregular patch reflecting the sky (green/blue = sky colour, red = shape seed)
        highp float ang = atan(p.y, p.x);
        highp float edge = 0.78 + 0.12 * sin(ang * 3.0 + c.r * 30.0) + 0.06 * sin(ang * 7.0 + c.r * 11.0);
        highp float rr = sqrt(r2);
        highp float inside = 1.0 - smoothstep(edge - 0.12, edge, rr);
        highp vec3 sky = vec3(0.55 + c.g * 0.0, c.g, c.b);
        c.rgb = mix(vec3(0.20, 0.22, 0.26), sky, 0.55 + 0.35 * smoothstep(0.2, -0.6, p.y));
        c.rgb += vec3(0.35) * smoothstep(0.08, 0.0, abs(rr - edge + 0.2)) * step(0.2, p.x - p.y); // a glint along the rim
        c.a *= inside;
    } else if (kind > 12.5) {
        // lamp halo: a wide, very soft warm glow with no hard core
        c.a *= exp(-r2 * 2.6) * (1.0 - smoothstep(0.7, 1.0, sqrt(r2)));
    } else if (kind > 11.5) {
        // light streak on the water: long soft glow, brighter in the middle
        highp float s = exp(-p.x * p.x * 6.0) * exp(-p.y * p.y * 1.2);
        c.a *= s;
    } else {
        // sparkle: four-point star
        highp float star = max(0.0, 1.0 - abs(p.x) * 7.0) * (1.0 - abs(p.y)) + max(0.0, 1.0 - abs(p.y) * 7.0) * (1.0 - abs(p.x));
        highp float a = clamp(star + exp(-r2 * 14.0), 0.0, 1.0);
        c.a *= a;
    }
    if (c.a < 0.01) {
        discard;
    }
    fragColor = c * ColorModulator;
}
