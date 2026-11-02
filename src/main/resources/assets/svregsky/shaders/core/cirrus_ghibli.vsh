#version 330

precision highp float;

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec3 relPos;
flat out vec4 info;
flat out vec2 faceShadow;
out float localY;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    relPos = Position + ModelOffset; // camera-relative position
    info = Color;
    faceShadow = UV0;
    localY = Position.y;
}
