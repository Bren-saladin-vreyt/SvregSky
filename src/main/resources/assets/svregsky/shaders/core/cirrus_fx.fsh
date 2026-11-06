#version 330

precision highp float;

// SvregSky ambient particles. UV.x selects the shape: [0,1] soft glow, [2,3] leaf / petal, [4,5] sparkle,
// [6,7] butterfly, [8,9] ripple ring, [10,11] foam blob, [12,13] snowflake, [14] bird, [16] dragonfly,
// [18] dandelion seed, [20] fish, [22] puddle, [24] light streak on water, [26] lamp halo, [28] sky lantern, [30] sparrow, [32] window light, [34] spirit companion, [36] kite, [38] kite string

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
    } else if (kind > 18.5) {
        // kite string: a thin soft line (the quad runs along the string)
        c.a *= 1.0 - smoothstep(0.2, 1.0, abs(p.x));
    } else if (kind > 17.5) {
        // kite: a diamond in two colours with crossed sticks, and a ribbon tail with little bows
        highp vec2 q = p;
        highp float dia = abs(q.x) / 0.62 + abs(q.y - 0.25) / (q.y > 0.25 ? 0.72 : 0.62);
        highp float tailX = 0.08 * sin(q.y * 9.0 + c.a * 0.0);
        bool tail = q.y < -0.30 && abs(q.x - tailX) < 0.025;
        highp vec2 bq = vec2(q.x - tailX, fract(q.y * 3.2) - 0.5);
        bool bow = q.y < -0.40 && abs(bq.y) < 0.12 && abs(bq.x) < 0.11 * (1.0 - abs(bq.y) / 0.12) + 0.02;
        if (dia > 1.0 && !tail && !bow) {
            discard;
        }
        highp vec3 col = c.rgb;
        if (dia <= 1.0) {
            col = (q.x * (q.y - 0.25) > 0.0) ? c.rgb : mix(c.rgb, vec3(1.0, 0.97, 0.90), 0.75);
            if (abs(q.x) < 0.025 || abs(q.y - 0.25) < 0.025) col = vec3(0.35, 0.25, 0.18);
            col *= 0.85 + 0.15 * step(0.0, q.x + q.y);
        } else {
            col = bow ? mix(c.rgb, vec3(1.0), 0.3) : vec3(0.95, 0.92, 0.85);
        }
        c.rgb = col;
    } else if (kind > 16.5) {
        // the little spirit (roh kapuk): red = pose (0 floating, 1 asleep, 2 leaf umbrella), green = blink (>= 128)
        // + facing left (+64), blue = daylight (night makes it glow softly)
        highp float pose = floor(c.r * 3.0 + 0.5);
        highp float gi = floor(c.g * 255.0 + 0.5);
        bool blink = gi >= 127.5;
        bool left = mod(gi, 128.0) >= 63.5;
        highp float dayL = c.b;
        highp vec2 q = vec2(left ? -p.x : p.x, p.y) * 1.15;
        highp vec4 b = vec4(0.0);
        if (pose > 1.5) {
            // big leaf held up as an umbrella
            highp vec2 lq = (q - vec2(0.05, 0.62)) / vec2(0.78, 0.36);
            if (lq.y > -0.15 && length(lq) < 1.0) {
                highp vec3 lc = mix(vec3(0.30, 0.62, 0.30), vec3(0.50, 0.80, 0.40), smoothstep(-0.2, 0.6, lq.y));
                lc = mix(lc, vec3(0.22, 0.45, 0.22), smoothstep(0.85, 1.0, length(lq)) * 0.6);
                b = vec4(mix(lc, vec3(0.70, 0.88, 0.55), smoothstep(0.03, 0.0, abs(lq.x))), 1.0);
            }
            if (abs(q.x - 0.05) < 0.025 && q.y > 0.25 && q.y < 0.62) b = vec4(0.36, 0.55, 0.28, 1.0);
        }
        // fluffy round body
        highp float ang = atan(q.y, q.x);
        highp float rr = 0.46 + 0.03 * sin(ang * 5.0 + 1.3) + 0.012 * sin(ang * 13.0) + 0.015 * sin(ang * 3.0 - 0.7);
        highp vec2 bp = q - vec2(0.0, -0.08);
        highp float d = length(bp / vec2(1.0, 0.92));
        if (d < rr) {
            highp float lit = 0.62 + 0.5 * bp.y - 0.35 * bp.x;
            highp vec3 bc = mix(vec3(0.80, 0.79, 0.93), vec3(1.0, 0.98, 0.92), smoothstep(0.42, 0.50, lit));
            bc = mix(bc, vec3(1.0, 1.0, 0.82), (1.0 - dayL) * 0.35);
            b = vec4(bc, 1.0);
            for (int s = 0; s < 2; s++) {
                highp float sx = s == 0 ? -1.0 : 1.0;
                highp vec2 e = bp - vec2(sx * 0.15, 0.05);
                if (pose > 0.5 && pose < 1.5 || blink) {
                    if (abs(length(e - vec2(0.0, 0.05)) - 0.07) < 0.016 && e.y < 0.02) b.rgb = vec3(0.15, 0.13, 0.18);
                } else if (length(e / vec2(0.055, 0.08)) < 1.0) {
                    b.rgb = vec3(0.10, 0.09, 0.14);
                    if (length(e - vec2(0.018, 0.03)) < 0.02) b.rgb = vec3(1.0);
                }
                highp vec2 bl = bp - vec2(sx * 0.27, -0.07);
                if (length(bl / vec2(0.075, 0.04)) < 1.0) b.rgb = mix(b.rgb, vec3(1.0, 0.62, 0.66), 0.75);
            }
            highp vec2 mo = bp - vec2(0.0, -0.06);
            if (pose > 0.5 && pose < 1.5) {
                if (length(mo / vec2(0.03, 0.025)) < 1.0) b.rgb = vec3(0.45, 0.25, 0.30);
            } else if (abs(length(mo - vec2(0.0, 0.035)) - 0.045) < 0.012 && mo.y < 0.02) {
                b.rgb = vec3(0.30, 0.18, 0.22);
            }
        }
        if (pose < 1.5) {
            // a little sprout on top
            if (abs(q.x - 0.02 * sin(q.y * 8.0)) < 0.02 && q.y > 0.30 && q.y < 0.52) b = vec4(0.36, 0.58, 0.28, 1.0);
            highp vec2 l1 = mat2(0.8, 0.6, -0.6, 0.8) * (q - vec2(-0.10, 0.53));
            if (length(l1 / vec2(0.12, 0.055)) < 1.0) b = vec4(mix(vec3(0.42, 0.72, 0.34), vec3(0.58, 0.85, 0.42), step(0.0, l1.y)), 1.0);
            highp vec2 l2 = mat2(0.8, -0.6, 0.6, 0.8) * (q - vec2(0.12, 0.56));
            if (length(l2 / vec2(0.13, 0.06)) < 1.0) b = vec4(mix(vec3(0.42, 0.72, 0.34), vec3(0.58, 0.85, 0.42), step(0.0, l2.y)), 1.0);
        }
        if (pose > 0.5 && pose < 1.5) {
            // little z's
            for (int i = 0; i < 2; i++) {
                highp float fi = float(i);
                highp vec2 z = (q - vec2(0.45 + fi * 0.17, 0.42 + fi * 0.2)) / (0.07 + fi * 0.03);
                if (abs(z.x) < 1.0 && abs(z.y) < 1.0 && (abs(z.y - 0.85) < 0.18 || abs(z.y + 0.85) < 0.18 || abs(z.y - z.x * 0.85) < 0.2)) b = vec4(0.55, 0.58, 0.75, 1.0);
            }
        }
        if (b.a < 0.5) {
            discard;
        }
        c.rgb = b.rgb * (0.45 + 0.55 * max(dayL, 0.75 * (1.0 - dayL) + 0.25));
    } else if (kind > 15.5) {
        // window light: a soft-edged shaft, brightest just inside the window, fading into the room (uv.y = along it)
        highp float v = uv.y;
        highp float edge = 1.0 - smoothstep(0.55, 1.0, abs(p.x));
        highp float fade = smoothstep(0.0, 0.06, v) * pow(1.0 - v, 1.3);
        highp float streak = 0.85 + 0.15 * sin(p.x * 9.0 + v * 3.0);
        c.a *= edge * fade * streak;
    } else if (kind > 14.5) {
        // sparrow on the ground, side view: red = peck (head down), green > 0.5 = facing left, blue = daylight
        highp vec2 q = vec2(c.g > 0.5 ? -p.x : p.x, p.y);
        highp float peck = c.r;
        highp float lit = 0.35 + 0.65 * c.b;
        highp vec2 hc = vec2(0.40 + 0.18 * peck, 0.20 - 0.50 * peck);
        highp vec2 bq = q - vec2(-0.02, -0.18);
        highp float bodyD = dot(bq / vec2(0.50, 0.33), bq / vec2(0.50, 0.33));
        highp float headD = length(q - hc) / 0.21;
        highp vec2 bk = q - hc - vec2(0.22, -0.02 - 0.10 * peck);
        highp float beak = step(bk.x, 0.0) * step(-0.12, bk.x) * step(abs(bk.y), 0.05 * (1.0 + bk.x / 0.12) + 0.004);
        highp vec2 tq = q - vec2(-0.45, -0.05);
        highp float tail = step(tq.x, 0.0) * step(-0.50, tq.x) * step(abs(tq.y - (-tq.x) * 0.35), 0.06 + 0.10 * (-tq.x));
        highp float legs = step(abs(abs(q.x - 0.02) - 0.08), 0.022) * step(-0.78, q.y) * step(q.y, -0.42);
        if (bodyD > 1.0 && headD > 1.0 && beak < 0.5 && tail < 0.5 && legs < 0.5) {
            discard;
        }
        highp vec3 back = vec3(0.55, 0.39, 0.26), belly = vec3(0.86, 0.79, 0.66), wing = vec3(0.40, 0.27, 0.18), cap = vec3(0.47, 0.37, 0.31);
        highp vec3 col = back;
        if (bodyD <= 1.0) {
            col = bq.y < -0.06 ? belly : back;
            highp vec2 wq = bq - vec2(-0.08, 0.02);
            if (dot(wq / vec2(0.32, 0.17), wq / vec2(0.32, 0.17)) < 1.0) {
                col = mix(wing, back, step(0.5, fract((wq.x - wq.y) * 6.0)) * 0.6); // streaked wing
            }
        }
        if (tail > 0.5 && bodyD > 1.0) {
            col = wing;
        }
        if (headD <= 1.0) {
            col = (q.y - hc.y) > -0.04 ? cap : belly;
            if (length(q - hc - vec2(0.08, 0.03)) < 0.045) {
                col = vec3(0.08);
            }
        }
        if (beak > 0.5 && headD > 1.0) {
            col = vec3(0.32, 0.27, 0.22);
        }
        if (legs > 0.5 && bodyD > 1.0) {
            col = vec3(0.45, 0.36, 0.30);
        }
        c.rgb = col * lit;
    } else if (kind > 13.5) {
        // sky lantern: a paper lantern, wider at the top, glowing brighter toward its open bottom
        highp float w = 0.42 + 0.14 * (-p.y);
        if (abs(p.x) > w || abs(p.y) > 0.82) {
            discard;
        }
        highp float glow = smoothstep(-0.9, 0.9, p.y);
        c.rgb = mix(c.rgb * vec3(1.0, 0.82, 0.70), vec3(1.0, 0.95, 0.70), glow * 0.8);
        c.rgb *= 1.0 - 0.18 * smoothstep(0.75, 0.82, abs(p.y)); // dark rims top and bottom
        c.rgb *= 1.0 - 0.10 * smoothstep(w - 0.08, w, abs(p.x));
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
