#version 330

// SvregSky cel-shading: the usual two-light entity shading, snapped into three flat anime tones
#define MINECRAFT_LIGHT_POWER   (0.6)
#define MINECRAFT_AMBIENT_LIGHT (0.4)

layout(std140) uniform Lighting {
    vec3 Light0_Direction;
    vec3 Light1_Direction;
};

vec2 minecraft_compute_light(vec3 lightDir0, vec3 lightDir1, vec3 normal) {
    return vec2(dot(lightDir0, normal), dot(lightDir1, normal));
}

vec4 minecraft_mix_light_separate(vec2 light, vec4 color) {
    vec2 lightValue = max(vec2(0.0), light);
    float lightAccum = min(1.0, (lightValue.x + lightValue.y) * MINECRAFT_LIGHT_POWER + MINECRAFT_AMBIENT_LIGHT);
    // three tones: lit, soft shade, deep shade (slightly cool, like painted shadow)
    float toon = lightAccum > 0.80 ? 1.0 : (lightAccum > 0.58 ? 0.82 : 0.64);
    vec3 tint = toon > 0.9 ? vec3(1.0) : (toon > 0.7 ? vec3(0.97, 0.98, 1.03) : vec3(0.93, 0.95, 1.06));
    return vec4(color.rgb * toon * tint, color.a);
}

vec4 minecraft_mix_light(vec3 lightDir0, vec3 lightDir1, vec3 normal, vec4 color) {
    vec2 light = minecraft_compute_light(lightDir0, lightDir1, normal);
    return minecraft_mix_light_separate(light, color);
}
