#include <metal_stdlib>
#include <simd/simd.h>

using namespace metal;

struct Projection
{
    float4x4 ProjMat;
};

struct DynamicTransforms
{
    float4x4 ModelViewMat;
    float4x4 TextureMat;
    float4 ColorModulator;
    float3 ModelOffset;
};

struct Globals
{
    int3 CameraBlockPos;
    float GlintAlpha;
    float3 CameraOffset;
    float GameTime;
    float2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

struct main0_out
{
    float4 vertexColor [[user(locn0)]];
    float4 gl_Position [[position]];
    float gl_PointSize [[point_size]];
};

struct main0_in
{
    float3 Position [[attribute(0)]];
    float4 Color [[attribute(1)]];
    float LineWidth [[attribute(2)]];
};

vertex main0_out main0(main0_in in [[stage_in]], constant Projection& _23 [[buffer(0)]], constant DynamicTransforms& _30 [[buffer(1)]])
{
    main0_out out = {};
    out.gl_Position = (_23.ProjMat * _30.ModelViewMat) * float4(in.Position, 1.0);
    out.vertexColor = in.Color;
    out.gl_PointSize = in.LineWidth;
    out.gl_Position.y = -(out.gl_Position.y);    // Invert Y-axis for Metal
    return out;
}

