package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
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

	/**
	 * Test seam mirroring {@link #scanDir} for external mods (M12a): seeds the registry from an
	 * external {@code mods_user/}-style directory so {@link LuaEngine}'s external-loading path can
	* be exercised headlessly without a real {@code Gdx.files.local} mount. Production scans external
	 * mods via {@link ModScanner#scan()}; this seam exists only for tests.
	 */
	static synchronized void scanExternal(FileHandle baseDir) {
		scanFrom(ModScanner.scanExternal(baseDir));
	}

	private static synchronized void scanFrom(List<ModManifest> result) {
		scanned = result;
		initialized = true;
		applyEnabledBalanceOverrides();
	}

	public static synchronized List<ModManifest> all() {
		if (!initialized) {
			scan();
		}
		return Collections.unmodifiableList(scanned);
	}

	public static ModManifest get(String id) {
		return lookup(id);
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
		// Re-merge balance overrides so toggling a balance mod takes effect
		// immediately (and disabling one clears its overrides). Guarded on
		// initialized so a pre-scan toggle doesn't trigger a scan here — the
		// eventual scanFrom will apply overrides then. scanFrom also calls
		// applyEnabledBalanceOverrides, so scan-time merging stays consistent.
		synchronized (ModRegistry.class) {
			if (initialized) applyEnabledBalanceOverrides();
		}
	}

	/**
	 * Reset {@link BalanceConfig} to defaults then re-apply every enabled mod's
	 * balance overrides, in scan order (later mods win per-key). Idempotent —
	 * rescans and enable toggles both converge to the same global state. Called
	 * from {@link #scanFrom} and {@link #setEnabled}. Must hold the ModRegistry
	 * monitor (both callers synchronize on {@code ModRegistry.class}).
	 */
	private static void applyEnabledBalanceOverrides() {
		BalanceConfig.resetToDefaults();
		for (ModManifest m : scanned) {
			if (isEnabled(m.id)) {
				BalanceConfig.applyModOverrides(m.balance);
			}
		}
		// Fork (M15c): propagate lua_spell_drop_prob from BalanceConfig to
		// Generator so the standard drop deck can roll LUA_SPELL.
		Generator.setLuaSpellDropProbability(BalanceConfig.LUA_SPELL_DROP_FIRST, BalanceConfig.LUA_SPELL_DROP_SECOND);
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
