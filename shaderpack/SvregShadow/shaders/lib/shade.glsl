// shared lighting: vanilla lightmap + a soft anime shadow from the sun (or moon)
uniform sampler2D lightmap;
uniform sampler2DShadow shadowtex0;
uniform float rainStrength;
uniform float sunAngle;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;

float svShadowLookup(vec3 sp) {
    if (sp.x <= 0.0 || sp.x >= 1.0 || sp.y <= 0.0 || sp.y >= 1.0 || sp.z >= 1.0) return 1.0;
    float bias = 0.00025;
#if SOFT_SHADOWS == 1
    float t = 1.0 / float(shadowMapResolution);
    float s = 0.0;
    s += shadow2D(shadowtex0, vec3(sp.xy + vec2(-t, -t) * 0.7, sp.z - bias)).r;
    s += shadow2D(shadowtex0, vec3(sp.xy + vec2( t, -t) * 0.7, sp.z - bias)).r;
    s += shadow2D(shadowtex0, vec3(sp.xy + vec2(-t,  t) * 0.7, sp.z - bias)).r;
    s += shadow2D(shadowtex0, vec3(sp.xy + vec2( t,  t) * 0.7, sp.z - bias)).r;
    return s * 0.25;
#else
    return shadow2D(shadowtex0, vec3(sp.xy, sp.z - bias)).r;
#endif
}

// how strong shadows are right now: full by day, fading at sunrise/sunset, weak under the moon, none in rain
float svShadowStrength() {
    float day = smoothstep(0.0, 0.025, sunAngle) * (1.0 - smoothstep(0.475, 0.5, sunAngle));
    float night = smoothstep(0.5, 0.53, sunAngle) * (1.0 - smoothstep(0.97, 1.0, sunAngle));
#if MOON_SHADOWS == 1
    float k = day + night * 0.35;
#else
    float k = day;
#endif
    return k * (1.0 - rainStrength * 0.9);
}

vec3 svShadowTint() {
#if SHADOW_TINT == 0
    return vec3(0.0);                 // neutral: plain darker
#elif SHADOW_TINT == 1
    return vec3(0.28, 0.36, 0.78);    // anime: cool blue-violet shade
#else
    return vec3(0.50, 0.33, 0.22);    // warm: evening brown
#endif
}

// lit = 1 in the light, 0 in shadow; sky = how open to the sky (lightmap y)
vec3 svApplyShadow(vec3 col, float lit, float sky) {
    float k = (1.0 - lit) * smoothstep(0.55, 0.9, sky) * svShadowStrength() * SHADOW_DARKNESS;
    return col * mix(vec3(1.0), svShadowTint(), k);
}

vec3 svFog(vec3 col, float dist) {
    return mix(col, fogColor, clamp((dist - fogStart) / max(fogEnd - fogStart, 1.0), 0.0, 1.0));
}
