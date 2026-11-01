#include "/lib/settings.glsl"
#include "/lib/shade.glsl"

uniform sampler2D gtexture;
#ifdef ENTITY
uniform vec4 entityColor;
#endif

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 shadowPos;
varying float viewDist;
varying float facing;
varying float diffuse;

void main() {
    vec4 col = texture2D(gtexture, texcoord) * glcolor;
#ifndef TRANSLUCENT
    if (col.a < 0.1) discard;
#endif
#ifdef ENTITY
    col.rgb = mix(col.rgb, entityColor.rgb, entityColor.a); // the red hurt flash
#endif
#if defined(ENTITY) || defined(NO_SHADOW)
    col.rgb *= diffuse;
#endif
    col.rgb *= texture2D(lightmap, lmcoord).rgb;
#ifndef NO_SHADOW
    float lit = facing <= 0.02 ? 0.0 : svShadowLookup(shadowPos);
    // fade the shadow out at the edge of the shadow distance instead of a hard cut
    lit = mix(lit, 1.0, smoothstep(shadowDistance * 0.8, shadowDistance, viewDist));
    col.rgb = svApplyShadow(col.rgb, lit, lmcoord.y);
#endif
    col.rgb = svFog(col.rgb, viewDist);
    gl_FragData[0] = col;
}
