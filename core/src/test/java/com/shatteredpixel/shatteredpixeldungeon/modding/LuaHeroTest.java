package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.watabou.utils.Bundle;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M3c Lua hero tests. Mirrors {@link LuaMobTest}'s headless harness.
 *
 * <p>What is pinned down here:
 * <ol>
 *   <li>{@link LuaHeroRegistry} register/get/all/contains/size/clear + {@link LuaHeroClass#hydrate}
 *       validation (required fields enforced, invalid talentSource rejected).</li>
 *   <li>{@code register_hero} global: valid table registers; missing id/name/talentSource/hp
 *       rejected; an unknown talentSource rejected; the shipped {@code test_hero.lua}
 *       registers via {@link LuaEngine#init()}.</li>
 *   <li><b>CRITICAL (D3)</b>: a Lua hero (MAGE host — distinct from the WARRIOR fallback)
 *       survives a Bundle round-trip. Asserted at the raw-bundle level
 *       ({@code class == "MAGE"}, {@code lua_class_id == id}) AND after restore
 *       ({@code heroClass == MAGE}, {@code luaClassId == id}). Using MAGE proves the
 *       enum channel round-trips cleanly rather than silently falling back to
 *       WARRIOR ({@code Bundle.java:166-174} returns {@code getEnumConstants()[0]}
 *       on any unknown name).</li>
 *   <li>{@link Hero#initLuaHero}: sets heroClass = host, luaClassId, hp/HT/defenseSkill,
 *       and binds the host's talent tree (D2 — {@link Talent#initClassTalents}).</li>
 *   <li>{@link LuaHeroService} consume/clear/peek semantics (no stale id leak).</li>
 *   <li>M1 sandbox regression — {@code luajava.bindClass} still unreachable with
 *       {@code register_hero} present.</li>
 * </ol>
 *
 * <p>Item hydration + GameScene/HeroSelectScene UI are verified by the desktop run,
 * not headlessly (PLAN risk #2 / Step 9 desktop verification).
 */
public class LuaHeroTest {

	private static HeadlessApplication application;
	private static int savedVersionCode;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		// Game.version is null in the bare headless harness, which makes
		// Document.<clinit> (and downstream Badges/Catalog) throw. The real game
		// sets this on startup; mirror a non-empty value so the item-identify path
		// used by Hero.initLuaHero's replicated public section can run.
		com.watabou.noosa.Game.version = "test";
		// M5c: version gate admits test_mod (spd_version=896) so its hero script loads.
		savedVersionCode = com.watabou.noosa.Game.versionCode;
		com.watabou.noosa.Game.versionCode = 896;
	}

	@Before
	public void resetModAndLuaState() throws Exception {
		ModTestSupport.enableTestMod();
		ModTestSupport.resetLuaState();
	}

	@AfterClass
	public static void shutdown() {
		com.watabou.noosa.Game.versionCode = savedVersionCode;
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	private Globals globals() {
		Globals g = LuaSandbox.exposedGlobals();
		g.set("RPD", RpdApi.build());
		return g;
	}

	// ---- LuaHeroClass.hydrate + LuaHeroRegistry ----

	@Test
	public void hydrateParsesAllFields() {
		LuaHeroClass h = LuaHeroClass.hydrate(baseTable("h1", "MAGE"));
		assertEquals("h1", h.id());
		assertEquals("Lua Mage", h.name());
		assertEquals(HeroClass.MAGE, h.talentSource());
		assertEquals(30, h.hp());
		assertEquals(7, h.defenseSkill());
		assertEquals(2, h.startingItems().size());
		assertEquals("test_sword", h.startingItems().get(0));
	}

	@Test
	public void hydrateDefaultsDefenseSkillWhenOmitted() {
		LuaTable t = baseTable("h2", "ROGUE");
		t.set("defenseSkill", LuaValue.NIL);  // explicit removal
		LuaHeroClass h = LuaHeroClass.hydrate(t);
		assertEquals("omitted defenseSkill falls back to Hero default",
				LuaHeroClass.DEFAULT_DEFENSE_SKILL, h.defenseSkill());
	}

	@Test
	public void hydrateRejectsInvalidTalentSource() {
		LuaTable t = baseTable("bad", "NOT_A_CLASS");
		boolean threw = false;
		try { LuaHeroClass.hydrate(t); } catch (IllegalArgumentException e) { threw = true; }
		assertTrue("invalid talentSource must be rejected", threw);
	}

	@Test
	public void registryRegisterGetAllContainsSizeClear() {
		LuaHeroRegistry.clear();
		LuaHeroClass a = LuaHeroClass.hydrate(baseTable("ra", "WARRIOR"));
		LuaHeroClass b = LuaHeroClass.hydrate(baseTable("rb", "MAGE"));
		LuaHeroRegistry.register(a, baseTable("ra", "WARRIOR"));
		LuaHeroRegistry.register(b, baseTable("rb", "MAGE"));
		assertEquals(2, LuaHeroRegistry.size());
		assertTrue(LuaHeroRegistry.contains("ra"));
		assertFalse(LuaHeroRegistry.contains("ghost"));
		assertEquals("rb", LuaHeroRegistry.get("rb").id());
		assertEquals(2, LuaHeroRegistry.all().size());
		LuaHeroRegistry.clear();
		assertEquals(0, LuaHeroRegistry.size());
		assertNull(LuaHeroRegistry.get("ra"));
	}

	// ---- register_hero global (validation) ----

	@Test
	public void registerHeroAcceptsValidTable() {
		LuaHeroRegistry.clear();
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		g.load("register_hero{ id='ok', name='x', talentSource='WARRIOR', hp=25 }").call();
		assertTrue("valid table should register", LuaHeroRegistry.contains("ok"));
		assertEquals(HeroClass.WARRIOR, LuaHeroRegistry.get("ok").talentSource());
	}

	@Test
	public void registerHeroRejectsMissingRequiredFields() {
		LuaHeroRegistry.clear();
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		g.load("register_hero{ name='x', talentSource='MAGE', hp=25 }").call();           // no id
		assertFalse("missing id rejected", LuaHeroRegistry.contains(""));
		g.load("register_hero{ id='n_hp', talentSource='MAGE' }").call();                 // no hp
		assertFalse("missing hp rejected", LuaHeroRegistry.contains("n_hp"));
		g.load("register_hero{ id='n_t', name='x', hp=25 }").call();                      // no talentSource
		assertFalse("missing talentSource rejected", LuaHeroRegistry.contains("n_t"));
	}

	@Test
	public void registerHeroRejectsUnknownTalentSource() {
		LuaHeroRegistry.clear();
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		g.load("register_hero{ id='bad', name='x', talentSource='PALADIN', hp=25 }").call();
		assertFalse("unknown talentSource rejected", LuaHeroRegistry.contains("bad"));
	}

	@Test
	public void shippedTestHeroRegistersViaEngineInit() {
		LuaHeroRegistry.clear();
		LuaEngine.resetForTests();
		LuaEngine.init();
		assertTrue("scripts/heroes/test_hero.lua should register via LuaEngine.init",
				LuaHeroRegistry.contains("test_hero"));
		assertEquals(HeroClass.WARRIOR, LuaHeroRegistry.get("test_hero").talentSource());
	}

	// ---- CRITICAL (D3): Bundle round-trip with MAGE host ----

	@Test
	public void bundleRoundTripPreservesLuaHeroIdentity_MageHost() {
		// Use MAGE so the assertion can distinguish a clean round-trip from the
		// silent WARRIOR fallback (getEnum returns getEnumConstants()[0] = WARRIOR
		// on any unknown name). If the implementation wrongly stored the Lua id as
		// CLASS, getEnum would return WARRIOR, not MAGE — this test would fail.
		Hero original = new Hero();
		original.heroClass = HeroClass.MAGE;
		original.luaClassId = "mage_lua_hero";
		// storeInBundle serialises MAX_TALENT_TIERS tiers, so populate the talents
		// list the way HeroClass.initHero would for a real MAGE hero.
		Talent.initClassTalents(HeroClass.MAGE, original.talents);

		Bundle b = new Bundle();
		original.storeInBundle(b);

		// Raw-bundle assertions: the CLASS channel stores the legal host name,
		// and the lua id lives under its sidecar key (NOT under CLASS).
		assertEquals("CLASS key must store the host enum name (clean round-trip)",
				"MAGE", b.getString("class"));
		assertEquals("lua id lives under the sidecar key",
				"mage_lua_hero", b.getString("lua_class_id"));

		Hero restored = new Hero();
		restored.restoreFromBundle(b);

		assertEquals("heroClass round-trips to the host (MAGE), not the WARRIOR fallback",
				HeroClass.MAGE, restored.heroClass);
		assertEquals("luaClassId marker survives restore",
				"mage_lua_hero", restored.luaClassId);
	}

	@Test
	public void vanillaHeroBundleHasNoLuaSidecar() {
		// A vanilla hero (luaClassId == null) must not write the sidecar key, so
		// restore leaves luaClassId null — the Lua path is opt-in only.
		Hero original = new Hero();
		original.heroClass = HeroClass.ROGUE;
		Talent.initClassTalents(HeroClass.ROGUE, original.talents);
		Bundle b = new Bundle();
		original.storeInBundle(b);
		assertFalse("vanilla hero must not write the lua sidecar key",
				b.contains("lua_class_id"));

		Hero restored = new Hero();
		restored.restoreFromBundle(b);
		assertNull("vanilla restore leaves luaClassId null", restored.luaClassId);
	}

	// ---- Hero.initLuaHero: identity + stats + talent binding ----

	@Test
	public void initLuaHeroSetsIdentityStatsAndHostTalents() throws Exception {
		LuaHeroRegistry.clear();
		LuaHeroClass def = LuaHeroClass.hydrate(baseTable("init_mage", "MAGE"));
		LuaHeroRegistry.register(def, baseTable("init_mage", "MAGE"));

		Hero hero = new Hero();
		// Item collection routes through Dungeon.hero.belongings; point it at our hero.
		Dungeon.hero = hero;

		// Hero.initLuaHero first sets identity/stats/talents, THEN replicates the
		// HeroClass.initHero public item section (ClothArmor/ScrollOfIdentify/...).
		// The item section needs the full item-registry boot (Scroll.handler etc.)
		// which the headless harness does not stand up — same limitation LuaMobTest
		// documents for its AI proc paths. We let the item section throw here and
		// assert the Lua identity/stats/talents were applied first; the item
		// replication (R6) is verified by the desktop one-shot run (PLAN Step 9).
		try {
			Hero.initLuaHero(hero, "init_mage");
		} catch (Throwable t) {
			// expected: Scroll.handler is null in headless
		}

		assertEquals("heroClass = host (MAGE)", HeroClass.MAGE, hero.heroClass);
		assertEquals("luaClassId marker set", "init_mage", hero.luaClassId);
		assertEquals("HP from Lua def", 30, hero.HP);
		assertEquals("HT from Lua def", 30, hero.HT);
		assertEquals("defenseSkill from Lua def", 7, defenseSkillOf(hero));
		assertNotNull("talents list initialised", hero.talents);
		assertFalse("host talent tree populated (D2)", hero.talents.isEmpty());
		// tier 1 talents map (LinkedHashMap<Talent,Integer>) must exist for MAGE
		LinkedHashMap<Talent, Integer> tier1 = hero.talents.get(0);
		assertNotNull("tier 1 talent map exists", tier1);
		assertFalse("MAGE tier 1 has talents (e.g. EMPOWERING_MEAL)", tier1.isEmpty());
	}

	@Test
	public void initLuaHeroUnknownIdIsNoOp() {
		Hero hero = new Hero();
		HeroClass before = hero.heroClass;
		Hero.initLuaHero(hero, "no_such_hero");
		// No crash; identity unchanged.
		assertEquals(before, hero.heroClass);
		assertNull(hero.luaClassId);
	}

	// ---- LuaHeroService consume/clear semantics ----

	@Test
	public void serviceConsumeIsSingleShotAndClears() {
		LuaHeroService.clearSelectedLuaHero();
		assertNull(LuaHeroService.consumePending());

		LuaHeroService.selectLuaHero("a");
		assertEquals("peek without consume", "a", LuaHeroService.peekPending());
		assertEquals("consume returns the id", "a", LuaHeroService.consumePending());
		assertNull("consume cleared the pending id", LuaHeroService.peekPending());
		assertNull("second consume returns null (single-shot)", LuaHeroService.consumePending());
	}

	@Test
	public void serviceClearDropsPending() {
		LuaHeroService.selectLuaHero("b");
		LuaHeroService.clearSelectedLuaHero();
		assertNull(LuaHeroService.peekPending());
	}

	// ---- M1 sandbox regression (must not break) ----

	@Test
	public void luajavaBindClassStillUnreachableWithRegisterHeroInjected() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		LuaValue ok = g.load(
				"return pcall(function() return luajava.bindClass('java.lang.Runtime') end)"
		).call();
		assertFalse("luajava.bindClass must still fail with register_hero present",
				ok.toboolean());
		assertTrue("luajava global itself must remain stripped", g.get("luajava").isnil());
	}

	@Test
	public void registerHeroExposedAsGlobal() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		assertNotNull("register_hero global must be wired", g.get("register_hero"));
	}

	private static int defenseSkillOf(Hero h) throws Exception {
		Field f = Hero.class.getDeclaredField("defenseSkill");
		f.setAccessible(true);
		return f.getInt(h);
	}

	// ---- helpers ----

	private static LuaTable baseTable(String id, String talentSource) {
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf(id));
		tbl.set("name", LuaValue.valueOf("Lua Mage"));
		tbl.set("talentSource", LuaValue.valueOf(talentSource));
		tbl.set("hp", LuaValue.valueOf(30));
		tbl.set("defenseSkill", LuaValue.valueOf(7));
		// startingItems: a Lua array (1-indexed) of LuaItem ids.
		LuaTable items = new LuaTable();
		items.set(1, LuaValue.valueOf("test_sword"));
		items.set(2, LuaValue.valueOf("test_axe"));
		tbl.set("startingItems", items);
		return tbl;
	}
}
