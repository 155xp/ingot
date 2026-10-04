#version 330
#extension GL_ARB_separate_shader_objects : require

// Synthetic shader (not vanilla): exercises push constants, a float texel buffer in the
// vertex stage, gl_VertexIndex / gl_InstanceIndex and a flat int varying.
#include <minecraft:globals.glsl>

layout(push_constant) uniform PushConstants {
    mat4 Transform;
    vec4 Tint;
    int Mode;
} pc;

uniform samplerBuffer Lut;

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) flat out int instance;

void main() {
    gl_Position = pc.Transform * vec4(Position + CameraOffset, 1.0);
    vertexColor = Color * pc.Tint * texelFetch(Lut, gl_VertexIndex % 16);
    texCoord0 = UV0;
    instance = gl_InstanceIndex;
}
