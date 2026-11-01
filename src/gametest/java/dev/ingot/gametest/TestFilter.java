package dev.ingot.gametest;

import java.util.Arrays;

/** -Dingot.test.only=A,B runs only the game test classes with those simple names. */
final class TestFilter {
	private TestFilter() {
	}

	static boolean runs(Object test) {
		String only = System.getProperty("ingot.test.only");
		return only == null || only.isBlank() || Arrays.stream(only.split(",")).map(String::trim).anyMatch(test.getClass().getSimpleName()::equals);
	}
}
