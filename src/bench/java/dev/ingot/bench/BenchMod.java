package dev.ingot.bench;

import net.fabricmc.api.ClientModInitializer;

/** Client entrypoint. Does nothing unless -Dingot.bench.scenario is set. */
public final class BenchMod implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		if (Bench.ACTIVE) {
			Bench.init();
		}
	}
}
