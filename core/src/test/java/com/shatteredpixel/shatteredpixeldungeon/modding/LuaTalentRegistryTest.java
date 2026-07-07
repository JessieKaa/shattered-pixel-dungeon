package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M8d1 (D6(b) MVP) coverage for {@link LuaTalentRegistry}, the
 * {@code register_talent} Lua global, and the new {@code MOD_EXAMPLE_TALENT}
 * enum slot. Pins down:
 * <ul>
 *   <li>Lua register_talent injects a {@code MOD_} enum into the right class+tier
 *       (id/tier/class validation, MOD_-prefix guard).</li>
 *   <li>Coexistence with M7e {@link LuaTalentOverride}: desc/maxPoints/title
 *       forwarded on the same call so {@link Talent#maxPoints()}/
 *       {@code desc()}/{@code title()} reuse the M7e fallback.</li>
 *   <li>Bundle round-trip: a saved mod-talent's points survive
 *       {@link Talent#storeTalentsInBundle}/{@link Talent#restoreTalentsFromBundle}.</li>
 *   <li>C3: a cleared registry leaves vanilla {@link Talent#initClassTalents}
 *       byte-for-byte (no mod talent injected).</li>
 * </ul>
 *
 * <p>Driven through the real {@link LuaEngine} globals (Lua→Java path exercised
 * end-to-end), with both registries wiped in {@code @Before} so each test is
 * isolated from the shipped test_mod talent scripts (iron_will/hearty_meal/
 * mod_example all load on init).
 */
public class LuaTalentRegistryTest {

	private static HeadlessApplication application;
	private static int savedVersionCode;
	private static String savedVersion;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		savedVersionCode = Game.versionCode;
		savedVersion = Game.version;
		Game.versionCode = 896;
		// Game.version must be non-null: the on_upgrade giveItem path hits
		// Item.collect → Catalog → Document.<clinit> → DeviceCompat.isDebug(),
		// which does Game.version.contains(...). Mirrors RpdApiItemSpellTest.
		Game.version = "test";
	}

	@AfterClass
	public static void shutdown() {
		Game.versionCode = savedVersionCode;
		Game.version = savedVersion;
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	@Before
	public void resetState() throws Exception {
		ModTestSupport.enableTestMod();
		ModTestSupport.resetLuaState();
	}

	/** Init the engine, then wipe both talent registries so each test starts empty. */
	private Globals cleanGlobals() {
		LuaEngine.init();
		LuaTalentRegistry.clear();
		LuaTalentOverride.clear();
		return LuaEngine.instance().globals();
	}

	private void register(Globals g, String luaTableLiteral) {
		g.load("register_talent(" + luaTableLiteral + ")").call();
	}

	private ArrayList<LinkedHashMap<Talent, Integer>> freshTalentList() {
		return new ArrayList<>();
	}

	// ---- LuaEngine loads test_mod/scripts/talents/mod_example.lua on init ----

	@Test
	public void init_loadsModExampleScript() {
		// test_mod is enabled in @Before, so LuaEngine.init() must load
		// scripts/talents/mod_example.lua → register MOD_EXAMPLE_TALENT.
		LuaEngine.init();
		assertTrue("MOD_EXAMPLE_TALENT should be a known mod talent after init",
				LuaTalentRegistry.isKnownModTalent("MOD_EXAMPLE_TALENT"));
		assertTrue("registry size should be >= 1 after init", LuaTalentRegistry.size() >= 1);

		ArrayList<LinkedHashMap<Talent, Integer>> talents = freshTalentList();
		Talent.initClassTalents(HeroClass.WARRIOR, talents);
		assertTrue("MOD_EXAMPLE_TALENT should be injected into WARRIOR tier 2",
				talents.get(1).containsKey(Talent.MOD_EXAMPLE_TALENT));
	}

	// ---- register_talent injects into the registered class+tier ----

	@Test
	public void luaRegister_injectsIntoClassTier() {
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', name='示例', maxPoints=2, desc='d' }");

		ArrayList<LinkedHashMap<Talent, Integer>> talents = freshTalentList();
		Talent.initClassTalents(HeroClass.WARRIOR, talents);

		assertEquals("injected at 0 points", Integer.valueOf(0), talents.get(1).get(Talent.MOD_EXAMPLE_TALENT));
	}

	@Test
	public void doesNotInjectIntoOtherClass() {
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', name='w', maxPoints=2 }");

		ArrayList<LinkedHashMap<Talent, Integer>> mageTalents = freshTalentList();
		Talent.initClassTalents(HeroClass.MAGE, mageTalents);
		assertFalse("WARRIOR-registered talent must not appear in MAGE",
				mageTalents.get(1).containsKey(Talent.MOD_EXAMPLE_TALENT));
	}

	@Test
	public void secondSlot_injectsIntoTier1() {
		// MOD_SECOND_TALENT covers the other pre-declared slot at a different tier.
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_SECOND_TALENT', tier=1, class='MAGE', name='s', maxPoints=2 }");

		ArrayList<LinkedHashMap<Talent, Integer>> talents = freshTalentList();
		Talent.initClassTalents(HeroClass.MAGE, talents);
		assertTrue("MOD_SECOND_TALENT in MAGE tier 1",
				talents.get(0).containsKey(Talent.MOD_SECOND_TALENT));
	}

	// ---- id validation ----

	@Test
	public void nonModPrefixId_rejected() {
		// Vanilla names must go through register_talent_override, not register_talent.
		Globals g = cleanGlobals();
		int before = LuaTalentRegistry.size();
		register(g, "{ id='HEARTY_MEAL', tier=1, class='WARRIOR', maxPoints=2 }");
		assertEquals("vanilla (non-MOD_) id must not register", before, LuaTalentRegistry.size());
	}

	@Test
	public void unknownModId_skippedWithoutThrowing() {
		Globals g = cleanGlobals();
		int before = LuaTalentRegistry.size();
		register(g, "{ id='MOD_NONEXISTENT', tier=2, class='WARRIOR', maxPoints=2 }");
		assertEquals("undeclared MOD_ enum must not register or throw", before, LuaTalentRegistry.size());
	}

	// ---- tier validation (MVP [1,2]) ----

	@Test
	public void tier0_rejected() {
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=0, class='WARRIOR', maxPoints=2 }");
		assertEquals("tier=0 must be rejected", 0, LuaTalentRegistry.size());
	}

	@Test
	public void tier3_rejectedByMvp() {
		// codex round-1 fix #3: MOD_ slots have baseMaxPoints=2, so tier 3/4 caps
		// would be silently rejected by the M7e lower-only guard. MVP limits to [1,2].
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=3, class='WARRIOR', maxPoints=2 }");
		assertEquals("tier=3 must be rejected in MVP (deferred to M8d2)",
				0, LuaTalentRegistry.size());
	}

	@Test
	public void nonIntTier_rejected() {
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier='two', class='WARRIOR', maxPoints=2 }");
		assertEquals("non-int tier must be rejected", 0, LuaTalentRegistry.size());
	}

	// ---- class validation ----

	@Test
	public void badClass_rejected() {
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='BOGUS', maxPoints=2 }");
		assertEquals("unknown class must be rejected", 0, LuaTalentRegistry.size());
	}

	@Test
	public void nonTableArgument_skippedWithoutThrowing() {
		Globals g = cleanGlobals();
		g.load("register_talent('not a table')").call();
		g.load("register_talent(42)").call();
		assertEquals("non-table args must not throw or register", 0, LuaTalentRegistry.size());
	}

	// ---- name/title validation (codex round-2 fix) ----

	@Test
	public void missingName_rejected() {
		// MOD_ enum has no .title properties key, so a player-facing name is
		// mandatory — without it the UI would render a !!!MOD_*.title!!! placeholder.
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', maxPoints=2, desc='d' }");
		assertEquals("missing name/title must be rejected", 0, LuaTalentRegistry.size());
	}

	@Test
	public void badNameType_rejected() {
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', name=123, maxPoints=2 }");
		assertEquals("non-string name (number) must be rejected", 0, LuaTalentRegistry.size());
	}

	@Test
	public void titleAlone_acceptable() {
		// `title` is an alternative to `name` (both flow into the title override).
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', title='T', maxPoints=2 }");
		assertEquals("title alone should register", 1, LuaTalentRegistry.size());
		assertEquals("T", Talent.MOD_EXAMPLE_TALENT.title());
	}

	// ---- coexistence with LuaTalentOverride (M7e) ----

	@Test
	public void coexistsWithTalentOverride_maxPointsLowered() {
		// register_talent forwards maxPoints to LuaTalentOverride (lower-only path),
		// so Talent.maxPoints() picks the mod value up via the M7e fallback.
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', maxPoints=1, name='n' }");
		assertEquals("MOD_ baseMaxPoints is the enum cap (2)",
				2, Talent.MOD_EXAMPLE_TALENT.baseMaxPoints());
		assertEquals("maxPoints() must return the lowered override (1)",
				1, Talent.MOD_EXAMPLE_TALENT.maxPoints());
		assertNotNull("override entry registered",
				LuaTalentOverride.get(Talent.MOD_EXAMPLE_TALENT));
	}

	@Test
	public void titleOverride_fromRegisterTalent() {
		// codex round-1 fix #2: register_talent's `name` field forwards to
		// LuaTalentOverride.title; MOD_ enum has no .title properties key, so
		// Talent.title() must read the override first.
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', name='Lua 新天赋' }");
		assertEquals("Lua 新天赋", Talent.MOD_EXAMPLE_TALENT.title());
	}

	@Test
	public void m7eOverrideWithoutName_keepsVanillaTitle() {
		// M7e register_talent_override never sends `name`/`title`, so a vanilla
		// talent's title() must still come from Messages (not be null/broken).
		Globals g = cleanGlobals();
		g.load("register_talent_override({ id='HEARTY_MEAL', maxPoints=1 })").call();
		assertNull("no title override when register_talent_override omits name/title",
				LuaTalentOverride.getTitle(Talent.HEARTY_MEAL));
		String title = Talent.HEARTY_MEAL.title();
		assertNotNull("vanilla title must come from Messages", title);
		assertTrue("vanilla title should not be a missing-key placeholder",
				title.length() > 0 && !title.startsWith("!!!"));
	}

	// ---- Bundle round-trip ----

	@Test
	public void bundleRoundTrip_preservesModTalent() {
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', name='示例', maxPoints=2, desc='d' }");

		Hero original = new Hero();
		original.heroClass = HeroClass.WARRIOR;
		Talent.initClassTalents(HeroClass.WARRIOR, original.talents);
		// Simulate the player spending 2 points into the mod talent.
		original.talents.get(1).put(Talent.MOD_EXAMPLE_TALENT, 2);

		Bundle b = new Bundle();
		Talent.storeTalentsInBundle(b, original);
		// 0-point talents are skipped by storeInBundle, but a spent one is kept.
		assertEquals("mod talent points must be persisted in the tier-2 bundle",
				2, b.getBundle("talents_tier_2").getInt("MOD_EXAMPLE_TALENT"));

		Hero restored = new Hero();
		restored.heroClass = HeroClass.WARRIOR;
		Talent.restoreTalentsFromBundle(b, restored);

		assertEquals("mod talent points must survive store/restore round-trip",
				Integer.valueOf(2), restored.talents.get(1).get(Talent.MOD_EXAMPLE_TALENT));
	}

	// ---- isKnownModTalent + clear (defensive skip + C3) ----

	@Test
	public void isKnownModTalent_unit() {
		Globals g = cleanGlobals();
		assertFalse("unknown before register", LuaTalentRegistry.isKnownModTalent("MOD_EXAMPLE_TALENT"));
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', name='x', maxPoints=2 }");
		assertTrue("known after register", LuaTalentRegistry.isKnownModTalent("MOD_EXAMPLE_TALENT"));
		assertFalse("null-safe", LuaTalentRegistry.isKnownModTalent(null));
		LuaTalentRegistry.clear();
		assertFalse("unknown after clear", LuaTalentRegistry.isKnownModTalent("MOD_EXAMPLE_TALENT"));
	}

	@Test
	public void clear_removesAllInjections() {
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', name='x', maxPoints=2 }");
		assertTrue(LuaTalentRegistry.size() >= 1);
		LuaTalentRegistry.clear();
		assertEquals(0, LuaTalentRegistry.size());

		// C3: with an empty registry, initClassTalents is byte-for-byte vanilla.
		ArrayList<LinkedHashMap<Talent, Integer>> talents = freshTalentList();
		Talent.initClassTalents(HeroClass.WARRIOR, talents);
		assertFalse("cleared registry must not inject any mod talent",
				talents.get(1).containsKey(Talent.MOD_EXAMPLE_TALENT));
		// Vanilla WARRIOR tier-2 talents are untouched.
		assertTrue(talents.get(1).containsKey(Talent.IRON_STOMACH));
	}

	// ---- M8d2: on_upgrade callback dispatch ----

	/**
	 * Build a live Hero (registered with Actor so hero.id() resolves through
	 * {@link Actor#findById}, which is what {@code RPD.giveItem}/
	 * {@code RPD.affectBuff} use) and park it on {@link Dungeon#hero} for the
	 * duration of the test. Mirrors {@code RpdApiItemSpellTest.newHero}.
	 */
	private static Hero newHero() {
		Hero hero = new Hero();
		hero.HT = 30;
		hero.HP = 30;
		hero.belongings = new Belongings(hero);
		Actor.add(hero);
		return hero;
	}

	@Test
	public void onUpgrade_firesOnTalentUpgradedWithPoints() {
		Globals g = cleanGlobals();
		// Callback records the int args it was called with via globals.
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', name='u', maxPoints=2, "
				+ "on_upgrade = function(hero, points) _G.cb_called = true; _G.cb_hero = hero; _G.cb_points = points end }");

		Hero hero = newHero();
		hero.heroClass = HeroClass.WARRIOR;
		Dungeon.hero = hero;
		try {
			Talent.initClassTalents(HeroClass.WARRIOR, hero.talents);
			// upgradeTalent: +1 point THEN onTalentUpgraded → Lua dispatch.
			hero.upgradeTalent(Talent.MOD_EXAMPLE_TALENT);

			assertTrue("on_upgrade must fire on upgrade", g.get("cb_called").toboolean());
			assertEquals("heroId (int) is forwarded, not a Java handle",
					hero.id(), g.get("cb_hero").toint());
			assertEquals("points is the post-upgrade count", 1, g.get("cb_points").toint());
		} finally {
			Dungeon.hero = null;
			Actor.remove(hero);
		}
	}

	@Test
	public void onUpgrade_giveItem_collectsIntoBackpack() {
		Globals g = cleanGlobals();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', name='u', maxPoints=2, "
				+ "on_upgrade = function(hero, points) RPD.giveItem(hero, 'rotten_organ', points) end }");

		Hero hero = newHero();
		hero.heroClass = HeroClass.WARRIOR;
		Dungeon.hero = hero;
		try {
			Talent.initClassTalents(HeroClass.WARRIOR, hero.talents);
			assertTrue("backpack empty before upgrade", hero.belongings.backpack.items.isEmpty());

			hero.upgradeTalent(Talent.MOD_EXAMPLE_TALENT);

			// The M6d giveItem path created + collected a LuaMaterial; find it
			// by type (id-resolved, no Java handle crossed back to Lua).
			LuaMaterial found = null;
			for (Item it : hero.belongings.backpack.items) {
				if (it instanceof LuaMaterial) { found = (LuaMaterial) it; break; }
			}
			assertNotNull("on_upgrade's giveItem must put rotten_organ in the backpack", found);
			assertEquals("quantity matches the points arg", 1, found.quantity());
		} finally {
			Dungeon.hero = null;
			Actor.remove(hero);
		}
	}

	@Test
	public void onUpgrade_affectBuff_attachesLuaBuff() {
		Globals g = cleanGlobals();
		// test_buff is a shipped Lua buff (scripts/buffs/test_buff.lua). affectBuff
		// is the M6c id-resolved path the PLAN calls "addBuff".
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', name='u', maxPoints=2, "
				+ "on_upgrade = function(hero, points) RPD.affectBuff(hero, 'test_buff', points) end }");

		Hero hero = newHero();
		hero.heroClass = HeroClass.WARRIOR;
		Dungeon.hero = hero;
		try {
			Talent.initClassTalents(HeroClass.WARRIOR, hero.talents);

			hero.upgradeTalent(Talent.MOD_EXAMPLE_TALENT);

			// Assert the SPECIFIC buff id attached (not just any LuaBuff).
			boolean found = false;
			for (Buff b : hero.buffs(LuaBuff.class)) {
				if (((LuaBuff) b).sameLuaId("test_buff")) { found = true; break; }
			}
			assertTrue("on_upgrade's affectBuff must attach the test_buff LuaBuff", found);
		} finally {
			Dungeon.hero = null;
			Actor.remove(hero);
		}
	}

	@Test
	public void modTalentWithoutOnUpgrade_dispatchIsNoop() {
		Globals g = cleanGlobals();
		g.load("_G.cb_called = false").call();
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', name='u', maxPoints=2 }");

		Hero hero = newHero();
		hero.heroClass = HeroClass.WARRIOR;
		Dungeon.hero = hero;
		try {
			Talent.initClassTalents(HeroClass.WARRIOR, hero.talents);
			// No on_upgrade registered → dispatch must bail at def.onUpgrade==null
			// without touching Lua, so the canary stays false and no exception.
			hero.upgradeTalent(Talent.MOD_EXAMPLE_TALENT);

			assertFalse("callback must not fire without on_upgrade",
					g.get("cb_called").toboolean());
			assertEquals("point still incremented (upgrade not blocked)",
					Integer.valueOf(1), hero.talents.get(1).get(Talent.MOD_EXAMPLE_TALENT));
		} finally {
			Dungeon.hero = null;
			Actor.remove(hero);
		}
	}

	@Test
	public void vanillaTalent_dispatchIsNoop() {
		Globals g = cleanGlobals();
		g.load("_G.cb_called = false").call();
		// Register an on_upgrade on the MOD_ slot only — IRON_STOMACH is vanilla
		// and must never hit Lua even though the registry is non-empty.
		register(g, "{ id='MOD_EXAMPLE_TALENT', tier=2, class='WARRIOR', name='u', maxPoints=2, "
				+ "on_upgrade = function(hero, points) _G.cb_called = true end }");

		Hero hero = newHero();
		hero.heroClass = HeroClass.WARRIOR;
		Dungeon.hero = hero;
		try {
			Talent.initClassTalents(HeroClass.WARRIOR, hero.talents);
			hero.upgradeTalent(Talent.IRON_STOMACH);

			assertFalse("vanilla talent upgrade must not fire any Lua callback",
					g.get("cb_called").toboolean());
		} finally {
			Dungeon.hero = null;
			Actor.remove(hero);
		}
	}
}
