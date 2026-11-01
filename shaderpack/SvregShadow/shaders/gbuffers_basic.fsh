#version 120
varying vec4 glcolor;
varying vec2 lmcoord;
varying float viewDist;
void main() {
    gl_FragData[0] = glcolor;
}
