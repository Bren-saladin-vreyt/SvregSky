#version 330

// SvregSky screen look: thin cartoon outlines from depth edges, a time-of-day film tint and a soft vignette.
// TextureMat[0] = tint rgb, tint amount | [1] = vignette, outline strength, depth fade start, 0

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0; // main depth

in vec2 uv;

out vec4 fragColor;

float depthAt(ivec2 p, ivec2 size) {
    return texelFetch(Sampler0, clamp(p, ivec2(0), size - 1), 0).r;
}

void main() {
    vec3 tint = TextureMat[0].xyz;
    float tintA = TextureMat[0].w;
    float vig = TextureMat[1].x;
    float outline = TextureMat[1].y;

    vec3 col = tint;
    float a = tintA;

    // vignette: darker, slightly blue corners
    vec2 d = uv - 0.5;
    float v = smoothstep(0.35, 0.95, length(d * vec2(1.25, 1.0)) * 1.25) * vig;
    col = mix(col, vec3(0.06, 0.05, 0.10), v / max(a + v, 1e-4));
    a = 1.0 - (1.0 - a) * (1.0 - v);

    if (outline > 0.0) {
        ivec2 size = textureSize(Sampler0, 0);
        ivec2 p = ivec2(gl_FragCoord.xy);
        float c = depthAt(p, size);
        float n1 = depthAt(p + ivec2(1, 0), size), n2 = depthAt(p - ivec2(1, 0), size);
        float n3 = depthAt(p + ivec2(0, 1), size), n4 = depthAt(p - ivec2(0, 1), size);
        float mx = max(max(n1, n2), max(n3, n4)), mn = min(min(n1, n2), min(n3, n4));
        // reverse depth (1 near, 0 far): relative jumps in depth are relative jumps in distance
        float rel = (max(mx, c) - min(mn, c)) / max(max(mx, c), 1e-6);
        float edge = smoothstep(0.18, 0.35, rel) * smoothstep(0.0, TextureMat[1].z, max(mx, c));
        float oa = edge * outline;
        col = mix(col, vec3(0.10, 0.08, 0.14), oa / max(a + oa, 1e-4));
        a = 1.0 - (1.0 - a) * (1.0 - oa);
    }
    if (a < 0.002) {
        discard;
    }
    fragColor = vec4(col, a);
}
