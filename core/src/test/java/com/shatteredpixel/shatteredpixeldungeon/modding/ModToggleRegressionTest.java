package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
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
		assertEquals("M6c: disabled mod must contribute 0 Lua buffs", 0, LuaBuffRegistry.size());
		assertEquals("M7e: disabled mod must contribute 0 talent overrides", 0, LuaTalentOverride.size());

		// Representative IDs across entry + every directory must be absent. Guards against a
		// size==0 pass hiding a stray registration path.
		assertFalse("entry item must not register when disabled", LuaItemRegistry.contains("test_mod_item"));
		assertFalse("items dir must not load when disabled", LuaItemRegistry.contains("test_sword"));
		assertFalse(LuaMobRegistry.contains("test_mob"));
		assertFalse(LuaAllyRegistry.contains("test_ally"));
		assertFalse(LuaHeroRegistry.contains("test_hero"));
		assertFalse(LuaSpellRegistry.contains("test_spell"));
		assertFalse("M6d spell dir must not load when disabled", LuaSpellRegistry.contains("heal"));
		assertFalse("town NPCs must not load when disabled", LuaNpcRegistry.contains("town_portal"));
		assertFalse(LuaNpcRegistry.contains("town_return"));
		assertFalse(LuaShopRegistry.contains("test_shop"));
		assertFalse("M6c buffs must not load when disabled", LuaBuffRegistry.contains("gases_immunity"));
		assertFalse(LuaBuffRegistry.contains("cloak"));
		assertFalse("M11a shield guards must not load when disabled", LuaBuffRegistry.contains("wooden_shield_guard"));
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
		assertTrue("M6d weapon representative loads", LuaItemRegistry.contains("hooked_dagger"));
		assertTrue("M6d weapon representative loads", LuaItemRegistry.contains("kunai"));
		assertTrue("entry item", LuaItemRegistry.contains("test_mod_item"));
		assertTrue(LuaMobRegistry.contains("test_mob"));
		assertTrue("M6a blob PoC mob loads", LuaMobRegistry.contains("test_blob_rat"));
		assertTrue("M6b shaman elder mob loads", LuaMobRegistry.contains("shaman_elder"));
		assertTrue("M6b spider elite mob loads", LuaMobRegistry.contains("spider_elite"));
		assertTrue("M6b deep snail mob loads", LuaMobRegistry.contains("deep_snail"));
		assertTrue("M6b hydra mob loads", LuaMobRegistry.contains("hydra"));
		assertTrue("M6b maze shadow mob loads", LuaMobRegistry.contains("maze_shadow"));
		assertTrue("M6b buffer mob loads", LuaMobRegistry.contains("buffer"));
		assertTrue(LuaAllyRegistry.contains("test_ally"));
		assertTrue(LuaHeroRegistry.contains("test_hero"));
		assertTrue(LuaSpellRegistry.contains("test_spell"));
		assertTrue("M6d heal spell loads", LuaSpellRegistry.contains("heal"));
		assertTrue("M6d haste spell loads", LuaSpellRegistry.contains("haste"));
		assertTrue("M6d charm spell loads", LuaSpellRegistry.contains("charm"));
		assertTrue("M6d lightning spell loads", LuaSpellRegistry.contains("lightning_bolt"));
		assertTrue("M6d town portal spell loads", LuaSpellRegistry.contains("town_portal"));
		assertTrue("M6d summon spell loads", LuaSpellRegistry.contains("summon_beast"));
		assertTrue("M6d raise dead spell loads", LuaSpellRegistry.contains("raise_dead"));
		assertTrue("M6d sprout spell loads", LuaSpellRegistry.contains("sprout"));
		assertTrue(LuaNpcRegistry.contains("test_npc"));
		assertTrue(LuaNpcRegistry.contains("town_portal"));
		assertTrue(LuaNpcRegistry.contains("town_return"));
		assertTrue(LuaShopRegistry.contains("test_shop"));
		// M6c+M11a: 16 Remished buff ports + 5 shield_guard buffs. ID + exact-size guards.
		assertTrue("M6c gases_immunity loads", LuaBuffRegistry.contains("gases_immunity"));
		assertTrue("M6c cloak loads", LuaBuffRegistry.contains("cloak"));
		assertTrue("M6c chaos_shield_left loads", LuaBuffRegistry.contains("chaos_shield_left"));
		assertTrue("M11a wooden_shield_guard loads", LuaBuffRegistry.contains("wooden_shield_guard"));
		assertEquals("16 M6c + 5 M11a shield guard buff ports", 21, LuaBuffRegistry.size());
		// M7e: 2 talent override scripts (hearty_meal lower+desc, iron_will desc-only).
		// M8d1: +1 forwarded from register_talent (mod_example.lua → MOD_EXAMPLE_TALENT),
		// since register_talent reuses the M7e override path for desc/maxPoints/title.
		// M8d3: +2 forwarded (mod_tier34.lua → MOD_TIER3_TALENT, MOD_TIER4_TALENT).
		assertEquals("2 M7e overrides + 1 M8d1 + 2 M8d3 forwarded = 5", 5, LuaTalentOverride.size());
		assertTrue("M7e hearty_meal override loads", LuaTalentOverride.get(Talent.HEARTY_MEAL) != null);
		assertTrue("M7e iron_will override loads", LuaTalentOverride.get(Talent.IRON_WILL) != null);
		assertTrue("M8d1 mod_example talent registered", LuaTalentRegistry.isKnownModTalent("MOD_EXAMPLE_TALENT"));

		// Exact sizes: catches a missing/misnamed script that ID-checks alone could miss.
		assertEquals("10 item dir scripts + 15 M10a item dir scripts + 1 entry item", 26, LuaItemRegistry.size());
		assertEquals("test_mob + M6a PoC + 6 M6b PoC + 10 M10a mobs", 18, LuaMobRegistry.size());
		assertEquals(1, LuaAllyRegistry.size());
		assertEquals(1, LuaHeroRegistry.size());
		assertEquals("test_spell + 8 M6d + 22 M10a spells", 31, LuaSpellRegistry.size());
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
