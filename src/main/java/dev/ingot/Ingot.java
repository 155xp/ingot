package dev.ingot;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public final class Ingot {
	public static final String MOD_ID = "ingot";
	public static final Logger LOGGER = LogUtils.getLogger();

	private Ingot() {
	}

	public static boolean isMacOs() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
	}
}
