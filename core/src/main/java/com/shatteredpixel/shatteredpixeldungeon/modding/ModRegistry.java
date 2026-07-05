package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.files.FileHandle;
import com.watabou.utils.GameSettings;

import java.util.Collections;
import java.util.List;

/**
 * Process-wide registry of discovered mods and their enable/disable state. M5a builds the data
 * model; M5b wires {@code LuaEngine} to load a mod's scripts only when {@link #isEnabled(String)}
 * is true, and adds the toggle UI.
 *
 * <p>Enable state is persisted via upstream {@link GameSettings} prefs under
 * {@code mod_enabled_<id>}, defaulting to the manifest's {@link ModManifest#default_enabled}.
 * This is a read/write of a public upstream API, not an upstream edit.
 */
public final class ModRegistry {

	private static volatile List<ModManifest> scanned = Collections.emptyList();
	private static volatile boolean initialized = false;

	private ModRegistry() {}

	public static synchronized void scan() {
		scanFrom(ModScanner.scan());
	}

	// Package-private seam: lets tests (and a future explicit rescan) load manifests from a
	// specific directory rather than the default Gdx.files.internal("mods") path.
	static synchronized void scanDir(FileHandle baseDir) {
		scanFrom(ModScanner.scanDir(baseDir));
	}

	private static synchronized void scanFrom(List<ModManifest> result) {
		scanned = result;
		initialized = true;
	}

	public static synchronized List<ModManifest> all() {
		if (!initialized) {
			scan();
		}
		return Collections.unmodifiableList(scanned);
	}

	public static synchronized ModManifest get(String id) {
		for (ModManifest m : scanned) {
			if (m.id.equals(id)) return m;
		}
		return null;
	}

	public static boolean isEnabled(String id) {
		ModManifest m;
		synchronized (ModRegistry.class) {
			m = lookup(id);
		}
		if (m == null) return false;
		return GameSettings.getBoolean(prefKey(id), m.default_enabled);
	}

	public static void setEnabled(String id, boolean enabled) {
		GameSettings.put(prefKey(id), enabled);
	}

	private static ModManifest lookup(String id) {
		if (!initialized) {
			scan();
		}
		for (ModManifest m : scanned) {
			if (m.id.equals(id)) return m;
		}
		return null;
	}

	private static String prefKey(String id) {
		return "mod_enabled_" + id;
	}

	// Test seam: force re-scan on next all()/get() even if already initialized. Package-private
	// so only tests in this package can reset; production never needs to.
	static void resetForTests() {
		synchronized (ModRegistry.class) {
			scanned = Collections.emptyList();
			initialized = false;
		}
	}
}
