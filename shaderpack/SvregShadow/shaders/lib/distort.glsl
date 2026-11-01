// squeeze the shadow map toward the player: sharp shadows nearby, coarser far away (cheap and good for small maps)
vec3 svDistort(vec3 p) {
    float f = length(p.xy) * 0.88 + 0.12;
    return vec3(p.xy / f, p.z * 0.25);
}
