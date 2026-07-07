package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.watabou.noosa.Game;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M9a: {@link LuaEngine#activeEnabledModIds()} is the save-slot feature's source of truth for
 * "what mods are actually loaded right now". It must be captured once at init (the exact set the
 * loader loops will register) and NOT track subsequent pending toggles — otherwise a player who
 * disables a mod without restarting would see a stale snapshot or a false-positive "missing mod"
 * warning on a slot they just saved.
 *
 * <p>Setup mirrors {@link ModToggleRegressionTest} via {@link ModTestSupport}: headless app,
 * version gate 896, fresh prefs + real-mods scan, test_mod enabled per test.
 */
public class LuaEngineActiveModsTest {

	private static HeadlessApplication application;
	private static int savedVersionCode;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		savedVersionCode = Game.versionCode;
		Game.versionCode = 896;
	}

	@Before
	public void resetState() throws Exception {
		ModTestSupport.enableTestMod();
		ModTestSupport.resetLuaState();
	}

	@AfterClass
	public static void shutdown() {
		Game.versionCode = savedVersionCode;
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	@Test
	public void activeEnabledModIds_capturesInitTimeEnabledSet() {
		LuaEngine.init();

		// The captured set must equal exactly the mods that were enabled at init (test_mod=true).
		Set<String> expected = computeEnabledFromRegistry();
		assertEquals(expected, LuaEngine.activeEnabledModIds());
		assertTrue("test_mod was enabled pre-init, must be in the captured set",
				LuaEngine.activeEnabledModIds().contains("test_mod"));
	}

	@Test
	public void pendingToggleAfterInit_doesNotChangeCapturedSet() {
		// ModToggleRegressionTest already proves isEnabled drives the loaders. Here we prove the
		// M9a invariant: once captured, a pending disable (no restart) does NOT retroactively change
		// the active set the save-slot code reads — otherwise saving right after a toggle would
		// write a snapshot that disagrees with the still-loaded registry.
		LuaEngine.init();
		Set<String> atInit = new LinkedHashSet<>(LuaEngine.activeEnabledModIds());

		ModRegistry.setEnabled("test_mod", false);

		assertEquals("pending toggle must not mutate the captured set",
				atInit, LuaEngine.activeEnabledModIds());
		assertTrue("registry still has test_mod loaded until restart; snapshot must reflect that",
				LuaEngine.activeEnabledModIds().contains("test_mod"));
	}

	@Test
	public void disabledMod_yieldsEmptyActiveSet() {
		// Symmetric to the load-zero-content baseline: with the only contributing mod disabled,
		// the active set is empty, so a slot saved under this state has an empty snapshot.
		ModRegistry.setEnabled("test_mod", false);
		LuaEngine.init();

		assertTrue(LuaEngine.activeEnabledModIds().isEmpty());
		assertFalse(LuaEngine.activeEnabledModIds().contains("test_mod"));
	}

	@Test
	public void resetForTests_clearsActiveSet() {
		LuaEngine.init();
		assertFalse(LuaEngine.activeEnabledModIds().isEmpty());

		LuaEngine.resetForTests();

		assertTrue("resetForTests must drop the captured set so the next init re-captures cleanly",
				LuaEngine.activeEnabledModIds().isEmpty());
	}

	private static Set<String> computeEnabledFromRegistry() {
		LinkedHashSet<String> s = new LinkedHashSet<>();
		for (ModManifest m : ModRegistry.all()) {
			if (ModRegistry.isEnabled(m.id)) s.add(m.id);
		}
		return s;
	}
}
