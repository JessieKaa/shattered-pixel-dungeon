package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Headless tests for the M5a mod-manifest layer: {@link ModManifest} parsing, {@link ModScanner}
 * discovery + version gate, and {@link ModRegistry} prefs round-trip. Bootstraps a libgdx
 * {@link HeadlessApplication} (the established M0-M4 pattern) and injects a fresh in-memory
 * {@link com.badlogic.gdx.Preferences} per test so {@code mod_enabled_*} state never leaks
 * across tests.
 */
public class ModScannerTest {

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
		// Leave GameSettings with a clean working store rather than the last test's fake.
		GameSettings.set(new FakePreferences());
		try { if (application != null) application.exit(); } catch (Throwable ignored) {}
	}

	@Before
	public void isolateState() {
		GameSettings.set(new FakePreferences());
		ModRegistry.resetForTests();
	}

	// ---------------- ModManifest parsing ----------------

	@Test
	public void manifest_parsesAllFields() {
		String json = ("{'id':'alpha_mod','name':'Alpha','version':'1.2.3','spd_version':896,"
				+ "'author':'fork','default_enabled':true,'description':'desc'}").replace('\'', '"');
		ModManifest m = ModManifest.fromJson(new JsonReader().parse(json));
		assertEquals("alpha_mod", m.id);
		assertEquals("Alpha", m.name);
		assertEquals("1.2.3", m.version);
		assertEquals(896, m.spd_version);
		assertEquals("fork", m.author);
		assertTrue(m.default_enabled);
		assertEquals("desc", m.description);
	}

	@Test
	public void manifest_defaultEnabled_defaultsFalseAndAuthorBlank() {
		String json = "{'id':'beta','name':'Beta','version':'0.1','spd_version':896}".replace('\'', '"');
		ModManifest m = ModManifest.fromJson(new JsonReader().parse(json));
		assertFalse(m.default_enabled);
		assertEquals("", m.author);
		assertEquals("", m.description);
	}

	@Test
	public void manifest_rejectsMissingRequiredField() {
		// spd_version absent -> libgdx getInt throws IllegalArgumentException
		String json = "{'id':'beta','name':'Beta','version':'0.1'}".replace('\'', '"');
		assertManifestRejected(json, "missing spd_version");
	}

	@Test
	public void manifest_rejectsBadId() {
		assertManifestRejected("{'id':'Bad-Id','name':'x','version':'1','spd_version':896}", "uppercase/dash id");
		assertManifestRejected("{'id':'has space','name':'x','version':'1','spd_version':896}", "id with space");
		assertManifestRejected("{'id':'','name':'x','version':'1','spd_version':896}", "empty id");
	}

	@Test
	public void manifest_rejectsNonPositiveSpdVersion() {
		assertManifestRejected("{'id':'ok','name':'x','version':'1','spd_version':0}", "spd_version 0");
		assertManifestRejected("{'id':'ok','name':'x','version':'1','spd_version':-1}", "spd_version negative");
	}

	@Test
	public void manifest_rejectsWrongTypeForId() {
		// numeric id must not be coerced to string
		assertManifestRejected("{'id':123,'name':'x','version':'1','spd_version':896}", "numeric id");
	}

	@Test
	public void manifest_rejectsWrongTypeForSpdVersion() {
		// string spd_version must not be coerced to int
		assertManifestRejected("{'id':'ok','name':'x','version':'1','spd_version':'896'}", "string spd_version");
		assertManifestRejected("{'id':'ok','name':'x','version':'1','spd_version':896.5}", "float spd_version");
	}

	@Test
	public void manifest_acceptsNonCanonicalDecimalSpdVersion() {
		// libgdx JsonReader is lenient: a leading-zero token like 0896 (invalid strict JSON) still
		// parses as the integer 896. The version gate compares integer VALUES, so the declared
		// value (896) == evaluated value (896) == Game.versionCode(896). There is no integrity
		// bypass: an author declaring 0896 means 896, and JsonValue cannot distinguish the raw
		// tokens anyway. This test documents the intentional behavior.
		ModManifest m = ModManifest.fromJson(new JsonReader().parse(
				"{'id':'ok','name':'x','version':'1','spd_version':0896}".replace('\'', '"')));
		assertEquals(896, m.spd_version);
	}

	@Test
	public void manifest_rejectsOutOfRangeSpdVersion() {
		// 4294968192 == 2^32 + 896 narrows (via asInt l2i) to 896, which would bypass the version
		// gate; the range check must reject it before narrowing.
		assertManifestRejected("{'id':'ok','name':'x','version':'1','spd_version':4294968192}",
				"out-of-int-range spd_version wrapping to 896");
	}

	@Test
	public void manifest_rejectsWrongTypeForDefaultEnabled() {
		// string "true" must not be coerced to boolean
		assertManifestRejected("{'id':'ok','name':'x','version':'1','spd_version':896,'default_enabled':'true'}",
				"string default_enabled");
	}

	private void assertManifestRejected(String singleQuoteJson, String msg) {
		try {
			ModManifest.fromJson(new JsonReader().parse(singleQuoteJson.replace('\'', '"')));
			org.junit.Assert.fail("expected IllegalArgumentException for " + msg);
		} catch (IllegalArgumentException ok) {
			// expected
		}
	}

	// ---------------- ModScanner discovery + version gate ----------------

	@Test
	public void scan_findsTestModManifest() throws Exception {
		// Resolve the real assets/mods dir via the classpath URL (cwd-independent real-IO handle;
		// classpath directory listing itself is unreliable in headless, so we point scanDir at the
		// resolved real directory rather than Gdx.files.internal("mods")).
		List<ModManifest> mods = ModScanner.scanDir(realModsHandle());
		Set<String> ids = ids(mods);
		assertTrue("test_mod should be discovered", ids.contains("test_mod"));
		assertFalse("mods/levels/ has no mod.json and must be skipped", ids.contains("levels"));
		assertFalse("levels should not appear as a mod id", ids.contains("test_safezone"));
		ModManifest testMod = null;
		for (ModManifest m : mods) if (m.id.equals("test_mod")) testMod = m;
		assertNotNull(testMod);
		assertEquals(TEST_VERSION_CODE, testMod.spd_version);
		assertFalse("test_mod default_enabled must be false", testMod.default_enabled);
	}

	@Test
	public void scan_acceptsMatchingVersion() throws IOException {
		File modsDir = newModsDir();
		buildMod(modsDir, "good_mod", manifest("good_mod", TEST_VERSION_CODE, false));
		List<ModManifest> mods = ModScanner.scanDir(new FileHandle(modsDir));
		assertEquals(ids(mods), Set.of("good_mod"));
	}

	@Test
	public void scan_rejectsVersionMismatch() throws IOException {
		File modsDir = newModsDir();
		buildMod(modsDir, "old_mod", manifest("old_mod", 999, false));
		assertTrue("version-mismatched mod must be skipped", ModScanner.scanDir(new FileHandle(modsDir)).isEmpty());
	}

	@Test
	public void scan_skipsDirWithoutManifest() throws IOException {
		File modsDir = newModsDir();
		// a subdir with no mod.json (mirrors real mods/levels/)
		assertTrue(new File(modsDir, "no_manifest").mkdirs());
		buildMod(modsDir, "good_mod", manifest("good_mod", TEST_VERSION_CODE, false));
		List<ModManifest> mods = ModScanner.scanDir(new FileHandle(modsDir));
		assertEquals(ids(mods), Set.of("good_mod"));
	}

	@Test
	public void scan_skipsMalformedManifest() throws IOException {
		File modsDir = newModsDir();
		buildMod(modsDir, "broken", "{'id':'broken','name':'x'}"); // missing version + spd_version
		buildMod(modsDir, "good_mod", manifest("good_mod", TEST_VERSION_CODE, false));
		List<ModManifest> mods = ModScanner.scanDir(new FileHandle(modsDir));
		assertEquals("broken mod skipped, good mod kept", ids(mods), Set.of("good_mod"));
	}

	@Test
	public void scan_skipsWrongTypeFields() throws IOException {
		File modsDir = newModsDir();
		// string spd_version (type error) -> skip; sibling good mod kept
		buildMod(modsDir, "typed_wrong", "{'id':'typed_wrong','name':'x','version':'1','spd_version':'896'}");
		buildMod(modsDir, "good_mod", manifest("good_mod", TEST_VERSION_CODE, false));
		assertEquals(ids(ModScanner.scanDir(new FileHandle(modsDir))), Set.of("good_mod"));
	}

	@Test
	public void scan_rejectsIdDirnameMismatch() throws IOException {
		File modsDir = newModsDir();
		// directory "foo" declares id "bar" -> must be skipped (codex round-1 must-fix)
		buildMod(modsDir, "foo", manifest("bar", TEST_VERSION_CODE, false));
		assertTrue(ModScanner.scanDir(new FileHandle(modsDir)).isEmpty());
	}

	@Test
	public void scan_multipleMods_allListed() throws IOException {
		File modsDir = newModsDir();
		buildMod(modsDir, "alpha_mod", manifest("alpha_mod", TEST_VERSION_CODE, false));
		buildMod(modsDir, "beta_mod", manifest("beta_mod", TEST_VERSION_CODE, true));
		Set<String> ids = ids(ModScanner.scanDir(new FileHandle(modsDir)));
		assertEquals("distinct mods must not be over-deduplicated", Set.of("alpha_mod", "beta_mod"), ids);
	}

	@Test
	public void scan_nullGdxFiles_returnsEmpty() {
		com.badlogic.gdx.Files saved = Gdx.files;
		Gdx.files = null;
		try {
			assertTrue("scan() must not NPE when Gdx.files is null", ModScanner.scan().isEmpty());
		} finally {
			Gdx.files = saved;
		}
	}

	// ---------------- M12a external scan + merge ----------------

	@Test
	public void scanExternal_setsExternalOriginAndBaseDir() throws IOException {
		File externalDir = tmp.newFolder("mods_user");
		buildMod(externalDir, "ext_mod", manifest("ext_mod", TEST_VERSION_CODE, false));
		List<ModManifest> mods = ModScanner.scanExternal(new FileHandle(externalDir));

		assertEquals(ids(mods), Set.of("ext_mod"));
		ModManifest m = mods.get(0);
		assertEquals("external mod must carry EXTERNAL origin", ModManifest.Origin.EXTERNAL, m.origin);
		assertNotNull("external mod must have a baseDir", m.baseDir);
		assertTrue("baseDir must point at the mod's own directory, got " + m.baseDir.path(),
				m.baseDir.path().endsWith("ext_mod"));
	}

	@Test
	public void scanExternal_missingDir_returnsEmpty() {
		File missing = new File(tmp.getRoot(), "does_not_exist");
		assertTrue("a non-existent mods_user/ must yield zero external mods (no crash)",
				ModScanner.scanExternal(new FileHandle(missing)).isEmpty());
		assertEquals("null external root is also tolerated", 0, ModScanner.scanExternal(null).size());
	}

	@Test
	public void merge_builtinShadowsExternalById() throws IOException {
		// Builtin "shared" + external "shared" (collision) and "ext_only" (distinct).
		File builtinDir = newModsDir();
		buildMod(builtinDir, "shared", manifest("shared", TEST_VERSION_CODE, false));
		File externalDir = tmp.newFolder("mods_user");
		buildMod(externalDir, "shared", manifest("shared", TEST_VERSION_CODE, false));
		buildMod(externalDir, "ext_only", manifest("ext_only", TEST_VERSION_CODE, false));

		List<ModManifest> builtin = ModScanner.scanDir(new FileHandle(builtinDir));
		List<ModManifest> external = ModScanner.scanExternal(new FileHandle(externalDir));
		List<ModManifest> merged = ModScanner.mergeById(builtin, external);

		assertEquals("both ids present after merge", Set.of("shared", "ext_only"), ids(merged));
		ModManifest shared = byId(merged, "shared");
		assertEquals("builtin must win id collision", ModManifest.Origin.BUILTIN, shared.origin);
		assertEquals("distinct external mod kept as EXTERNAL",
				ModManifest.Origin.EXTERNAL, byId(merged, "ext_only").origin);
	}

	@Test
	public void scanDir_setsBuiltinOriginAndBaseDir() throws IOException {
		// Builtin test seam must still annotate BUILTIN + baseDir (regression guard for the
		// Origin-param refactor of scanChildren).
		File modsDir = newModsDir();
		buildMod(modsDir, "b_mod", manifest("b_mod", TEST_VERSION_CODE, false));
		ModManifest m = ModScanner.scanDir(new FileHandle(modsDir)).get(0);
		assertEquals(ModManifest.Origin.BUILTIN, m.origin);
		assertNotNull(m.baseDir);
		assertTrue(m.baseDir.path().endsWith("b_mod"));
	}

	// ---------------- ModRegistry round-trip ----------------

	@Test
	public void registry_isEnabled_usesDefaultOnFirstRead() throws Exception {
		ModRegistry.scanDir(realModsHandle()); // loads real test_mod (default_enabled=false)
		assertFalse("test_mod default is false", ModRegistry.isEnabled("test_mod"));
		assertFalse("unknown mod reports disabled", ModRegistry.isEnabled("does_not_exist"));
	}

	@Test
	public void registry_setEnabled_persistsViaPrefs() throws Exception {
		ModRegistry.scanDir(realModsHandle());
		ModRegistry.setEnabled("test_mod", true);
		assertTrue(ModRegistry.isEnabled("test_mod"));
		ModRegistry.setEnabled("test_mod", false);
		assertFalse(ModRegistry.isEnabled("test_mod"));
	}

	@Test
	public void registry_get_returnsNullForUnknown() throws Exception {
		ModRegistry.scanDir(realModsHandle());
		assertNotNull(ModRegistry.get("test_mod"));
		assertNull(ModRegistry.get("nope"));
	}

	@Test
	public void registry_scan_isIdempotent() throws Exception {
		ModRegistry.scanDir(realModsHandle());
		Set<String> first = ids(ModRegistry.all());
		ModRegistry.scanDir(realModsHandle());
		Set<String> second = ids(ModRegistry.all());
		assertEquals(first, second);
		assertTrue(first.contains("test_mod"));
	}

	// ---------------- helpers ----------------

	private File newModsDir() throws IOException {
		return tmp.newFolder("mods");
	}

	/** Resolve the real {@code assets/mods} dir from the test classpath as a cwd-independent
	 * real-IO {@link FileHandle} (classpath directory listing is unreliable in headless). */
	private static FileHandle realModsHandle() throws Exception {
		java.net.URL url = ModScannerTest.class.getClassLoader().getResource("mods");
		assertNotNull("assets/mods must be on the test classpath", url);
		return new FileHandle(new File(url.toURI()));
	}

	private void buildMod(File modsDir, String dirName, String singleQuoteManifestJson) throws IOException {
		File modDir = new File(modsDir, dirName);
		modDir.mkdirs();
		Files.write(new File(modDir, "mod.json").toPath(),
				singleQuoteManifestJson.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
	}

	/** Minimal valid manifest body with the common knobs; single-quoted for the replace trick. */
	private static String manifest(String id, int spdVersion, boolean defaultEnabled) {
		return "{'id':'" + id + "','name':'" + id + "','version':'0.1','spd_version':" + spdVersion
				+ ",'default_enabled':" + defaultEnabled + "}";
	}

	private static Set<String> ids(List<ModManifest> mods) {
		Set<String> out = new TreeSet<>();
		for (ModManifest m : mods) out.add(m.id);
		return out;
	}

	private static ModManifest byId(List<ModManifest> mods, String id) {
		for (ModManifest m : mods) if (m.id.equals(id)) return m;
		throw new AssertionError("no mod with id " + id + " in " + ids(mods));
	}

	/**
	 * HashMap-backed {@link com.badlogic.gdx.Preferences} for deterministic, isolated
	 * {@code mod_enabled_*} state. Only the methods GameSettings touches are exercised; the rest
	 * are minimal but correct.
	 */
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
