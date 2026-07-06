package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.watabou.noosa.Game;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M5c C3 regression: {@link ModRegistry#isEnabled} must fully control every Lua registry. With
 * {@code test_mod} disabled, {@link LuaEngine#init()} leaves all seven registries empty (vanilla
 * playthrough loads zero Lua content — the baseline the fork must hold). With it enabled, every
 * shipped test script + the M5b entry item registers.
 *
 * <p>Assertions are exact (ID + size), not {@code size() > 0}: a weak size check would let a single
 * entry item mask a broken directory scan. Setup mirrors the other Lua tests via {@link ModTestSupport}
 * (version gate 896 + fresh prefs + real-mods scan); each test then overrides the toggle as needed.
 */
public class ModToggleRegressionTest {

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
		// enableTestMod seeds test_mod=true; individual tests override the toggle. resetLuaState
		// guarantees each test starts from genuinely empty registries (the disabled case must
		// observe emptiness, not whatever a prior test left behind).
		ModTestSupport.enableTestMod();
		ModTestSupport.resetLuaState();
	}

	@AfterClass
	public static void shutdown() {
		Game.versionCode = savedVersionCode;
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	@Test
	public void disabled_mod_loadsZeroLuaContent() {
		ModRegistry.setEnabled("test_mod", false);

		LuaEngine.init();

		// All 7 registries the loaders populate must be empty.
		assertEquals(0, LuaItemRegistry.size());
		assertEquals(0, LuaMobRegistry.size());
		assertEquals(0, LuaAllyRegistry.size());
		assertEquals(0, LuaHeroRegistry.size());
		assertEquals(0, LuaSpellRegistry.size());
		assertEquals(0, LuaNpcRegistry.size());
		assertEquals(0, LuaShopRegistry.size());

		// Representative IDs across entry + every directory must be absent. Guards against a
		// size==0 pass hiding a stray registration path.
		assertFalse("entry item must not register when disabled", LuaItemRegistry.contains("test_mod_item"));
		assertFalse("items dir must not load when disabled", LuaItemRegistry.contains("test_sword"));
		assertFalse(LuaMobRegistry.contains("test_mob"));
		assertFalse(LuaAllyRegistry.contains("test_ally"));
		assertFalse(LuaHeroRegistry.contains("test_hero"));
		assertFalse(LuaSpellRegistry.contains("test_spell"));
		assertFalse("town NPCs must not load when disabled", LuaNpcRegistry.contains("town_portal"));
		assertFalse(LuaNpcRegistry.contains("town_return"));
		assertFalse(LuaShopRegistry.contains("test_shop"));
	}

	@Test
	public void enabled_mod_loadsAllTestContent() {
		// @Before already set test_mod=true; verify the full content surface loads.
		LuaEngine.init();

		// Exact IDs: directory scripts across all 7 types + the M5b entry item.
		assertTrue("items dir script", LuaItemRegistry.contains("test_sword"));
		assertTrue(LuaItemRegistry.contains("test_axe"));
		assertTrue(LuaItemRegistry.contains("test_dagger"));
		assertTrue(LuaItemRegistry.contains("test_proc_weapon"));
		assertTrue(LuaItemRegistry.contains("test_equip_buff"));
		// M6-fast: 3 C-path data skins (Remished material reskins) added to test_mod.
		assertTrue(LuaItemRegistry.contains("rotten_organ"));
		assertTrue(LuaItemRegistry.contains("bone_shard"));
		assertTrue(LuaItemRegistry.contains("toxic_gland"));
		assertTrue("entry item", LuaItemRegistry.contains("test_mod_item"));
		assertTrue(LuaMobRegistry.contains("test_mob"));
		assertTrue(LuaAllyRegistry.contains("test_ally"));
		assertTrue(LuaHeroRegistry.contains("test_hero"));
		assertTrue(LuaSpellRegistry.contains("test_spell"));
		assertTrue(LuaNpcRegistry.contains("test_npc"));
		assertTrue(LuaNpcRegistry.contains("town_portal"));
		assertTrue(LuaNpcRegistry.contains("town_return"));
		assertTrue(LuaShopRegistry.contains("test_shop"));

		// Exact sizes: catches a missing/misnamed script that ID-checks alone could miss.
		assertEquals("8 item dir scripts + 1 entry item", 9, LuaItemRegistry.size());
		assertEquals(1, LuaMobRegistry.size());
		assertEquals(1, LuaAllyRegistry.size());
		assertEquals(1, LuaHeroRegistry.size());
		assertEquals(1, LuaSpellRegistry.size());
		assertEquals("3 NPC scripts (test_npc + town_portal + town_return)", 3, LuaNpcRegistry.size());
		assertEquals(1, LuaShopRegistry.size());
	}

	@Test
	public void initTwice_doesNotDuplicateRegistrations() {
		// init() is idempotent (guarded by `if (initialized) return;`). The meaningful regression
		// guard is re-running the scan after dropping the singleton: re-registering the same IDs
		// must overwrite, not append, so registry sizes stay stable (no duplication on re-scan).
		LuaEngine.init();
		int itemsAfterFirst = LuaItemRegistry.size();
		int npcsAfterFirst = LuaNpcRegistry.size();

		LuaEngine.resetForTests();
		LuaEngine.init();

		assertEquals("items must not duplicate on re-init", itemsAfterFirst, LuaItemRegistry.size());
		assertEquals("npcs must not duplicate on re-init", npcsAfterFirst, LuaNpcRegistry.size());
	}
}
