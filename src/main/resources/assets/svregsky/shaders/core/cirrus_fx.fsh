#version 330

// SvregSky ambient particles. UV.x selects the shape: [0,1] firefly glow, [2,3] leaf / petal, [4,5] water sparkle.

#moj_import <minecraft:dynamictransforms.glsl>

in vec2 uv;
in vec4 vColor;

out vec4 fragColor;

void main() {
    float kind = floor(uv.x * 0.5);
    vec2 p = vec2(uv.x - kind * 2.0, uv.y) * 2.0 - 1.0;
    float r2 = dot(p, p);
    vec4 c = vColor;
    if (kind < 0.5) {
        // firefly: bright core + soft halo
        float a = exp(-r2 * 9.0) + 0.35 * exp(-r2 * 2.5);
        c.rgb = mix(c.rgb, vec3(1.0, 1.0, 0.85), exp(-r2 * 30.0));
        c.a *= clamp(a, 0.0, 1.0);
    } else if (kind < 1.5) {
        // leaf / petal: pointed oval with a darker middle vein, flat toon colour
        float leaf = p.x * p.x + (p.y * p.y) / (0.30 * (1.0 - 0.45 * p.x * p.x));
        if (leaf > 1.0) {
            discard;
        }
        float vein = 1.0 - smoothstep(0.0, 0.12, abs(p.y)) * 1.0;
        c.rgb *= 1.0 - 0.18 * vein * step(-0.8, p.x) * step(p.x, 0.8);
        c.rgb *= 0.88 + 0.12 * step(0.0, p.y); // two-tone shading
    } else {
        // sparkle: four-point star
        float star = max(0.0, 1.0 - abs(p.x) * 7.0) * (1.0 - abs(p.y)) + max(0.0, 1.0 - abs(p.y) * 7.0) * (1.0 - abs(p.x));
        float a = clamp(star + exp(-r2 * 14.0), 0.0, 1.0);
        c.a *= a;
    }
    if (c.a < 0.01) {
        discard;
    }
    fragColor = c * ColorModulator;
}
