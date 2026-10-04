#version 330
#extension GL_ARB_separate_shader_objects : require

layout(push_constant) uniform PushConstants {
    mat4 Transform;
    vec4 Tint;
    int Mode;
} pc;

uniform sampler2D Sampler0;
uniform usamplerBuffer Ids;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) flat in int instance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 c = texture(Sampler0, texCoord0) * vertexColor;
    if (pc.Mode == 1) {
        c.rgb *= float(texelFetch(Ids, instance).r & 255u) / 255.0;
    }
    if (c.a < 0.1) {
        discard;
    }
    gl_FragDepth = gl_FragCoord.z;
    fragColor = c;
}
