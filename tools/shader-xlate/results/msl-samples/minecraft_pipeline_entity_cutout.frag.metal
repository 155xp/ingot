#pragma clang diagnostic ignored "-Wmissing-prototypes"

#include <metal_stdlib>
#include <simd/simd.h>

using namespace metal;

struct Fog
{
    float4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

struct DynamicTransforms
{
    float4x4 ModelViewMat;
    float4x4 TextureMat;
    float4 ColorModulator;
    float3 ModelOffset;
};

struct main0_out
{
    float4 fragColor [[color(0)]];
};

struct main0_in
{
    float sphericalVertexDistance [[user(locn0)]];
    float cylindricalVertexDistance [[user(locn1)]];
    float4 vertexPerFaceColorBack [[user(locn2)]];
    float4 vertexPerFaceColorFront [[user(locn3)]];
    float4 lightMapColor [[user(locn4)]];
    float4 overlayColor [[user(locn5)]];
    float2 texCoord0 [[user(locn6)]];
};

static inline __attribute__((always_inline))
float linear_fog_value(thread const float& vertexDistance, thread const float& fogStart, thread const float& fogEnd)
{
    if (vertexDistance <= fogStart)
    {
        return 0.0;
    }
    else
    {
        if (vertexDistance >= fogEnd)
        {
            return 1.0;
        }
    }
    return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

static inline __attribute__((always_inline))
float total_fog_value(thread const float& sphericalVertexDistance, thread const float& cylindricalVertexDistance, thread const float& environmentalStart, thread const float& environmantalEnd, thread const float& renderDistanceStart, thread const float& renderDistanceEnd)
{
    float param = sphericalVertexDistance;
    float param_1 = environmentalStart;
    float param_2 = environmantalEnd;
    float param_3 = cylindricalVertexDistance;
    float param_4 = renderDistanceStart;
    float param_5 = renderDistanceEnd;
    return fast::max(linear_fog_value(param, param_1, param_2), linear_fog_value(param_3, param_4, param_5));
}

static inline __attribute__((always_inline))
float4 apply_fog(thread const float4& inColor, thread const float& sphericalVertexDistance, thread const float& cylindricalVertexDistance, thread const float& environmentalStart, thread const float& environmantalEnd, thread const float& renderDistanceStart, thread const float& renderDistanceEnd, thread const float4& fogColor)
{
    float param = sphericalVertexDistance;
    float param_1 = cylindricalVertexDistance;
    float param_2 = environmentalStart;
    float param_3 = environmantalEnd;
    float param_4 = renderDistanceStart;
    float param_5 = renderDistanceEnd;
    float fogValue = total_fog_value(param, param_1, param_2, param_3, param_4, param_5);
    return float4(mix(inColor.xyz, fogColor.xyz, float3(fogValue * fogColor.w)), inColor.w);
}

static inline __attribute__((always_inline))
float4 calculateFinalColor(thread float4& color, thread float4& overlayColor, thread float4& lightMapColor, constant Fog& _148, thread float& sphericalVertexDistance, thread float& cylindricalVertexDistance)
{
    float4 _125 = color;
    float3 _131 = mix(overlayColor.xyz, _125.xyz, float3(overlayColor.w));
    color.x = _131.x;
    color.y = _131.y;
    color.z = _131.z;
    color *= lightMapColor;
    float4 fogColor = _148.FogColor;
    float4 param = color;
    float param_1 = sphericalVertexDistance;
    float param_2 = cylindricalVertexDistance;
    float param_3 = _148.FogEnvironmentalStart;
    float param_4 = _148.FogEnvironmentalEnd;
    float param_5 = _148.FogRenderDistanceStart;
    float param_6 = _148.FogRenderDistanceEnd;
    float4 param_7 = fogColor;
    return apply_fog(param, param_1, param_2, param_3, param_4, param_5, param_6, param_7);
}

fragment main0_out main0(main0_in in [[stage_in]], constant DynamicTransforms& _215 [[buffer(1)]], constant Fog& _148 [[buffer(3)]], texture2d<float> Sampler0 [[texture(2)]], sampler Sampler0Smplr [[sampler(2)]], bool gl_FrontFacing [[front_facing]])
{
    main0_out out = {};
    float4 color = Sampler0.sample(Sampler0Smplr, in.texCoord0);
    if (color.w < 0.100000001490116119384765625)
    {
        discard_fragment();
    }
    float4 faceVertexColor = gl_FrontFacing ? in.vertexPerFaceColorFront : in.vertexPerFaceColorBack;
    color *= (faceVertexColor * _215.ColorModulator);
    float4 param = color;
    float4 _225 = calculateFinalColor(param, in.overlayColor, in.lightMapColor, _148, in.sphericalVertexDistance, in.cylindricalVertexDistance);
    out.fragColor = _225;
    return out;
}

