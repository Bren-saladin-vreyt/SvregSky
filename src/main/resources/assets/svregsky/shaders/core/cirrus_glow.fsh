#version 330

precision highp float;

// SvregSky soft glow (bloom) and photo-mode depth of field, from a copy of the scene.
// TextureMat[2] = bloom strength, depth-of-field strength, 0, 0

layout(std140) uniform DynamicTransforms {
    highp mat4 ModelViewMat;
    highp vec4 ColorModulator;
    highp vec3 ModelOffset;
    highp mat4 TextureMat;
};

uniform highp sampler2D Sampler0; // scene colour copy
uniform highp sampler2D Sampler1; // depth

in highp vec2 uv;

out highp vec4 fragColor;

highp vec3 sceneAt(ivec2 p, ivec2 size) {
    return texelFetch(Sampler0, clamp(p, ivec2(0), size - 1), 0).rgb;
}

void main() {
    ivec2 size = textureSize(Sampler0, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    highp vec3 base = sceneAt(p, size);
    highp float bloom = TextureMat[2].x;
    highp float dof = TextureMat[2].y;
    highp float focus = texelFetch(Sampler1, size / 2, 0).r; // focus on whatever is in the middle of the screen
    highp float scale = float(size.y) / 480.0;

    highp vec3 col = base;
    if (dof > 0.0) {
        // blur grows with the distance from the focus (reverse-Z: relative depth difference)
        highp float d = texelFetch(Sampler1, p, 0).r;
        highp float coc = clamp(abs(d - focus) / max(max(d, focus), 1e-5) * 1.6, 0.0, 1.0) * dof;
        if (coc > 0.02) {
            highp vec3 sum = base;
            highp float wsum = 1.0;
            for (int i = 0; i < 16; i++) {
                highp float fi = float(i);
                highp float ang = fi * 2.39996;
                highp float r = sqrt((fi + 0.5) / 16.0) * coc * 7.0 * scale;
                ivec2 o = ivec2(round(vec2(cos(ang), sin(ang)) * r));
                sum += sceneAt(p + o, size);
                wsum += 1.0;
            }
            col = sum / wsum;
        }
    }
    if (bloom > 0.0) {
        // gather bright neighbours on a sparse spiral: a soft halo around sun, lamps and glowing things
        highp vec3 glow = vec3(0.0);
        for (int i = 0; i < 20; i++) {
            highp float fi = float(i);
            highp float ang = fi * 2.39996 + 0.7;
            highp float r = (2.0 + fi * 1.4) * scale;
            highp vec3 s = sceneAt(p + ivec2(round(vec2(cos(ang), sin(ang)) * r)), size);
            highp float l = dot(s, vec3(0.30, 0.59, 0.11));
            glow += s * smoothstep(0.72, 0.98, l) * (1.0 - fi / 24.0);
        }
        col += glow / 20.0 * bloom * 1.6;
    }
    fragColor = vec4(col, 1.0);
}
