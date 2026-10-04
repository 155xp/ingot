#include <metal_stdlib>
#include <simd/simd.h>

using namespace metal;

struct main0_out
{
    float gl_FragDepth [[depth(any)]];
};

struct main0_in
{
    float2 texCoord [[user(locn0)]];
};

fragment main0_out main0(main0_in in [[stage_in]], texture2d<float> InSampler [[texture(0)]], sampler InSamplerSmplr [[sampler(0)]])
{
    main0_out out = {};
    out.gl_FragDepth = InSampler.sample(InSamplerSmplr, in.texCoord).x;
    return out;
}

