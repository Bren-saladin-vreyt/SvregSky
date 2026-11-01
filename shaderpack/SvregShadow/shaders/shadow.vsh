#version 120
#include "/lib/settings.glsl"
#include "/lib/distort.glsl"

varying vec2 texcoord;
varying vec4 glcolor;

void main() {
    gl_Position = ftransform();
    gl_Position.xyz = svDistort(gl_Position.xyz);
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    glcolor = gl_Color;
}
