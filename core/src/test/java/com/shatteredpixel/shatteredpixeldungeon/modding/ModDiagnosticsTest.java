package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
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
import static org.junit.Assert.assertTrue;

/**
 * M16c tests for the per-mod diagnostics pipeline: scanner records skipped directories as orphan
 * diagnostics, LuaEngine records entry/script/register failures + counts, and rescan clears stale
 * diagnostics. Mirrors the {@link ModScannerTest} / {@link LuaModEntryTest} headless setup (fresh
 * in-memory {@code Preferences} per test so {@code mod_enabled_*} never leaks across tests).
 *
 * <p>PLAN lists {@code duplicateId_recordsError}; that branch is unreachable end-to-end because
 * {@link ModScanner} enforces id==dirname before the within-scan duplicate check, so two dirs can
 * never collide on id within one scan. The reachable "two mods same id" case is the builtin-vs-
 * external merge shadow, covered here by {@link #externalShadowed_recordsWarning}.
 */
public class ModDiagnosticsTest {

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

	// ---------------- scan-problem diagnostics ----------------

	@Test
	public void badManifest_recordsError() throws IOException {
		File modsDir = newModsDir();
		buildMod(modsDir, "broken", "{'id':'broken','name':'x'}"); // missing version + spd_version
		ModRegistry.scanDir(new FileHandle(modsDir));

		// The broken dir is skipped from all(); its diagnostic lives in the orphan map.
		assertTrue("broken mod must not be in the discovered list", ModRegistry.all().isEmpty());
		Map<String, ModDiagnostics> orphans = ModRegistry.orphanDiagnostics();
		ModDiagnostics diag = orphans.get("scan:BUILTIN:broken");
		assertNotNull("an orphan diagnostic for the broken dir must exist", diag);
		assertEquals("a parse failure is an error", ModDiagnostics.Status.FAILED, diag.status());
		assertFalse("parse error text must be recorded", diag.errors().isEmpty());
	}

	@Test
	public void versionMismatch_recordsWarning() throws IOException {
		File modsDir = newModsDir();
		buildMod(modsDir, "old_mod", manifest("old_mod", 999, false));
		ModRegistry.scanDir(new FileHandle(modsDir));

		assertTrue(ModRegistry.all().isEmpty());
		ModDiagnostics diag = ModRegistry.orphanDiagnostics().get("scan:BUILTIN:old_mod");
		assertNotNull("version-mismatched dir must have an orphan diagnostic", diag);
		assertNotNull("declaredId must be captured so the UI can name it", diag.declaredId());
		assertFalse("version mismatch is a warning, not a fatal error", diag.warnings().isEmpty());
	}

	@Test
	public void idMismatch_recordsError() throws IOException {
		File modsDir = newModsDir();
		// directory "foo" declares id "bar" -> id-mismatch skip with declaredId captured.
		buildMod(modsDir, "foo", manifest("bar", TEST_VERSION_CODE, false));
		ModRegistry.scanDir(new FileHandle(modsDir));

		assertTrue(ModRegistry.all().isEmpty());
		ModDiagnostics diag = ModRegistry.orphanDiagnostics().get("scan:BUILTIN:foo");
		assertNotNull(diag);
		assertEquals("declared id captured for display", "bar", diag.declaredId());
		assertFalse("id mismatch is an error", diag.errors().isEmpty());
	}

	@Test
	public void externalShadowed_recordsWarning() throws IOException {
		File builtinDir = newModsDir();
		buildMod(builtinDir, "shared", manifest("shared", TEST_VERSION_CODE, false));
		File externalDir = tmp.newFolder("mods_user");
		buildMod(externalDir, "shared", manifest("shared", TEST_VERSION_CODE, false));

		// Drive the full builtin+external scan-then-merge so the shadow diagnostic is produced.
		ModScanner.ScanResult builtin = ModScanner.scanDirResult(new FileHandle(builtinDir));
		ModScanner.ScanResult external = ModScanner.scanExternalResult(new FileHandle(externalDir));
		ModScanner.ScanResult merged = ModScanner.mergeById(builtin, external);

		assertEquals(1, merged.manifests.size());
		assertEquals("builtin wins the id collision", "shared", merged.manifests.get(0).id);
		ModDiagnostics diag = merged.diagnostics.get("scan:EXTERNAL:shared");
		assertNotNull("the shadowed external mod must produce an orphan diagnostic", diag);
		assertFalse(diag.warnings().isEmpty());
	}

	// ---------------- LuaEngine status + counts ----------------

	@Test
	public void badEntry_recordsFailedStatus() throws IOException {
		File modsDir = newModsDir();
		// Declare an entry so LuaEngine actually loads it; the body throws at runtime.
		buildMod(modsDir, "badentry", manifestWithEntry("badentry", TEST_VERSION_CODE, true, "init.lua"));
		writeScript(modsDir, "badentry", "init.lua", "error(\"boom from badentry\")");
		ModRegistry.scanDir(new FileHandle(modsDir));

		LuaEngine.init();

		ModDiagnostics diag = ModRegistry.getDiagnostics("badentry");
		assertNotNull(diag);
		assertEquals("a runtime error in the entry script must mark the mod FAILED",
				ModDiagnostics.Status.FAILED, diag.status());
		assertFalse("the entry error must be recorded", diag.errors().isEmpty());
	}

	@Test
	public void disabled_mod_statusIsDisabled() throws Exception {
		// test_mod ships default_enabled=false; init must mark it DISABLED (not LOADED).
		ModRegistry.scanDir(realModsHandle());
		assertFalse("precondition: test_mod defaults disabled", ModRegistry.isEnabled("test_mod"));

		LuaEngine.init();

		ModDiagnostics diag = ModRegistry.getDiagnostics("test_mod");
		assertNotNull(diag);
		assertEquals(ModDiagnostics.Status.DISABLED, diag.status());
	}

	@Test
	public void enabled_mod_countsArePresent() throws Exception {
		ModRegistry.scanDir(realModsHandle());
		ModRegistry.setEnabled("test_mod", true);

		LuaEngine.init();

		ModDiagnostics diag = ModRegistry.getDiagnostics("test_mod");
		assertNotNull(diag);
		assertEquals("a clean enabled mod ends up LOADED", ModDiagnostics.Status.LOADED, diag.status());
		assertFalse("registered content counts must be recorded", diag.counts().isEmpty());
		assertTrue("test_mod_item registers at least one item",
				diag.counts().getOrDefault("items", 0) >= 1);
	}

	// ---------------- lifecycle ----------------

	@Test
	public void rescan_clearsOldDiagnostics() throws IOException {
		File modsDir = newModsDir();
		buildMod(modsDir, "broken", "{'id':'broken','name':'x'}"); // parse failure -> orphan
		ModRegistry.scanDir(new FileHandle(modsDir));
		assertFalse("precondition: orphan recorded on first scan",
				ModRegistry.orphanDiagnostics().isEmpty());

		// Rescan with a clean dir: the old orphan must not linger.
		File cleanDir = newModsDir();
		buildMod(cleanDir, "good_mod", manifest("good_mod", TEST_VERSION_CODE, false));
		ModRegistry.scanDir(new FileHandle(cleanDir));

		assertTrue("rescan must clear stale orphan diagnostics",
				ModRegistry.orphanDiagnostics().isEmpty());
		assertNotNull("good_mod is discovered and has a DISCOVERED diagnostic",
				ModRegistry.getDiagnostics("good_mod"));
	}

	@Test
	public void orphanDiagnostics_excludedFromAllMods() throws IOException {
		File modsDir = newModsDir();
		buildMod(modsDir, "broken", "{'id':'broken','name':'x'}");
		buildMod(modsDir, "good_mod", manifest("good_mod", TEST_VERSION_CODE, false));
		ModRegistry.scanDir(new FileHandle(modsDir));

		assertEquals("only the good mod is discovered", 1, ModRegistry.all().size());
		assertEquals("good_mod", ModRegistry.all().get(0).id);
		assertFalse("orphan map carries the broken dir", ModRegistry.orphanDiagnostics().isEmpty());
		// The all-diagnostics view includes BOTH discovered mods and orphans (the manager needs
		// both), but orphanDiagnostics() is the strict "not in all()" filter.
		Map<String, ModDiagnostics> orphans = ModRegistry.orphanDiagnostics();
		for (String key : orphans.keySet()) {
			assertFalse("orphan key must not collide with a real mod id", "good_mod".equals(key));
		}
	}

	// ---------------- helpers ----------------

	private int modsDirCounter = 0;

	private File newModsDir() throws IOException {
		// Unique name per call: some tests (e.g. rescan) build two mods dirs in one test, and
		// TemporaryFolder.newFolder throws if the folder already exists.
		return tmp.newFolder("mods_" + (modsDirCounter++));
	}

	private void buildMod(File modsDir, String dirName, String singleQuoteManifestJson) throws IOException {
		File modDir = new File(modsDir, dirName);
		modDir.mkdirs();
		Files.write(new File(modDir, "mod.json").toPath(),
				singleQuoteManifestJson.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
	}

	private void writeScript(File modsDir, String dirName, String scriptName, String body) throws IOException {
		File modDir = new File(modsDir, dirName);
		modDir.mkdirs();
		Files.write(new File(modDir, scriptName).toPath(), body.getBytes(StandardCharsets.UTF_8));
	}

	private static String manifest(String id, int spdVersion, boolean defaultEnabled) {
		return "{'id':'" + id + "','name':'" + id + "','version':'0.1','spd_version':" + spdVersion
				+ ",'default_enabled':" + defaultEnabled + "}";
	}

	private static String manifestWithEntry(String id, int spdVersion, boolean defaultEnabled, String entry) {
		return "{'id':'" + id + "','name':'" + id + "','version':'0.1','spd_version':" + spdVersion
				+ ",'default_enabled':" + defaultEnabled + ",'entry':'" + entry + "'}";
	}

	private static FileHandle realModsHandle() throws Exception {
		java.net.URL url = ModDiagnosticsTest.class.getClassLoader().getResource("mods");
		assertNotNull("assets/mods must be on the test classpath", url);
		return new FileHandle(new File(url.toURI()));
	}

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
