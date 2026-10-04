package dev.ingot.nativ;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Extracts libingot.dylib from the mod jar into a content-addressed cache directory and opens it.
 * The library stays loaded for the lifetime of the JVM (global arena).
 */
public final class NativeLibrary {
	private static final String RESOURCE = "/natives/macos/libingot.dylib";

	private static SymbolLookup lookup;

	private NativeLibrary() {
	}

	public static synchronized SymbolLookup lookup() {
		if (lookup == null) {
			lookup = SymbolLookup.libraryLookup(extract(), Arena.global());
		}
		return lookup;
	}

	private static Path extract() {
		String override = System.getProperty("ingot.nativePath");
		if (override != null) {
			return Path.of(override);
		}

		byte[] bytes;
		try (InputStream in = NativeLibrary.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("libingot.dylib is missing from the mod jar (was it built on macOS?)");
			}
			bytes = in.readAllBytes();
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read " + RESOURCE, e);
		}

		String hash;
		try {
			hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}

		Path dir = Path.of(System.getProperty("java.io.tmpdir"), "ingot-natives", hash);
		Path file = dir.resolve("libingot.dylib");
		try {
			if (!Files.isRegularFile(file) || Files.size(file) != bytes.length) {
				Files.createDirectories(dir);
				Path tmp = Files.createTempFile(dir, "libingot", ".tmp");
				Files.write(tmp, bytes);
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Failed to extract libingot.dylib to " + file, e);
		}
		return file;
	}
}
