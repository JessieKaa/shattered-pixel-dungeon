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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * M12a: {@link LuaEngine} must load an external mod's scripts the same way it loads builtin ones,
 * resolving them from {@link ModManifest#baseDir} ({@code mods_user/<id>/}) instead of the
 * hardcoded {@code mods/<id>/} classpath path. Covers the two external loading paths added in
 * M12a: per-type script directories ({@code scripts/items/*.lua}) and the mod entry script.
 *
 * <p>Setup mirrors {@link LuaModEntryTest}: headless libgdx + {@code Game.versionCode=896} + a
 * fresh {@link ModTestSupport.FakePreferences} per test. The external fixture is a temp
 * {@code mods_user/ext_mod/} dir with a {@code mod.json}, a {@code scripts/items/} script, and an
 * entry script; it is seeded via {@link ModRegistry#scanExternal(FileHandle)} (the M12a test seam)
 * so the test never depends on a real {@code Gdx.files.local} mount.
 */
public class LuaEngineExternalLoadTest {

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
		GameSettings.set(new ModTestSupport.FakePreferences());
		try { if (application != null) application.exit(); } catch (Throwable ignored) {}
	}

	@Before
	public void isolateState() {
		GameSettings.set(new ModTestSupport.FakePreferences());
		ModRegistry.resetForTests();
		ModTestSupport.resetLuaState();
	}

	@Test
	public void externalMod_scriptsDir_loadsItem() throws Exception {
		File modsUser = tmp.newFolder("mods_user");
		// mod.json with no entry; the item is registered purely from scripts/items/.
		buildExternalMod(modsUser, "ext_mod", null, "scripts/items/ext_item.lua",
				"register_item{id='ext_item', name='Ext Item', tier=1}");
		ModRegistry.scanExternal(new FileHandle(modsUser));
		ModRegistry.setEnabled("ext_mod", true);

		LuaEngine.init();

		assertEquals("origin must be EXTERNAL after scanExternal",
				ModManifest.Origin.EXTERNAL, ModRegistry.get("ext_mod").origin);
		assertTrue("external scripts/items/*.lua must be loaded via baseDir",
				LuaItemRegistry.contains("ext_item"));
	}

	@Test
	public void externalMod_entry_loadsItem() throws Exception {
		File modsUser = tmp.newFolder("mods_user");
		// entry script registers an item; no scripts/items/ dir on this mod.
		buildExternalMod(modsUser, "ext_mod", "init.lua", null,
				"register_item{id='ext_entry_item', name='Ext Entry Item', tier=2}");
		ModRegistry.scanExternal(new FileHandle(modsUser));
		ModRegistry.setEnabled("ext_mod", true);

		LuaEngine.init();

		assertTrue("external entry script must be loaded via baseDir.child(entry)",
				LuaItemRegistry.contains("ext_entry_item"));
	}

	/** Build {@code mods_user/<id>/mod.json} plus, optionally, an entry script and/or one script
	 *  file. {@code scriptPath} is relative to the mod dir (e.g. {@code scripts/items/x.lua});
	 *  {@code entry} is the manifest entry field (also relative); both may be null. */
	private void buildExternalMod(File modsUser, String id, String entry,
	                              String scriptPath, String scriptBody) throws IOException {
		File modDir = new File(modsUser, id);
		modDir.mkdirs();
		StringBuilder json = new StringBuilder()
				.append("{'id':'").append(id)
				.append("','name':'Ext','version':'0.1','spd_version':")
				.append(TEST_VERSION_CODE)
				.append(",'default_enabled':true");
		if (entry != null) json.append(",'entry':'").append(entry).append("'");
		json.append("}");
		Files.write(new File(modDir, "mod.json").toPath(),
				json.toString().replace('\'', '"').getBytes(StandardCharsets.UTF_8));
		if (entry != null) {
			File entryFile = new File(modDir, entry);
			entryFile.getParentFile().mkdirs();
			Files.write(entryFile.toPath(), scriptBody.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
		}
		if (scriptPath != null) {
			File script = new File(modDir, scriptPath);
			script.getParentFile().mkdirs();
			Files.write(script.toPath(), scriptBody.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
		}
	}
}
