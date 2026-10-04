#include <metal_stdlib>
#include <simd/simd.h>

using namespace metal;

struct PushConstants
{
    float4x4 Transform;
    float4 Tint;
    int Mode;
};

struct main0_out
{
    float4 fragColor [[color(0)]];
    float gl_FragDepth [[depth(any)]];
};

struct main0_in
{
    float2 texCoord0 [[user(locn0)]];
    float4 vertexColor [[user(locn1)]];
    int instance [[user(locn2)]];
};

fragment main0_out main0(main0_in in [[stage_in]], constant PushConstants& pc [[buffer(14)]], texture2d<float> Sampler0 [[texture(1)]], texture_buffer<uint> Ids [[texture(2)]], sampler Sampler0Smplr [[sampler(0)]], float4 gl_FragCoord [[position]])
{
    main0_out out = {};
    float4 c = Sampler0.sample(Sampler0Smplr, in.texCoord0) * in.vertexColor;
    if (pc.Mode == 1)
    {
        float4 _59 = c;
        float3 _61 = _59.xyz * (float(Ids.read(uint(in.instance)).x & 255u) / 255.0);
        c.x = _61.x;
        c.y = _61.y;
        c.z = _61.z;
    }
    if (c.w < 0.100000001490116119384765625)
    {
        discard_fragment();
    }
    out.gl_FragDepth = gl_FragCoord.z;
    out.fragColor = c;
    return out;
}

