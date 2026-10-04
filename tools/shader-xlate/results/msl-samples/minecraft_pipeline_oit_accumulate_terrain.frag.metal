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

struct Projection
{
    float4x4 ProjMat;
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

struct TerrainUniform
{
    float4x4 ModelViewMat;
    int2 TextureSize;
};

struct ChunkSection
{
    int3 ChunkPosition;
    float ChunkVisibility;
};

constant spvUnsafeArray<float2, 4> _355 = spvUnsafeArray<float2, 4>({ float2(0.125, 0.375), float2(-0.125, -0.375), float2(0.375, -0.125), float2(-0.375, 0.125) });

struct main0_out
{
    float4 fragColor [[color(0)]];
    float gl_FragDepth [[depth(any)]];
};

struct main0_in
{
    float sphericalVertexDistance [[user(locn0)]];
    float cylindricalVertexDistance [[user(locn1)]];
    float4 vertexColor [[user(locn2)]];
    float2 texCoord0 [[user(locn3)]];
    float chunkVisibility [[user(locn4)]];
};

static inline __attribute__((always_inline))
float4 sampleNearest(texture2d<float> source, sampler sourceSmplr, thread float2& uv, thread const float2& pixelSize, thread const float2& du, thread const float2& dv, thread const float2& texelScreenSize)
{
    float2 uvTexelCoords = uv / pixelSize;
    float2 texelCenter = round(uvTexelCoords) - float2(0.5);
    float2 texelOffset = uvTexelCoords - texelCenter;
    texelOffset = (((texelOffset - float2(0.5)) * pixelSize) / texelScreenSize) + float2(0.5);
    texelOffset = fast::clamp(texelOffset, float2(0.0), float2(1.0));
    uv = (texelCenter + texelOffset) * pixelSize;
    return source.sample(sourceSmplr, uv, gradient2d(du, dv));
}

static inline __attribute__((always_inline))
float4 sampleRGSS(texture2d<float> source, sampler sourceSmplr, thread const float2& uv, thread const float2& pixelSize)
{
    float2 du = dfdx(uv);
    float2 dv = dfdy(uv);
    float2 texelScreenSize = sqrt((du * du) + (dv * dv));
    float maxTexelSize = fast::max(texelScreenSize.x, texelScreenSize.y);
    float minPixelSize = fast::min(pixelSize.x, pixelSize.y);
    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);
    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = fast::min(duLength, dvLength);
    float maxDerivative = fast::max(duLength, dvLength);
    float effectiveDerivative = sqrt(minDerivative * maxDerivative);
    float mipLevelExact = fast::max(0.0, log2(effectiveDerivative / minPixelSize));
    float mipLevelLow = floor(mipLevelExact);
    float mipLevelHigh = mipLevelLow + 1.0;
    float mipBlend = fract(mipLevelExact);
    float4 rgssColorLow = float4(0.0);
    float4 rgssColorHigh = float4(0.0);
    for (int i = 0; i < 4; i++)
    {
        float2 sampleUV = uv + (_355[i] * pixelSize);
        rgssColorLow += source.sample(sourceSmplr, sampleUV, level(mipLevelLow));
        rgssColorHigh += source.sample(sourceSmplr, sampleUV, level(mipLevelHigh));
    }
    rgssColorLow *= 0.25;
    rgssColorHigh *= 0.25;
    float4 rgssColor = mix(rgssColorLow, rgssColorHigh, float4(mipBlend));
    float2 param = uv;
    float2 param_1 = pixelSize;
    float2 param_2 = du;
    float2 param_3 = dv;
    float2 param_4 = texelScreenSize;
    float4 _401 = sampleNearest(source, sourceSmplr, param, param_1, param_2, param_3, param_4);
    float4 nearestColor = _401;
    return mix(nearestColor, rgssColor, float4(blendFactor));
}

static inline __attribute__((always_inline))
float4 sampleNearest(texture2d<float> source, sampler sourceSmplr, thread const float2& uv, thread const float2& pixelSize)
{
    float2 du = dfdx(uv);
    float2 dv = dfdy(uv);
    float2 texelScreenSize = sqrt((du * du) + (dv * dv));
    float2 param = uv;
    float2 param_1 = pixelSize;
    float2 param_2 = du;
    float2 param_3 = dv;
    float2 param_4 = texelScreenSize;
    float4 _251 = sampleNearest(source, sourceSmplr, param, param_1, param_2, param_3, param_4);
    return _251;
}

static inline __attribute__((always_inline))
float deviceToLinearDepth(thread const float& deviceDepth, constant Projection& _412)
{
    return _412.ProjMat[3].z / (deviceDepth + _412.ProjMat[2].z);
}

static inline __attribute__((always_inline))
float normalizeDepth(thread const float& fragmentDeviceDepth, constant Projection& _412, texture2d<float> DepthBoundsSampler, sampler DepthBoundsSamplerSmplr, thread float4& gl_FragCoord)
{
    float4 depthBoundsSample = DepthBoundsSampler.read(uint2(int2(gl_FragCoord.xy)), 0);
    float closestBoundLinearDepthNegated = depthBoundsSample.x;
    float closestBoundLinearDepth = -closestBoundLinearDepthNegated;
    float furthestBoundLinearDepth = depthBoundsSample.y;
    float param = fragmentDeviceDepth;
    float fragmentLinearDepth = deviceToLinearDepth(param, _412);
    float range = fast::max(furthestBoundLinearDepth - closestBoundLinearDepth, 9.9999997473787516355514526367188e-05);
    float depthWithinBounds = fast::clamp(fragmentLinearDepth - closestBoundLinearDepth, 0.0, range);
    float depthWithHighPrecision = 0.75 * range;
    if (depthWithHighPrecision <= 10.0)
    {
        return depthWithinBounds / range;
    }
    else
    {
        if (depthWithinBounds <= 10.0)
        {
            return (depthWithinBounds / 10.0) * 0.75;
        }
        else
        {
            return 0.75 + (((depthWithinBounds - 10.0) / (range - 10.0)) * 0.25);
        }
    }
}

static inline __attribute__((always_inline))
float toAbsorbance(thread const float& transmittance)
{
    return fast::clamp(-log(fast::max(transmittance, 9.9999997473787516355514526367188e-05)), 0.0, 4.0);
}

static inline __attribute__((always_inline))
float sampleAbsorbance(thread const spvUnsafeArray<float, 8>& coefficients, thread const float& originalDepth, thread const float& currentAbsorbance)
{
    float averageAbsorbance = coefficients[0];
    if (averageAbsorbance == 0.0)
    {
        return 0.0;
    }
    float depthMeasuredInBins = originalDepth * 7.0;
    float depth = depthMeasuredInBins / 8.0;
    float currentAverageAbsorbanceContribution = currentAbsorbance * (1.0 - depth);
    averageAbsorbance -= currentAverageAbsorbanceContribution;
    int treeIndexB = clamp(int(floor(depthMeasuredInBins)), 0, 7);
    bool shouldSampleA = treeIndexB >= 1;
    int _543 = {};
    if (shouldSampleA)
    {
        _543 = treeIndexB - 1;
    }
    else
    {
        _543 = treeIndexB;
    }
    int treeIndexA = _543;
    treeIndexB += 8;
    treeIndexA += 8;
    float sampleB = averageAbsorbance;
    float sampleA = shouldSampleA ? averageAbsorbance : 0.0;
    float _646 = {};
    for (int waveletLevel = 0; waveletLevel <= 2; waveletLevel++)
    {
        int power = 2 - waveletLevel;
        float waveletWidth = exp2(float(-power));
        float waveletHeightScale = exp2(float(power) * 0.5);
        int parentIndexB = treeIndexB >> 1;
        int isRightHalfB = treeIndexB & 1;
        int waveletSignB = 1 - (isRightHalfB << 1);
        float indexAtParentLevel = float(parentIndexB & ((1 << power) - 1));
        float depthOffsetRelativeToWavelet = depth - (waveletWidth * indexAtParentLevel);
        float currentDifferenceCoefficient = (((float(isRightHalfB) * waveletWidth) + (float(waveletSignB) * depthOffsetRelativeToWavelet)) * waveletHeightScale) * currentAbsorbance;
        float differenceCoefficientB = coefficients[parentIndexB];
        differenceCoefficientB -= currentDifferenceCoefficient;
        sampleB -= ((waveletHeightScale * differenceCoefficientB) * float(waveletSignB));
        treeIndexB = parentIndexB;
        if (shouldSampleA)
        {
            int parentIndexA = treeIndexA >> 1;
            if (parentIndexA == parentIndexB)
            {
                _646 = differenceCoefficientB;
            }
            else
            {
                _646 = coefficients[parentIndexA];
            }
            float differenceCoefficientA = _646;
            int isRightHalfA = treeIndexA & 1;
            int waveletSignA = 1 - (isRightHalfA << 1);
            sampleA -= ((waveletHeightScale * differenceCoefficientA) * float(waveletSignA));
            treeIndexA = parentIndexA;
        }
    }
    float _676 = {};
    if (depthMeasuredInBins >= 8.0)
    {
        _676 = 1.0;
    }
    else
    {
        _676 = fract(depthMeasuredInBins);
    }
    float lerpAlpha = _676;
    return mix(sampleA, sampleB, lerpAlpha);
}

static inline __attribute__((always_inline))
float toTransmittance(thread const float& absorbance)
{
    return fast::clamp(exp(-absorbance), 9.9999997473787516355514526367188e-05, 1.0);
}

static inline __attribute__((always_inline))
float sampleTransmittance(thread const int2& pos, thread const float& depth, thread const float& currentTransmittance, texture2d<float> Coeff0, sampler Coeff0Smplr, texture2d<float> Coeff1, sampler Coeff1Smplr)
{
    spvUnsafeArray<float4, 2> coeffSamples = {};
    coeffSamples[0] = Coeff0.read(uint2(pos), 0);
    coeffSamples[1] = Coeff1.read(uint2(pos), 0);
    spvUnsafeArray<float, 8> coefficients = {};
    for (int i = 0; i < 2; i++)
    {
        for (int j = 0; j < 4; j++)
        {
            coefficients[(i * 4) + j] = coeffSamples[i][j];
        }
    }
    float param = currentTransmittance;
    spvUnsafeArray<float, 8> param_1 = coefficients;
    float param_2 = depth;
    float param_3 = toAbsorbance(param);
    float param_4 = sampleAbsorbance(param_1, param_2, param_3);
    return toTransmittance(param_4);
}

static inline __attribute__((always_inline))
float4 sampleColorForAccumulation(thread const float4& color, constant Projection& _412, texture2d<float> DepthBoundsSampler, sampler DepthBoundsSamplerSmplr, thread float4& gl_FragCoord, texture2d<float> Coeff0, sampler Coeff0Smplr, texture2d<float> Coeff1, sampler Coeff1Smplr, thread float& gl_FragDepth)
{
    float transmittance = 1.0 - color.w;
    float accumAlpha = color.w;
    gl_FragDepth = gl_FragCoord.z;
    float param = gl_FragCoord.z;
    int2 param_1 = int2(gl_FragCoord.xy);
    float param_2 = normalizeDepth(param, _412, DepthBoundsSampler, DepthBoundsSamplerSmplr, gl_FragCoord);
    float param_3 = transmittance;
    float sampledTransmittance = sampleTransmittance(param_1, param_2, param_3, Coeff0, Coeff0Smplr, Coeff1, Coeff1Smplr);
    return float4(color.xyz * color.w, accumAlpha) * sampledTransmittance;
}

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
float4 calculateFinalColor(thread float4& color, constant Projection& _412, texture2d<float> DepthBoundsSampler, sampler DepthBoundsSamplerSmplr, thread float4& gl_FragCoord, texture2d<float> Coeff0, sampler Coeff0Smplr, texture2d<float> Coeff1, sampler Coeff1Smplr, thread float& gl_FragDepth, constant Fog& _792, thread float& sphericalVertexDistance, thread float& cylindricalVertexDistance)
{
    float4 param = color;
    float4 _788 = sampleColorForAccumulation(param, _412, DepthBoundsSampler, DepthBoundsSamplerSmplr, gl_FragCoord, Coeff0, Coeff0Smplr, Coeff1, Coeff1Smplr, gl_FragDepth);
    color = _788;
    float4 fogColor = float4(_792.FogColor.xyz * color.w, _792.FogColor.w);
    float4 param_1 = color;
    float param_2 = sphericalVertexDistance;
    float param_3 = cylindricalVertexDistance;
    float param_4 = _792.FogEnvironmentalStart;
    float param_5 = _792.FogEnvironmentalEnd;
    float param_6 = _792.FogRenderDistanceStart;
    float param_7 = _792.FogRenderDistanceEnd;
    float4 param_8 = fogColor;
    return apply_fog(param_1, param_2, param_3, param_4, param_5, param_6, param_7, param_8);
}

fragment main0_out main0(main0_in in [[stage_in]], constant Globals& _835 [[buffer(1)]], constant Projection& _412 [[buffer(2)]], constant TerrainUniform& _849 [[buffer(3)]], constant Fog& _792 [[buffer(4)]], texture2d<float> DepthBoundsSampler [[texture(1)]], texture2d<float> Coeff0 [[texture(2)]], texture2d<float> Coeff1 [[texture(3)]], texture2d<float> Sampler0 [[texture(4)]], sampler DepthBoundsSamplerSmplr [[sampler(1)]], sampler Coeff0Smplr [[sampler(2)]], sampler Coeff1Smplr [[sampler(3)]], sampler Sampler0Smplr [[sampler(4)]], float4 gl_FragCoord [[position]])
{
    main0_out out = {};
    float4 _841 = {};
    if (_835.UseRgss == 1)
    {
        float2 param = in.texCoord0;
        float2 param_1 = float2(1.0) / float2(_849.TextureSize);
        _841 = sampleRGSS(Sampler0, Sampler0Smplr, param, param_1);
    }
    else
    {
        float2 param_2 = in.texCoord0;
        float2 param_3 = float2(1.0) / float2(_849.TextureSize);
        _841 = sampleNearest(Sampler0, Sampler0Smplr, param_2, param_3);
    }
    float4 color = _841 * in.vertexColor;
    color = mix(_792.FogColor * float4(1.0, 1.0, 1.0, color.w), color, float4(in.chunkVisibility));
    if (color.w < 0.100000001490116119384765625)
    {
        discard_fragment();
    }
    float4 param_4 = color;
    float4 _896 = calculateFinalColor(param_4, _412, DepthBoundsSampler, DepthBoundsSamplerSmplr, gl_FragCoord, Coeff0, Coeff0Smplr, Coeff1, Coeff1Smplr, out.gl_FragDepth, _792, in.sphericalVertexDistance, in.cylindricalVertexDistance);
    out.fragColor = _896;
    return out;
}

