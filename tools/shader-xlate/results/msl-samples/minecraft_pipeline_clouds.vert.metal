#pragma clang diagnostic ignored "-Wmissing-prototypes"
#pragma clang diagnostic ignored "-Wmissing-braces"

#include <metal_stdlib>
#include <simd/simd.h>

using namespace metal;

template<typename T, size_t Num>
struct spvUnsafeArray
{
    T elements[Num ? Num : 1];
    
    thread T& operator [] (size_t pos) thread
    {
        return elements[pos];
    }
    constexpr const thread T& operator [] (size_t pos) const thread
    {
        return elements[pos];
    }
    
    device T& operator [] (size_t pos) device
    {
        return elements[pos];
    }
    constexpr const device T& operator [] (size_t pos) const device
    {
        return elements[pos];
    }
    
    constexpr const constant T& operator [] (size_t pos) const constant
    {
        return elements[pos];
    }
    
    threadgroup T& operator [] (size_t pos) threadgroup
    {
        return elements[pos];
    }
    constexpr const threadgroup T& operator [] (size_t pos) const threadgroup
    {
        return elements[pos];
    }
};

// Implementation of signed integer mod accurate to SPIR-V specification
template<typename Tx, typename Ty>
inline Tx spvSMod(Tx x, Ty y)
{
    Tx remainder = x - y * (x / y);
    return select(Tx(remainder + y), remainder, remainder == 0 || (x >= 0) == (y >= 0));
}

struct CloudInfo
{
    float4 CloudColor;
    float3 CloudOffset;
    float3 CellSize;
};

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

constant spvUnsafeArray<float4, 6> _210 = spvUnsafeArray<float4, 6>({ float4(0.699999988079071044921875, 0.699999988079071044921875, 0.699999988079071044921875, 1.0), float4(1.0), float4(0.800000011920928955078125, 0.800000011920928955078125, 0.800000011920928955078125, 1.0), float4(0.800000011920928955078125, 0.800000011920928955078125, 0.800000011920928955078125, 1.0), float4(0.89999997615814208984375, 0.89999997615814208984375, 0.89999997615814208984375, 1.0), float4(0.89999997615814208984375, 0.89999997615814208984375, 0.89999997615814208984375, 1.0) });

struct main0_out
{
    float vertexDistance [[user(locn0)]];
    float4 vertexColor [[user(locn1)]];
    float4 gl_Position [[position]];
};

static inline __attribute__((always_inline))
float3 vertex0(thread const int& index)
{
    float3 pos = float3(0.0);
    pos.x = float((15744195 >> index) & 1);
    pos.y = float((6711024 >> index) & 1);
    pos.z = float((12841062 >> index) & 1);
    return pos;
}

static inline __attribute__((always_inline))
float fog_spherical_distance(thread const float3& pos)
{
    return length(pos);
}

vertex main0_out main0(constant CloudInfo& _145 [[buffer(0)]], constant Projection& _170 [[buffer(1)]], constant DynamicTransforms& _176 [[buffer(2)]], texture_buffer<int> CloudFaces [[texture(0)]], uint gl_VertexIndex [[vertex_id]])
{
    main0_out out = {};
    int quadVertex = spvSMod(int(gl_VertexIndex), 4);
    int index = (int(gl_VertexIndex) / 4) * 3;
    int cellX = CloudFaces.read(uint(index)).x;
    int cellZ = CloudFaces.read(uint((index + 1))).x;
    int dirAndFlags = CloudFaces.read(uint((index + 2))).x;
    int direction = dirAndFlags & 7;
    bool isInsideFace = (dirAndFlags & 16) == 16;
    bool useTopColor = (dirAndFlags & 32) == 32;
    cellX = (cellX << 1) | ((dirAndFlags & 128) >> 7);
    cellZ = (cellZ << 1) | ((dirAndFlags & 64) >> 6);
    int _129 = {};
    if (isInsideFace)
    {
        _129 = 3 - quadVertex;
    }
    else
    {
        _129 = quadVertex;
    }
    int param = (direction * 4) + _129;
    float3 faceVertex = vertex0(param);
    float3 pos = ((faceVertex * _145.CellSize) + (float3(float(cellX), 0.0, float(cellZ)) * _145.CellSize)) + _145.CloudOffset;
    out.gl_Position = (_170.ProjMat * _176.ModelViewMat) * float4(pos, 1.0);
    float3 param_1 = pos;
    out.vertexDistance = fog_spherical_distance(param_1);
    float4 _197 = {};
    if (useTopColor)
    {
        _197 = float4(1.0);
    }
    else
    {
        _197 = _210[direction];
    }
    out.vertexColor = _197 * _145.CloudColor;
    out.gl_Position.y = -(out.gl_Position.y);    // Invert Y-axis for Metal
    return out;
}

