#version 330
#extension GL_ARB_separate_shader_objects : require

// Synthetic shader (not vanilla): checks that SPIRV-Cross applies the Y flip on every return path.
layout(location = 0) out vec2 ndc;

void main() {
    vec2 p[3] = vec2[](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0));
    ndc = p[gl_VertexIndex % 3];
    if (gl_VertexIndex >= 3) {
        gl_Position = vec4(p[gl_VertexIndex % 3] * 0.5, 0.25, 1.0);
        return;
    }
    gl_Position = vec4(p[gl_VertexIndex], 0.25, 1.0);
}
