#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0;

layout(location = 0) in vec2 ndc;
layout(location = 0) out vec4 fragColor;

void main() {
    // r = pixel column, g = pixel row as GL would report them, b = window-space depth (+0.5 if gl_FrontFacing),
    // a = what the first uploaded texel row (v = 0) contains (the harness uploads row 0 with G = 255).
    fragColor = vec4((gl_FragCoord.x - 0.5) / 255.0, (gl_FragCoord.y - 0.5) / 255.0,
                     gl_FragCoord.z + (gl_FrontFacing ? 0.5 : 0.0), texture(Sampler0, vec2(0.25, 0.25)).g);
}
