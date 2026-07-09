package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.watabou.utils.GameSettings;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Process-wide registry of discovered mods and their enable/disable state. M5a builds the data
 * model; M5b wires {@code LuaEngine} to load a mod's scripts only when {@link #isEnabled(String)}
 * is true, and adds the toggle UI.
 *
 * <p>M16c: added per-mod {@link ModDiagnostics} capture. Diagnostics are cleared at the start of
 * {@link #scan()} and then seeded from {@link ModScanner.ScanResult}. LuaEngine updates them during
 * init. Orphan diagnostics (keys not matching any discovered mod, e.g. bad manifests) are exposed
 * for the "scan problems" section in {@link WndModManager}.
 *
 * <p>Enable state is persisted via upstream {@link GameSettings} prefs under
 * {@code mod_enabled_<id>}, defaulting to the manifest's {@link ModManifest#default_enabled}.
 * This is a read/write of a public upstream API, not an upstream edit.
 */
public final class ModRegistry {

	private static volatile List<ModManifest> scanned = Collections.emptyList();
	private static volatile boolean initialized = false;
	private static final Map<String, ModDiagnostics> diagnostics = new LinkedHashMap<>();

	private ModRegistry() {}

	public static synchronized void scan() {
		clearDiagnostics();
		ModScanner.ScanResult result = ModScanner.scan();
		scanFrom(result.manifests);
		mergeDiagnostics(result.diagnostics);
	}

	// Package-private seam: lets tests (and a future explicit rescan) load manifests from a
	// specific directory rather than the default Gdx.files.internal("mods") path.
	static synchronized void scanDir(FileHandle baseDir) {
		clearDiagnostics();
		ModScanner.ScanResult result = ModScanner.scanDirResult(baseDir);
		scanFrom(result.manifests);
		mergeDiagnostics(result.diagnostics);
	}

	/**
	 * Test seam mirroring {@link #scanDir} for external mods (M12a): seeds the registry from an
	 * external {@code mods_user/}-style directory so {@link LuaEngine}'s external-loading path can
	 * be exercised headlessly without a real {@code Gdx.files.local} mount. Production scans external
	 * mods via {@link ModScanner#scan()}; this seam exists only for tests.
	 */
	static synchronized void scanExternal(FileHandle baseDir) {
		clearDiagnostics();
		ModScanner.ScanResult result = ModScanner.scanExternalResult(baseDir);
		scanFrom(result.manifests);
		mergeDiagnostics(result.diagnostics);
	}

	private static synchronized void scanFrom(List<ModManifest> result) {
		scanned = result;
		initialized = true;
		for (ModManifest m : scanned) {
			diagnostics.computeIfAbsent(m.id, k -> new ModDiagnostics()).setStatus(ModDiagnostics.Status.DISCOVERED);
		}
		applyEnabledBalanceOverrides();
	}

	private static void mergeDiagnostics(Map<String, ModDiagnostics> incoming) {
		for (Map.Entry<String, ModDiagnostics> e : incoming.entrySet()) {
			diagnostics.merge(e.getKey(), e.getValue(), (a, b) -> {
				ModDiagnostics merged = new ModDiagnostics();
				merged.setStatus(a.status());
				if (a.declaredId() != null) merged.setDeclaredId(a.declaredId());
				for (String s : a.errors()) merged.addError(s);
				for (String s : a.warnings()) merged.addWarning(s);
				for (String s : b.errors()) merged.addError(s);
				for (String s : b.warnings()) merged.addWarning(s);
				return merged;
			});
		}
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
		float luaDropProbMax = 0f;
		for (ModManifest m : scanned) {
			if (isEnabled(m.id)) {
				BalanceConfig.applyModOverrides(m.balance);
				if (m.balance != null && m.balance.containsKey("lua_item_drop_prob")) {
					double v = m.balance.get("lua_item_drop_prob");
					if (Double.isFinite(v) && v >= 0 && v <= 10000) {
						luaDropProbMax = Math.max(luaDropProbMax, (float) v);
					} else {
						addModWarning(m.id, "ignored invalid lua_item_drop_prob: " + v);
					}
				}
			}
		}
		BalanceConfig.LUA_ITEM_DROP_PROB = luaDropProbMax;
		Generator.setLuaItemProbability(luaDropProbMax, luaDropProbMax);
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

	// ---------------- diagnostics API ----------------

	public static synchronized ModDiagnostics getDiagnostics(String id) {
		return diagnostics.get(id);
	}

	public static synchronized Map<String, ModDiagnostics> allDiagnostics() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics));
	}

	public static synchronized Map<String, ModDiagnostics> orphanDiagnostics() {
		Set<String> known = new java.util.HashSet<>();
		for (ModManifest m : scanned) known.add(m.id);
		Map<String, ModDiagnostics> out = new LinkedHashMap<>();
		for (Map.Entry<String, ModDiagnostics> e : diagnostics.entrySet()) {
			if (!known.contains(e.getKey())) out.put(e.getKey(), e.getValue());
		}
		return Collections.unmodifiableMap(out);
	}

	static synchronized void setModStatus(String id, ModDiagnostics.Status status) {
		diagnostics.computeIfAbsent(id, k -> new ModDiagnostics()).setStatus(status);
	}

	static synchronized void addModError(String id, String message) {
		diagnostics.computeIfAbsent(id, k -> new ModDiagnostics()).addError(message);
	}

	static synchronized void addModWarning(String id, String message) {
		diagnostics.computeIfAbsent(id, k -> new ModDiagnostics()).addWarning(message);
	}

	static synchronized void setModCount(String id, String type, int value) {
		diagnostics.computeIfAbsent(id, k -> new ModDiagnostics()).setCount(type, value);
	}

	static synchronized void incrementModCount(String id, String type) {
		diagnostics.computeIfAbsent(id, k -> new ModDiagnostics()).incrementCount(type);
	}

	static synchronized void clearDiagnostics() {
		diagnostics.clear();
	}

	// Test seam: force re-scan on next all()/get() even if already initialized. Package-private
	// so only tests in this package can reset; production never needs to.
	static void resetForTests() {
		synchronized (ModRegistry.class) {
			scanned = Collections.emptyList();
			initialized = false;
			clearDiagnostics();
		}
	}
}
