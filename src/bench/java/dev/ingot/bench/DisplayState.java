package dev.ingot.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * Whether the main display is awake and the login session unlocked, from CoreGraphics (macOS only, through FFM).
 *
 * <p>This matters more than anything else in the setup: with the display asleep or the screen locked, WindowServer
 * does not composite the game window, presentation never waits for the display, and the same configuration renders
 * about twice as fast as with the window on screen. A run in that state measures something no player sees, so the
 * harness marks it invalid.
 */
final class DisplayState {
	/** {@code known} is false off macOS or when CoreGraphics could not be queried; the other fields are then false. */
	record State(boolean known, boolean displayAsleep, boolean screenLocked) {
		boolean visible() {
			return !this.known || (!this.displayAsleep && !this.screenLocked);
		}

		String describe() {
			if (!this.known) {
				return "unknown";
			}
			return this.screenLocked ? "screen locked" : this.displayAsleep ? "display asleep" : "awake";
		}
	}

	private static final int UTF8 = 0x08000100; // kCFStringEncodingUTF8
	private static boolean resolved;
	private static MethodHandle mainDisplayId;
	private static MethodHandle displayIsAsleep;
	private static MethodHandle sessionCopyCurrentDictionary;
	private static MethodHandle stringCreateWithCString;
	private static MethodHandle dictionaryGetValue;
	private static MethodHandle booleanGetValue;
	private static MethodHandle release;

	private DisplayState() {
	}

	static synchronized State query() {
		if (!resolve()) {
			return new State(false, false, false);
		}
		try (Arena arena = Arena.ofConfined()) {
			int display = (int) mainDisplayId.invokeExact();
			boolean asleep = (int) displayIsAsleep.invokeExact(display) != 0;
			boolean locked = false;
			MemorySegment session = (MemorySegment) sessionCopyCurrentDictionary.invokeExact();
			if (!session.equals(MemorySegment.NULL)) {
				MemorySegment key = (MemorySegment) stringCreateWithCString.invokeExact(MemorySegment.NULL,
					arena.allocateFrom("CGSSessionScreenIsLocked"), UTF8);
				// The key is absent while the session is unlocked.
				MemorySegment value = (MemorySegment) dictionaryGetValue.invokeExact(session, key);
				locked = !value.equals(MemorySegment.NULL) && (byte) booleanGetValue.invokeExact(value) != 0;
				release.invokeExact(key);
				release.invokeExact(session);
			}
			return new State(true, asleep, locked);
		} catch (Throwable t) {
			Bench.LOGGER.warn("[bench] could not query the display state", t);
			return new State(false, false, false);
		}
	}

	private static boolean resolve() {
		if (resolved) {
			return mainDisplayId != null;
		}
		resolved = true;
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
			return false;
		}
		try {
			Linker linker = Linker.nativeLinker();
			// System frameworks live in the dyld shared cache, so look them up by install name (dlopen), not by file.
			SymbolLookup cg = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", Arena.global());
			SymbolLookup cf = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Arena.global());
			mainDisplayId = linker.downcallHandle(cg.find("CGMainDisplayID").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
			displayIsAsleep = linker.downcallHandle(cg.find("CGDisplayIsAsleep").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
			sessionCopyCurrentDictionary = linker.downcallHandle(cg.find("CGSessionCopyCurrentDictionary").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.ADDRESS));
			stringCreateWithCString = linker.downcallHandle(cf.find("CFStringCreateWithCString").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
			dictionaryGetValue = linker.downcallHandle(cf.find("CFDictionaryGetValue").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			booleanGetValue = linker.downcallHandle(cf.find("CFBooleanGetValue").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS));
			release = linker.downcallHandle(cf.find("CFRelease").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
			return true;
		} catch (RuntimeException | LinkageError e) {
			Bench.LOGGER.warn("[bench] CoreGraphics is not available; the display state will not be checked", e);
			mainDisplayId = null;
			return false;
		}
	}
}
