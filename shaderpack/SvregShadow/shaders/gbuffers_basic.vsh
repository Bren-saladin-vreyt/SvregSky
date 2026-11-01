#version 120
varying vec4 glcolor;
varying vec2 lmcoord;
varying float viewDist;
void main() {
    glcolor = gl_Color;
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    viewDist = length((gl_ModelViewMatrix * gl_Vertex).xyz);
    gl_Position = ftransform();
}
