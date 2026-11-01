#include "/lib/settings.glsl"
#include "/lib/distort.glsl"

uniform mat4 gbufferModelViewInverse;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
uniform vec3 shadowLightPosition;
uniform mat4 gbufferModelView;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 shadowPos;
varying float viewDist;
varying float facing;
varying float diffuse;

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    glcolor = gl_Color;
    vec4 vp = gl_ModelViewMatrix * gl_Vertex;
    viewDist = length(vp.xyz);
    vec3 n = normalize(gl_NormalMatrix * gl_Normal);
    vec3 L = normalize(shadowLightPosition);
    facing = dot(n, L);
    // vanilla-style two-light shading for mobs, players and held items (blocks keep their own baked shading)
    vec3 l0 = normalize(mat3(gbufferModelView) * vec3(0.2, 1.0, -0.7));
    vec3 l1 = normalize(mat3(gbufferModelView) * vec3(-0.2, 1.0, 0.7));
    diffuse = min(1.0, (max(dot(n, l0), 0.0) + max(dot(n, l1), 0.0)) * 0.6 + 0.4);
#ifdef NO_SHADOW
    shadowPos = vec3(2.0);
#else
    // a little push along the normal (more far away) keeps flat ground free of shadow acne
    vec4 pp = gbufferModelViewInverse * vp;
    vec3 nw = mat3(gbufferModelViewInverse) * n;
    pp.xyz += nw * (0.04 + viewDist * 0.0025);
    vec4 sp = shadowProjection * (shadowModelView * pp);
    sp.xyz = svDistort(sp.xyz);
    shadowPos = sp.xyz * 0.5 + 0.5;
#endif
    gl_Position = ftransform();
}
