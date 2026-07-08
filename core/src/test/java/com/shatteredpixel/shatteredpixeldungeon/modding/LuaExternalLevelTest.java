package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M12d: an external mod's {@code register_level} must capture the mod's
 * {@link ModManifest.Origin#EXTERNAL} origin + {@link ModManifest#baseDir} so
 * {@link LuaLevelService#enterLevel} can read the level json from
 * {@code mods_user/<modId>/levels/<id>.json} via {@link DataDrivenLevel#fromFileHandle},
 * instead of the hardcoded classpath {@code mods/levels/} dir.
 *
 * <p>Covers:
 * <ol>
 *   <li>Happy path: external mod entry script registers a level; the registry entry
 *       carries origin=EXTERNAL + baseDir + null path (default resolved at load time),
 *       and {@code currentMod} is cleared after the entry script returns (no leak).</li>
 *   <li>The external level json loads through {@code baseDir.child(...)} with the same
 *       structural validation as a builtin level ({@link DataDrivenLevel#fromJsonValue}).</li>
 *   <li>Traversal/charset rejection at the {@code register_level} boundary: a level id
 *       must match {@link ModManifest#ID_PATTERN} (it is interpolated into a path), and an
 *       explicit path must be relative, backslash-free, {@code ..}-free, and {@code .json}.
 *       A bad id or path rejects the whole registration (registry stays clean, no throw).</li>
 * </ol>
 *
 * <p>Setup mirrors {@link LuaEngineExternalLoadTest}: headless libgdx +
 * {@code Game.versionCode=896} + a fresh {@link ModTestSupport.FakePreferences} per test.
 * The external fixture is a temp {@code mods_user/ext_lvl_mod/} dir seeded via
 * {@link ModRegistry#scanExternal(FileHandle)} so the test never depends on a real
 * {@code Gdx.files.local} mount.
 */
public class LuaExternalLevelTest {

	private static final int TEST_VERSION_CODE = 896;
	private static final String MOD_ID = "ext_lvl_mod";

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
	public void externalMod_registerLevel_capturesOriginBaseDirAndClearsCurrentMod() throws Exception {
		File modsUser = tmp.newFolder("mods_user");
		buildExternalLevelMod(modsUser, MOD_ID, "register_level{id='dungeon_a', name='Dungeon A'}");
		ModRegistry.scanExternal(new FileHandle(modsUser));
		ModRegistry.setEnabled(MOD_ID, true);

		LuaEngine.init();

		assertEquals("origin must be EXTERNAL after scanExternal",
				ModManifest.Origin.EXTERNAL, ModRegistry.get(MOD_ID).origin);

		LuaLevelRegistry.Entry entry = LuaLevelRegistry.get("dungeon_a");
		assertNotNull("register_level must register 'dungeon_a'", entry);
		assertEquals("registered level must carry the external origin",
				ModManifest.Origin.EXTERNAL, entry.origin);
		assertNotNull("registered level must carry the mod baseDir", entry.baseDir);
		assertEquals("baseDir must point at the external mod dir",
				MOD_ID, entry.baseDir.name());
		assertNull("no explicit path → null (default resolved per-origin at load time)",
				entry.path);
		assertNull("currentMod must be cleared after the entry script returns (no leak)",
				LuaEngine.currentMod);
	}

	@Test
	public void externalMod_level_loadsFromBaseDirWithSharedValidation() throws Exception {
		File modsUser = tmp.newFolder("mods_user");
		buildExternalLevelMod(modsUser, MOD_ID, "register_level{id='dungeon_a', name='Dungeon A'}");
		ModRegistry.scanExternal(new FileHandle(modsUser));
		ModRegistry.setEnabled(MOD_ID, true);

		LuaEngine.init();

		LuaLevelRegistry.Entry entry = LuaLevelRegistry.get("dungeon_a");
		assertNotNull(entry);
		// Mirror LuaLevelService.loadLevelById's external branch: baseDir.child("levels/<id>.json").
		FileHandle fh = entry.baseDir.child("levels/dungeon_a.json");
		assertTrue("external level json must exist under baseDir/levels/", fh.exists());

		DataDrivenLevel level = DataDrivenLevel.fromFileHandle(fh, "dungeon_a");
		assertNotNull("external level must parse (same validation as builtin)", level);
		level.create();

		// 5x5 wall-border + floor-interior fixture (see buildExternalLevelMod).
		assertEquals(5, level.width());
		assertEquals(5, level.height());
		assertEquals(25, level.length());
		assertEquals(12, level.entrance());
		assertEquals(Terrain.ENTRANCE, level.map[12]);
		assertEquals(Terrain.WALL, level.map[0]);
		assertEquals(Terrain.WALL, level.map[24]);
		assertTrue("entrance must be passable", level.passable[12]);
	}

	@Test
	public void registerLevel_rejectsTraversalIdsAndInvalidPaths() throws Exception {
		File modsUser = tmp.newFolder("mods_user");
		// One valid registration plus five malformed ones. register_level's catch swallows the
		// validation throw (logs + returns nil), so every line runs and only the valid one registers.
		String entry =
				"register_level{id='good_lvl', name='Good'} "
				+ "register_level{id='../evil', name='E'} "
				+ "register_level{id='bad_path', name='B', path='../../escape.json'} "
				+ "register_level{id='bad_abs', name='A', path='/abs/x.json'} "
				+ "register_level{id='bad_ext', name='X', path='foo.txt'} "
				+ "register_level{id='UPPER', name='U'}";
		buildExternalLevelMod(modsUser, MOD_ID, entry);
		ModRegistry.scanExternal(new FileHandle(modsUser));
		ModRegistry.setEnabled(MOD_ID, true);

		LuaEngine.init();

		assertTrue("valid lowercase id with no path must register", LuaLevelRegistry.contains("good_lvl"));
		assertFalse("traversal id '../evil' must be rejected", LuaLevelRegistry.contains("../evil"));
		assertFalse("'..' path must reject the registration", LuaLevelRegistry.contains("bad_path"));
		assertFalse("absolute path must reject the registration", LuaLevelRegistry.contains("bad_abs"));
		assertFalse("non-.json path must reject the registration", LuaLevelRegistry.contains("bad_ext"));
		assertFalse("uppercase id fails [a-z0-9_]+ and must be rejected", LuaLevelRegistry.contains("UPPER"));
		assertNull("currentMod must still be cleared after a rejection-heavy entry script",
				LuaEngine.currentMod);
	}

	/** Build {@code mods_user/<id>/{mod.json, entry.lua, levels/dungeon_a.json}}. The level json is a
	 *  5x5 wall-border + floor-interior map with entrance at the centre (cell 12) — structurally
	 *  valid per {@link DataDrivenLevel#fromJsonValue}. */
	private void buildExternalLevelMod(File modsUser, String id, String entryBody) throws IOException {
		File modDir = new File(modsUser, id);
		modDir.mkdirs();
		String json = ("{'id':'" + id + "','name':'Ext Levels','version':'0.1','spd_version':"
				+ TEST_VERSION_CODE + ",'default_enabled':true,'entry':'entry.lua'}")
				.replace('\'', '"');
		Files.write(new File(modDir, "mod.json").toPath(), json.getBytes(StandardCharsets.UTF_8));

		File entryFile = new File(modDir, "entry.lua");
		entryFile.getParentFile().mkdirs();
		Files.write(entryFile.toPath(), entryBody.replace('\'', '"').getBytes(StandardCharsets.UTF_8));

		new File(modDir, "levels").mkdirs();
		Files.write(new File(modDir, "levels/dungeon_a.json").toPath(),
				dungeonAJson().getBytes(StandardCharsets.UTF_8));
	}

	private static String dungeonAJson() {
		// 5x5: wall border, floor interior, entrance at centre (pos 12). Row-major, 25 quoted tiles.
		String[][] grid = {
				{"wall",  "wall",  "wall",     "wall",  "wall"},
				{"wall",  "floor", "floor",    "floor", "wall"},
				{"wall",  "floor", "entrance", "floor", "wall"},
				{"wall",  "floor", "floor",    "floor", "wall"},
				{"wall",  "wall",  "wall",     "wall",  "wall"}
		};
		StringBuilder t = new StringBuilder();
		for (int y = 0; y < 5; y++) {
			for (int x = 0; x < 5; x++) {
				if (t.length() > 0) t.append(",");
				t.append("'").append(grid[y][x]).append("'");
			}
		}
		return ("{'width':5,'height':5,'entrance':12,'safe':true,'tiles':["
				+ t + "]}").replace('\'', '"');
	}
}
