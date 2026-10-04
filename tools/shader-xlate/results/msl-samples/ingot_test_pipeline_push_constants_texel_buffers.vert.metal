#pragma clang diagnostic ignored "-Wmissing-prototypes"

#include <metal_stdlib>
#include <simd/simd.h>

using namespace metal;

// Implementation of signed integer mod accurate to SPIR-V specification
template<typename Tx, typename Ty>
inline Tx spvSMod(Tx x, Ty y)
{
    Tx remainder = x - y * (x / y);
    return select(Tx(remainder + y), remainder, remainder == 0 || (x >= 0) == (y >= 0));
}

struct PushConstants
{
    float4x4 Transform;
    float4 Tint;
    int Mode;
};

struct Globals
{
    packed_int3 CameraBlockPos;
    float GlintAlpha;
    packed_float3 CameraOffset;
    float GameTime;
    float2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

struct main0_out
{
    float2 texCoord0 [[user(locn0)]];
    float4 vertexColor [[user(locn1)]];
    int instance [[user(locn2)]];
    float4 gl_Position [[position]];
};

struct main0_in
{
    float3 Position [[attribute(0)]];
    float2 UV0 [[attribute(1)]];
    float4 Color [[attribute(2)]];
};

vertex main0_out main0(main0_in in [[stage_in]], constant Globals& _33 [[buffer(0)]], constant PushConstants& pc [[buffer(14)]], texture_buffer<float> Lut [[texture(0)]], uint gl_VertexIndex [[vertex_id]], uint gl_InstanceIndex [[instance_id]])
{
    main0_out out = {};
    out.gl_Position = pc.Transform * float4(in.Position + float3(_33.CameraOffset), 1.0);
    out.vertexColor = (in.Color * pc.Tint) * Lut.read(uint(spvSMod(int(gl_VertexIndex), 16)));
    out.texCoord0 = in.UV0;
    out.instance = int(gl_InstanceIndex);
    out.gl_Position.y = -(out.gl_Position.y);    // Invert Y-axis for Metal
    return out;
}

