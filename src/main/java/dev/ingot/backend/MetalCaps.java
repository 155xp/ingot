package dev.ingot.backend;

/**
 * Immutable facts about the Metal device, queried once at device creation. Owner: core.
 *
 * @param name                    MTLDevice.name
 * @param unifiedMemory           hasUnifiedMemory (true on all Apple Silicon)
 * @param appleFamily             highest N with supportsFamily(MTLGPUFamilyAppleN), 0 if none
 * @param maxBufferLength         maxBufferLength in bytes
 * @param uniformOffsetAlignment  reported as DeviceLimits.minUniformOffsetAlignment (16 on Apple GPUs, 256 otherwise)
 * @param maxTextureSize          16384
 * @param lowPower                isLowPower (integrated GPU on Intel Macs)
 */
public record MetalCaps( // CONTRACT
	String name,
	boolean unifiedMemory,
	int appleFamily,
	long maxBufferLength,
	int uniformOffsetAlignment,
	int maxTextureSize,
	boolean lowPower
) {
}
