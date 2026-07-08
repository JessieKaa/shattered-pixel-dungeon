package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.watabou.noosa.Game;
import com.watabou.utils.GameSettings;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M13b: the custom-level discovery API exposes {@link LuaLevelService#isEnterAllowed},
 * {@link LuaLevelService#listEnterableLevels}, and {@link LuaLevelService#levelDisplayName}.
 * These cover the C3 guarantee — a registered level is enterable in release, an unregistered
 * id stays debug-only — and the UI's id/name resolution, without standing up the full
 * {@code Dungeon}/{@code saveAll}/{@code switchLevel} stack (the enter flow itself is
 * exercised via desktop manual verify, as for the debug enterLevel).
 *
 * <p>Levels are registered directly via {@link LuaLevelRegistry#register} (the same call
 * {@code register_level} makes) so the test targets the discovery logic, not the Lua
 * registration path (already covered by {@link LuaExternalLevelTest}). {@code Game.version}
 * is toggled between a release and an INDEV string to drive {@link DeviceCompat#isDebug()}
 * both ways — it is null in the bare headless harness, which would otherwise NPE inside
 * {@code isDebug()}. Mirrors {@link LuaLevelInjectTest}'s version-toggle setup.
 */
public class LuaLevelDiscoveryTest {

	private static final String RELEASE_VERSION = "1.0.0-release";

	private static HeadlessApplication application;
	private static String prevVersion;
	private static int savedVersionCode;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		prevVersion = Game.version;
		savedVersionCode = Game.versionCode;
		Game.versionCode = 896;
	}

	@AfterClass
	public static void shutdown() {
		Game.version = prevVersion;
		Game.versionCode = savedVersionCode;
		GameSettings.set(new ModTestSupport.FakePreferences());
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	@Before
	public void isolateState() {
		GameSettings.set(new ModTestSupport.FakePreferences());
		ModTestSupport.resetLuaState();   // clears LuaLevelRegistry
		// Default to a non-INDEV version so DeviceCompat.isDebug() == false unless a test opts in.
		Game.version = RELEASE_VERSION;
	}

	@Test
	public void listEnterableLevels_emptyRegistry_yieldsEmptyList() {
		assertTrue("no registered levels → empty list", LuaLevelService.listEnterableLevels().isEmpty());
	}

	@Test
	public void listEnterableLevels_returnsRegisteredIdsSorted() {
		LuaLevelRegistry.register("gamma", levelTable("gamma", "Gamma Hub"));
		LuaLevelRegistry.register("alpha", levelTable("alpha", "Alpha Zone"));
		LuaLevelRegistry.register("beta_zone", levelTable("beta_zone", "Beta"));

		List<String> ids = LuaLevelService.listEnterableLevels();

		assertEquals("all registered ids are listed", 3, ids.size());
		assertEquals("ids are alphabetically sorted",
				Arrays.asList("alpha", "beta_zone", "gamma"), ids);
		assertEquals("registry membership matches exactly",
				new HashSet<>(LuaLevelRegistry.ids()), new HashSet<>(ids));
	}

	@Test
	public void isEnterAllowed_registeredId_trueInRelease() {
		LuaLevelRegistry.register("alpha", levelTable("alpha", "Alpha Zone"));

		assertTrue("registered id is enterable without debug",
				LuaLevelService.isEnterAllowed("alpha"));
	}

	@Test
	public void isEnterAllowed_unregisteredId_falseInRelease() {
		// Sanity: the test env defaults to a release (non-INDEV) version.
		assertFalse("test env must be release-like", com.watabou.utils.DeviceCompat.isDebug());

		LuaLevelRegistry.register("alpha", levelTable("alpha", "Alpha Zone"));

		assertFalse("an unregistered bare asset id is blocked in release (C3)",
				LuaLevelService.isEnterAllowed("mods_levels_alpha_not_registered"));
	}

	@Test
	public void isEnterAllowed_unregisteredId_trueInDebug() {
		// A debug build still admits unregistered ids — preserves the original debug-gated
		// behaviour for developers poking at bare assets.
		Game.version = "1.0.0-INDEV";
		assertTrue(com.watabou.utils.DeviceCompat.isDebug());

		assertTrue("unregistered id is allowed under debug",
				LuaLevelService.isEnterAllowed("anything_unregistered"));
	}

	@Test
	public void levelDisplayName_registered_returnsName() {
		LuaLevelRegistry.register("alpha", levelTable("alpha", "Alpha Zone"));

		assertEquals("Alpha Zone", LuaLevelService.levelDisplayName("alpha"));
	}

	@Test
	public void levelDisplayName_unregisteredOrMissingName_fallsBackToId() {
		LuaLevelRegistry.register("nameless", namelessTable());  // registered but no name field

		assertEquals("unregistered id falls back to itself",
				"not_registered", LuaLevelService.levelDisplayName("not_registered"));
		assertEquals("registered-but-missing-name falls back to id",
				"nameless", LuaLevelService.levelDisplayName("nameless"));
	}

	/** A registered level table carrying id + name, mirroring what {@code register_level} stores. */
	private static LuaTable levelTable(String id, String name) {
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf(id));
		tbl.set("name", LuaValue.valueOf(name));
		return tbl;
	}

	/** A malformed table with no {@code name} key — register_level would reject this, but the
	 *  discovery layer must not throw if one ever slips in (defensive fallback → id). */
	private static LuaTable namelessTable() {
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf("nameless"));
		return tbl;
	}
}
