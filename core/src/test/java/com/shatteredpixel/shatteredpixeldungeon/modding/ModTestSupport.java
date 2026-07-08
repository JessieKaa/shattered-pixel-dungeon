package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.files.FileHandle;
import com.watabou.utils.GameSettings;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared fixtures for mod-aware Lua tests: resolves the real {@code assets/mods} directory and
 * provides per-test isolation of mod enable-state plus a clean Lua registry/engine slate.
 *
 * <p>M5c: every {@code Lua*Test} that drives {@link LuaEngine#init()} depends on {@code test_mod}
 * being enabled (its scripts live under {@code mods/test_mod/scripts/<type>/} and only load for
 * enabled mods). Headless runs cannot lazy-list the {@code mods/} classpath dir reliably (see
 * {@link ModScannerTest}), so tests pre-seed {@link ModRegistry} via {@link #realModsHandle()}.
 * Factored here to avoid duplicating the ~80-line {@link FakePreferences} and the scan/enable
 * sequence across 10+ test classes (M5a {@code ModScannerTest} / M5b {@code LuaModEntryTest}
 * established the pattern; M5c generalises it).
 */
final class ModTestSupport {

	private ModTestSupport() {}

	/** Resolve the real {@code assets/mods} dir as a cwd-independent real-IO {@link FileHandle}
	 * (classpath directory listing itself is unreliable in headless). */
	static FileHandle realModsHandle() throws Exception {
		java.net.URL url = ModTestSupport.class.getClassLoader().getResource("mods");
		assert url != null : "assets/mods must be on the test classpath";
		return new FileHandle(new File(url.toURI()));
	}

	/** Fresh HashMap-backed prefs + re-scan real mods + enable {@code test_mod}. Call in {@code @Before}
	 *  for per-test isolation: a fresh {@link FakePreferences} every test means {@code mod_enabled_*}
	 *  state never leaks across tests (M5a/M5b established pattern). */
	static void enableTestMod() throws Exception {
		GameSettings.set(new FakePreferences());
		ModRegistry.resetForTests();
		ModRegistry.scanDir(realModsHandle());
		ModRegistry.setEnabled("test_mod", true);
	}

	/** Drop every Lua registry the 7 {@code loadXxxScripts} methods populate, then drop the
	 *  {@link LuaEngine} singleton so the next {@link LuaEngine#init()} re-scans. Mandatory in
	 *  {@code @Before}: without it, static registry contents from a prior test survive and make
	 *  disabled/idempotent assertions meaningless (the disabled case must observe genuinely empty
	 *  registries, not whatever the previous test left behind). */
	static void resetLuaState() {
		LuaItemRegistry.clear();
		LuaMobRegistry.clear();
		LuaAllyRegistry.clear();
		LuaHeroRegistry.clear();
		LuaSpellRegistry.clear();
		LuaNpcRegistry.clear();
		LuaShopRegistry.clear();
		LuaBuffRegistry.clear();
		LuaTalentOverride.clear();
		LuaTalentRegistry.clear();
		LuaPainterRegistry.clear();
		LuaTrapRegistry.clear();
		LuaLevelRegistry.clear(); // populated by register_level via loadModEntryScripts (not loadXxxScripts)
		LuaEngine.resetForTests();
	}

	/** HashMap-backed {@link com.badlogic.gdx.Preferences} for deterministic, isolated
	 *  {@code mod_enabled_*} state. Only the methods {@link GameSettings} touches are exercised;
	 *  the rest are minimal but correct. */
	static final class FakePreferences implements com.badlogic.gdx.Preferences {
		private final Map<String, Object> store = new HashMap<>();

		@Override public com.badlogic.gdx.Preferences putBoolean(String key, boolean val) { store.put(key, val); return this; }
		@Override public com.badlogic.gdx.Preferences putInteger(String key, int val) { store.put(key, val); return this; }
		@Override public com.badlogic.gdx.Preferences putLong(String key, long val) { store.put(key, val); return this; }
		@Override public com.badlogic.gdx.Preferences putFloat(String key, float val) { store.put(key, val); return this; }
		@Override public com.badlogic.gdx.Preferences putString(String key, String val) { store.put(key, val); return this; }
		@Override public com.badlogic.gdx.Preferences put(Map<String, ?> vals) { if (vals != null) store.putAll(vals); return this; }

		@Override public boolean getBoolean(String key) { return getBoolean(key, false); }
		@Override public int getInteger(String key) { return getInteger(key, 0); }
		@Override public long getLong(String key) { return getLong(key, 0L); }
		@Override public float getFloat(String key) { return getFloat(key, 0f); }
		@Override public String getString(String key) { return getString(key, ""); }

		@Override public boolean getBoolean(String key, boolean defValue) {
			Object v = store.get(key); return v instanceof Boolean ? (Boolean) v : defValue;
		}
		@Override public int getInteger(String key, int defValue) {
			Object v = store.get(key); return v instanceof Number ? ((Number) v).intValue() : defValue;
		}
		@Override public long getLong(String key, long defValue) {
			Object v = store.get(key); return v instanceof Number ? ((Number) v).longValue() : defValue;
		}
		@Override public float getFloat(String key, float defValue) {
			Object v = store.get(key); return v instanceof Number ? ((Number) v).floatValue() : defValue;
		}
		@Override public String getString(String key, String defValue) {
			Object v = store.get(key); return v instanceof String ? (String) v : defValue;
		}

		@Override public Map<String, ?> get() { return new HashMap<>(store); }
		@Override public boolean contains(String key) { return store.containsKey(key); }
		@Override public void clear() { store.clear(); }
		@Override public void remove(String key) { store.remove(key); }
		@Override public void flush() { /* no-op, in-memory */ }
	}
}
