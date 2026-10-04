#pragma clang diagnostic ignored "-Wmissing-prototypes"

#include <metal_stdlib>
#include <simd/simd.h>

using namespace metal;

struct ChunkSection
{
    packed_int3 ChunkPosition;
    float ChunkVisibility;
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

struct Projection
{
    float4x4 ProjMat;
};

struct TerrainUniform
{
    float4x4 ModelViewMat;
    int2 TextureSize;
};

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

struct main0_out
{
    float sphericalVertexDistance [[user(locn0)]];
    float cylindricalVertexDistance [[user(locn1)]];
    float4 vertexColor [[user(locn2)]];
    float2 texCoord0 [[user(locn3)]];
    float chunkVisibility [[user(locn4)]];
    float4 gl_Position [[position]];
};

struct main0_in
{
    float3 Position [[attribute(0)]];
    float4 Color [[attribute(1)]];
    float2 UV0 [[attribute(2)]];
    int2 UV2 [[attribute(3)]];
};

static inline __attribute__((always_inline))
float fog_spherical_distance(thread const float3& pos)
{
    return length(pos);
}

static inline __attribute__((always_inline))
float fog_cylindrical_distance(thread const float3& pos)
{
    float distXZ = length(pos.xz);
    float distY = abs(pos.y);
    return fast::max(distXZ, distY);
}

static inline __attribute__((always_inline))
float4 sample_lightmap(texture2d<float> lightMap, sampler lightMapSmplr, thread const int2& uv)
{
    return lightMap.sample(lightMapSmplr, fast::clamp((float2(uv) / float2(256.0)) + float2(0.03125), float2(0.03125), float2(0.96875)), level(0.0));
}

vertex main0_out main0(main0_in in [[stage_in]], constant ChunkSection& _80 [[buffer(0)]], constant Globals& _87 [[buffer(1)]], constant Projection& _105 [[buffer(2)]], constant TerrainUniform& _111 [[buffer(3)]], texture2d<float> Sampler2 [[texture(0)]], sampler Sampler2Smplr [[sampler(0)]])
{
    main0_out out = {};
    float3 pos = (in.Position + float3(int3(_80.ChunkPosition) - int3(_87.CameraBlockPos))) + float3(_87.CameraOffset);
    out.gl_Position = (_105.ProjMat * _111.ModelViewMat) * float4(pos, 1.0);
    float3 param = pos;
    out.sphericalVertexDistance = fog_spherical_distance(param);
    float3 param_1 = pos;
    out.cylindricalVertexDistance = fog_cylindrical_distance(param_1);
    int2 param_2 = in.UV2;
    out.vertexColor = in.Color * sample_lightmap(Sampler2, Sampler2Smplr, param_2);
    out.texCoord0 = in.UV0;
    float dist = length(pos);
    out.chunkVisibility = mix(1.0, _80.ChunkVisibility, fast::clamp((dist - 16.0) / 16.0, 0.0, 1.0));
    out.gl_Position.y = -(out.gl_Position.y);    // Invert Y-axis for Metal
    return out;
}

