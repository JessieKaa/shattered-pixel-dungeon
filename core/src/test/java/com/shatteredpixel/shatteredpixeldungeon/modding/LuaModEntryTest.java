package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.watabou.noosa.Game;
import com.watabou.utils.GameSettings;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Headless tests for the M5b mod entry-script mechanism: {@link LuaEngine} loads an enabled mod's
 * {@link ModManifest#entry} and skips disabled / entry-less / broken-entry mods gracefully.
 *
 * <p>Setup mirrors {@link ModScannerTest}: a libgdx {@link HeadlessApplication} with
 * {@code Game.versionCode=896} (so the version gate admits {@code test_mod}), a fresh HashMap-backed
 * {@link com.badlogic.gdx.Preferences} per test for {@code mod_enabled_*} isolation, and
 * {@link ModRegistry#resetForTests()} + {@link LuaItemRegistry#clear()} +
 * {@link LuaEngine#resetForTests()} so each test re-initialises from a clean slate.
 *
 * <p>The real-asset tests (enabled/disabled) pre-seed {@link ModRegistry} via
 * {@code scanDir(realModsHandle())} rather than relying on {@link LuaEngine}'s lazy
 * {@code Gdx.files.internal("mods").list()} — classpath directory listing is unreliable in headless
 * (see ModScannerTest.scan_findsTestModManifest), but production (Android/desktop) does enumerate
 * {@code mods/} correctly, so the lazy scan is fine outside tests.
 */
public class LuaModEntryTest {

	private static final int TEST_VERSION_CODE = 896;

	private static HeadlessApplication application;
	private static int savedVersionCode;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		savedVersionCode = Game.versionCode;
		Game.versionCode = TEST_VERSION_CODE;
	}

	@AfterClass
	public static void shutdown() {
		Game.versionCode = savedVersionCode;
		GameSettings.set(new FakePreferences());
		try { if (application != null) application.exit(); } catch (Throwable ignored) {}
	}

	@Before
	public void isolateState() {
		GameSettings.set(new FakePreferences());
		ModRegistry.resetForTests();
		ModTestSupport.resetLuaState();
	}

	// ---------------- entry loading (real test_mod asset) ----------------

	@Test
	public void entry_enabled_loadsTestModItem() throws Exception {
		ModRegistry.scanDir(realModsHandle());
		ModRegistry.setEnabled("test_mod", true);

		LuaEngine.init();

		assertTrue("test_mod_item should be registered when test_mod is enabled",
				LuaItemRegistry.contains("test_mod_item"));
		// M5c: test_sword loads from mods/test_mod/scripts/items/ (mod-scoped), so it
		// registers alongside the entry item when test_mod is enabled.
		assertTrue("test_sword must register when test_mod is enabled",
				LuaItemRegistry.contains("test_sword"));
	}

	@Test
	public void entry_disabled_skipsTestModItem() throws Exception {
		ModRegistry.scanDir(realModsHandle());
		// test_mod default_enabled=false; no setEnabled → disabled.

		LuaEngine.init();

		assertFalse("test_mod_item must NOT register when test_mod is disabled",
				LuaItemRegistry.contains("test_mod_item"));
		// M5c: test_sword is now mod-scoped (mods/test_mod/scripts/items/), so a disabled
		// test_mod must NOT register it either — the toggle fully controls all Lua content.
		assertFalse("test_sword is mod-gated; disabled test_mod must NOT register it",
				LuaItemRegistry.contains("test_sword"));
	}

	// ---------------- graceful skip (synthetic temp mods) ----------------

	@Test
	public void entry_modWithoutEntryField_skipsGracefully() throws Exception {
		File modsDir = newModsDir();
		buildMod(modsDir, "noentry", manifest("noentry", null));
		ModRegistry.scanDir(new FileHandle(modsDir));
		assertNotNull("noentry mod must be admitted (id matches dirname) or this test is vacuous",
				ModRegistry.get("noentry"));
		assertTrue("noentry defaults enabled", ModRegistry.isEnabled("noentry"));

		// init must complete without throwing even though noentry is enabled but has no entry.
		LuaEngine.init();

		assertFalse("no temp mod registers test_mod_item", LuaItemRegistry.contains("test_mod_item"));
	}

	@Test
	public void entry_missingFile_logsAndContinues() throws Exception {
		File modsDir = newModsDir();
		// Enabled mod whose entry resolves to a path that does not exist on the classpath.
		buildMod(modsDir, "badentry", manifest("badentry", "ghost.lua"));
		ModRegistry.scanDir(new FileHandle(modsDir));
		assertNotNull("badentry mod must be admitted (id matches dirname) or this test is vacuous",
				ModRegistry.get("badentry"));
		assertTrue("badentry defaults enabled", ModRegistry.isEnabled("badentry"));

		LuaEngine.init();  // must not crash; bad entry is logged and skipped.

		assertFalse("missing entry file must not register anything", LuaItemRegistry.contains("test_mod_item"));
	}

	// ---------------- M16c: entry runtime error records a diagnostic ----------------

	@Test
	public void entry_withRuntimeError_recordsError() throws Exception {
		File modsDir = newModsDir();
		buildMod(modsDir, "boommod", manifest("boommod", "boom.lua"));
		writeScript(modsDir, "boommod", "boom.lua", "error(\"boom\")");
		ModRegistry.scanDir(new FileHandle(modsDir));
		assertNotNull(ModRegistry.get("boommod"));

		LuaEngine.init();

		ModDiagnostics diag = ModRegistry.getDiagnostics("boommod");
		assertNotNull("a diagnostic must exist for the mod whose entry errored", diag);
		assertEquals("entry runtime error => FAILED", ModDiagnostics.Status.FAILED, diag.status());
		assertFalse("entry error text must be recorded", diag.errors().isEmpty());
	}

	// ---------------- entry path validation (ModManifest) ----------------

	@Test
	public void manifest_entryPath_rejectsUnsafeAndAcceptsSafe() {
		// Rejected: traversal, absolute, backslash, non-.lua.
		assertManifestRejected("{'id':'ok','name':'x','version':'1','spd_version':896,'entry':'../x.lua'}",
				"traversal ..");
		assertManifestRejected("{'id':'ok','name':'x','version':'1','spd_version':896,'entry':'/abs.lua'}",
				"absolute path");
		assertManifestRejected("{'id':'ok','name':'x','version':'1','spd_version':896,'entry':'a\\\\b.lua'}",
				"backslash");
		assertManifestRejected("{'id':'ok','name':'x','version':'1','spd_version':896,'entry':'noext'}",
				"non-.lua suffix");

		// Accepted: relative .lua path, and absent entry (null).
		ModManifest withEntry = ModManifest.fromJson(new JsonReader().parse(
				"{'id':'ok','name':'x','version':'1','spd_version':896,'entry':'init.lua'}".replace('\'', '"')));
		assertEquals("init.lua", withEntry.entry);

		ModManifest noEntry = ModManifest.fromJson(new JsonReader().parse(
				"{'id':'ok','name':'x','version':'1','spd_version':896}".replace('\'', '"')));
		assertNull(noEntry.entry);
	}

	// ---------------- helpers ----------------

	private void assertManifestRejected(String singleQuoteJson, String msg) {
		try {
			ModManifest.fromJson(new JsonReader().parse(singleQuoteJson.replace('\'', '"')));
			fail("expected IllegalArgumentException for " + msg);
		} catch (IllegalArgumentException ok) {
			// expected
		}
	}

	private File newModsDir() throws IOException {
		return tmp.newFolder("mods");
	}

	private void buildMod(File modsDir, String dirName, String singleQuoteManifestJson) throws IOException {
		File modDir = new File(modsDir, dirName);
		modDir.mkdirs();
		Files.write(new File(modDir, "mod.json").toPath(),
				singleQuoteManifestJson.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
	}

	/** Write a Lua file at the mod dir root (used for entry scripts that should error at runtime). */
	private static void writeScript(File modsDir, String dirName, String scriptName, String body) throws IOException {
		File modDir = new File(modsDir, dirName);
		modDir.mkdirs();
		Files.write(new File(modDir, scriptName).toPath(), body.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Manifest body for a temp mod. {@code id} MUST equal the directory name (ModScanner enforces
	 * id&harr;dirname); {@code entry=null} omits the field, otherwise declares the entry path.
	 */
	private static String manifest(String id, String entry) {
		String base = "{'id':'" + id + "','name':'Tmp','version':'0.1','spd_version':896,'default_enabled':true";
		if (entry == null) return base + "}";
		return base + ",'entry':'" + entry + "'}";
	}

	private static FileHandle realModsHandle() throws Exception {
		java.net.URL url = LuaModEntryTest.class.getClassLoader().getResource("mods");
		assertNotNull("assets/mods must be on the test classpath", url);
		return new FileHandle(new File(url.toURI()));
	}

	/** HashMap-backed Preferences for deterministic, isolated mod_enabled_* state. */
	private static final class FakePreferences implements com.badlogic.gdx.Preferences {
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
