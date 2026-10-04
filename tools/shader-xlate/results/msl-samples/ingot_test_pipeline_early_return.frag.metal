#include <metal_stdlib>
#include <simd/simd.h>

using namespace metal;

struct main0_out
{
    float4 fragColor [[color(0)]];
};

fragment main0_out main0(texture2d<float> Sampler0 [[texture(0)]], sampler Sampler0Smplr [[sampler(0)]], float4 gl_FragCoord [[position]], bool gl_FrontFacing [[front_facing]])
{
    main0_out out = {};
    out.fragColor = float4((gl_FragCoord.x - 0.5) / 255.0, (gl_FragCoord.y - 0.5) / 255.0, gl_FragCoord.z + (gl_FrontFacing ? 0.5 : 0.0), Sampler0.sample(Sampler0Smplr, float2(0.25)).y);
    return out;
}

