#version 330

precision highp float;

// SvregSky raymarch clouds, anti-flicker: a cloud pixel may only change (sky <-> cloud, or to another tone) once the
// change has shown up in two frames in a row. One-frame flips -- the edge crawl of a low-res buffer when the camera
// turns or walks -- are ignored, so outlines stay crisp pixel art but hold still.
// The previous frames are followed through the camera's motion: each cloud pixel knows its distance (alpha), so its
// point in cloud space can be found in last frame's view; sky pixels follow the rotation only.
// Sampler0 = this frame's march, Sampler1 = last frame's march (raw), Sampler2 = last frame's result (this pass).
// ModelViewMat = this frame's view, TextureMat = last frame's view, ModelOffset = camera move since last frame (cloud
// space), ColorModulator.x = 1 when the history is valid (0 after a resize or a mode switch)

layout(std140) uniform DynamicTransforms {
    highp mat4 ModelViewMat;
    highp vec4 ColorModulator;
    highp vec3 ModelOffset;
    highp mat4 TextureMat;
};

layout(std140) uniform Projection {
    highp mat4 ProjMat;
};

uniform highp sampler2D Sampler0;
uniform highp sampler2D Sampler1;
uniform highp sampler2D Sampler2;

in highp vec2 uv;

out highp vec4 fragColor;

highp float dist(highp float a) {
    return exp2((1.0 - a) * log2(1.0 + 4096.0)) - 1.0;
}

// same look: both empty, or both cloud with nearly the same colour
bool same(highp vec4 a, highp vec4 b) {
    bool ca = a.a > 0.5 / 255.0, cb = b.a > 0.5 / 255.0;
    if (ca != cb) {
        return false;
    }
    if (!ca) {
        return true;
    }
    highp vec3 d = abs(a.rgb - b.rgb);
    return max(d.r, max(d.g, d.b)) < 0.05;
}

void main() {
    highp ivec2 size = textureSize(Sampler0, 0);
    highp ivec2 px = ivec2(gl_FragCoord.xy);
    highp vec4 cur = texelFetch(Sampler0, px, 0);
    fragColor = cur;
    if (ColorModulator.x < 0.5) {
        return;
    }
    // where this pixel's content was last frame
    highp vec2 puv = (vec2(px) + 0.5) / vec2(size);
    highp vec3 vd = normalize(vec3((puv * 2.0 - 1.0) / vec2(ProjMat[0][0], ProjMat[1][1]), -1.0));
    highp vec3 rd = transpose(mat3(ModelViewMat)) * vd;
    highp vec3 back = cur.a > 0.5 / 255.0 ? rd * dist(cur.a) + ModelOffset : rd; // vector from last camera (sky: direction only)
    highp vec3 pv = mat3(TextureMat) * back;
    if (pv.z > -1e-3) {
        return;
    }
    highp vec2 pn = pv.xy / -pv.z * vec2(ProjMat[0][0], ProjMat[1][1]);
    highp vec2 prevUv = pn * 0.5 + 0.5;
    if (prevUv.x < 0.0 || prevUv.y < 0.0 || prevUv.x >= 1.0 || prevUv.y >= 1.0) {
        return; // came in from off-screen: nothing to compare with
    }
    highp ivec2 pp = ivec2(prevUv * vec2(size));
    highp vec4 raw = texelFetch(Sampler1, pp, 0);
    highp vec4 hist = texelFetch(Sampler2, pp, 0);
    // the change is real (seen two frames in a row), or nothing changed: take this frame
    if (same(cur, raw) || same(cur, hist)) {
        return;
    }
    // a different cloud slid in front or away (big distance jump): no history for it
    if (cur.a > 0.5 / 255.0 && hist.a > 0.5 / 255.0 && abs(dist(cur.a) - dist(hist.a)) > 0.25 * dist(cur.a) + 8.0) {
        return;
    }
    // a one-frame flip: keep what was shown
    fragColor = hist;
}
