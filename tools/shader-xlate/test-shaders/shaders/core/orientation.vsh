#version 330
#extension GL_ARB_separate_shader_objects : require

// Synthetic shader (not vanilla) for the runtime orientation test (metalcheck/metalrender.m).
// One GL-convention counter-clockwise triangle covering the lower-left half of clip space.
layout(location = 0) out vec2 ndc;

void main() {
    vec2 p[3] = vec2[](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0));
    ndc = p[gl_VertexIndex];
    gl_Position = vec4(p[gl_VertexIndex], 0.25, 1.0);
}
