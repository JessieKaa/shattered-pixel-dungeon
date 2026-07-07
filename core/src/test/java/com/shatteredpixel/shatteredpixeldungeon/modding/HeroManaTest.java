package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ManaRegen;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M7d mana dual-track tests. Pins down:
 * <ul>
 *   <li>Hero MP/MPMax defaults + Bundle round-trip (incl. pre-M7d save compat:
 *       missing keys → full pool via the level formula, NOT getInt's 0).</li>
 *   <li>{@link ManaRegen} is auto-attached on restoreFromBundle (Dungeon.loadGame
 *       doesn't call live(); codex round-1 must-fix #2).</li>
 *   <li>{@link LuaSpell#useMode()} hydration: mana / consumable / bad-value fallback.</li>
 *   <li>{@link LuaSpell#consume(Hero)}: mana mode spends MP (no detach) when enough,
 *       returns false when short; consumable mode detaches and never reads MP.</li>
 *   <li>spellCost is clamped ≥ 0 (codex round-1 must-fix #3).</li>
 *   <li>{@link LuaSpellRegistry#hasManaSpell()} drives the StatusPane visibility guard
 *       (codex round-2 must-fix #1 / C3 vanilla zero pollution).</li>
 *   <li>RPD mana API: heroMana/heroManaMax/spendMana/restoreMana incl. int-only amount
 *       rejection (codex round-1 must-fix #4).</li>
 * </ul>
 *
 * <p>Untestable headlessly (GameScene/Actor tick): applySelf/applyAtCell full chain,
 * ManaRegen.act() full chain — covered by code review + desktop run (PLAN risk #8).
 */
public class HeroManaTest {

	private static HeadlessApplication application;
	private static int savedVersionCode;
	private static String savedVersion;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		savedVersion = Game.version;
		Game.version = "test";
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
		Game.version = savedVersion;
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	private static Hero newHero() {
		Hero hero = new Hero();
		hero.heroClass = HeroClass.WARRIOR;
		// storeInBundle serialises MAX_TALENT_TIERS tiers — populate them the way
		// HeroClass.initHero would, or store throws IndexOutOfBounds (empty list).
		Talent.initClassTalents(HeroClass.WARRIOR, hero.talents);
		hero.belongings = new Belongings(hero);
		Actor.add(hero);
		return hero;
	}

	private Globals globals() {
		LuaEngine.init();
		return LuaEngine.instance().globals();
	}

	// ---- Hero MP defaults ----

	@Test
	public void heroStartsWithDefaultManaPool() {
		Hero hero = new Hero();
		assertEquals("default MPMax = 10", 10, hero.MPMax);
		assertEquals("default MP = MPMax (full)", 10, hero.MP);
	}

	@Test
	public void updateMPMaxScalesWithLevel() {
		Hero hero = new Hero();
		hero.lvl = 6;
		hero.updateMPMax();
		assertEquals("MPMax = 10 + 2*(lvl-1)", 10 + 2 * 5, hero.MPMax);
		assertEquals("MP clamps down to new cap", 10, hero.MP);
	}

	// ---- Bundle round-trip ----

	@Test
	public void mpRoundTripsThroughBundle() {
		Hero original = newHero();
		original.MPMax = 20;
		original.MP = 7;

		Bundle b = new Bundle();
		original.storeInBundle(b);

		assertTrue("MP key is written", b.contains("MP"));
		assertTrue("MPMax key is written", b.contains("MPMax"));

		Hero restored = newHero();
		restored.restoreFromBundle(b);
		assertEquals("MP round-trips", 7, restored.MP);
		assertEquals("MPMax round-trips", 20, restored.MPMax);
	}

	@Test
	public void preM7dSaveDefaultsToFullPool() {
		// A pre-M7d save has neither MP nor MPMax. restore must derive MPMax from
		// the level formula and fill MP — NOT getInt()'s 0 (codex round-1 #1).
		Hero original = newHero();
		original.lvl = 4;
		original.updateMPMax();
		Bundle b = new Bundle();
		original.storeInBundle(b);
		// Simulate an old save by stripping the keys.
		b.remove("MP");
		b.remove("MPMax");

		Hero restored = newHero();
		restored.lvl = 4;
		restored.restoreFromBundle(b);
		assertEquals("MPMax derived from level formula on missing key",
				10 + 2 * 3, restored.MPMax);
		assertEquals("MP defaults to MPMax (full) on missing key", restored.MPMax, restored.MP);
	}

	@Test
	public void manaRegenAutoAttachedOnRestore() {
		// Dungeon.loadGame restores via restoreFromBundle, not live(), so the buff
		// must be re-attached here for pre-M7d saves (codex round-1 #2).
		Hero original = newHero();
		Bundle b = new Bundle();
		original.storeInBundle(b);

		Hero restored = newHero();
		assertFalse("fresh hero before restore has no ManaRegen yet",
				restored.buffs(ManaRegen.class).iterator().hasNext());
		restored.restoreFromBundle(b);
		assertTrue("restore must attach ManaRegen (old-save migration)",
				restored.buffs(ManaRegen.class).iterator().hasNext());
	}

	// ---- LuaSpell useMode hydration ----

	@Test
	public void useModeDefaultsToConsumable() {
		LuaEngine.init();
		// test_spell.lua has no useMode → consumable (zero regression).
		LuaSpell s = LuaSpellRegistry.create("test_spell");
		assertEquals("consumable", s.useMode());
	}

	@Test
	public void useModeManaHydrates() {
		LuaEngine.init();
		// heal.lua declares useMode="mana" (M7d).
		LuaSpell heal = LuaSpellRegistry.create("heal");
		assertEquals("mana", heal.useMode());
		assertEquals(5, heal.spellCost());
	}

	@Test
	public void useModeBadValueFallsBackToConsumable() {
		globals().load("register_spell{ id='bad', name='b', useMode='stamina' }").call();
		assertEquals("unknown useMode → consumable",
				"consumable", LuaSpellRegistry.create("bad").useMode());
	}

	@Test
	public void spellCostClampedNonNegative() {
		// codex round-1 #3: a negative spellCost would invert mana spend into gain.
		globals().load("register_spell{ id='neg', name='n', useMode='mana', spellCost=-5 }").call();
		assertEquals("negative spellCost clamped to 0",
				0, LuaSpellRegistry.create("neg").spellCost());
	}

	// ---- LuaSpell.consume (dual-track) ----

	@Test
	public void consumeManaSpendsMpAndSkipsDetach() {
		globals().load("register_spell{ id='mc', name='m', useMode='mana', spellCost=4 }").call();
		LuaSpell spell = LuaSpellRegistry.create("mc");
		spell.quantity(3);

		Hero hero = newHero();
		hero.MPMax = 10;
		hero.MP = 10;
		hero.belongings.backpack.items.add(spell);

		boolean ok = spell.consume(hero);

		assertTrue("mana consume with enough MP returns true", ok);
		assertEquals("MP spent by spellCost", 6, hero.MP);
		assertEquals("mana mode does NOT detach (quantity unchanged)", 3, spell.quantity());
	}

	@Test
	public void consumeManaShortReturnsFalseWithoutSpend() {
		globals().load("register_spell{ id='ms', name='m', useMode='mana', spellCost=5 }").call();
		LuaSpell spell = LuaSpellRegistry.create("ms");
		Hero hero = newHero();
		hero.MPMax = 10;
		hero.MP = 2;

		boolean ok = spell.consume(hero);

		assertFalse("mana consume below cost returns false", ok);
		assertEquals("MP untouched when short", 2, hero.MP);
	}

	@Test
	public void consumeConsumableDetachesAndIgnoresMp() {
		// test_spell is consumable; consume() must detach and never touch MP.
		LuaEngine.init();
		LuaSpell spell = LuaSpellRegistry.create("test_spell");
		spell.quantity(3);
		Hero hero = newHero();
		hero.MPMax = 10;
		hero.MP = 10;
		hero.belongings.backpack.items.add(spell);

		boolean ok = spell.consume(hero);

		assertTrue("consumable consume returns true", ok);
		assertEquals("consumable mode detaches one (3→2)", 2, spell.quantity());
		assertEquals("consumable mode never reads MP", 10, hero.MP);
	}

	// ---- LuaSpellRegistry.hasManaSpell (StatusPane visibility guard) ----

	@Test
	public void hasManaSpellTracksRegistration() {
		// Drive the registry directly so test_mod's heal.lua (loaded by init) does
		// not contaminate the flag. resetLuaState() already cleared it in @Before.
		LuaSpellRegistry.clear();
		assertFalse("empty registry → flag false", LuaSpellRegistry.hasManaSpell());

		org.luaj.vm2.LuaTable consumable = new org.luaj.vm2.LuaTable();
		consumable.set("id", LuaValue.valueOf("c"));
		consumable.set("name", LuaValue.valueOf("c"));
		LuaSpellRegistry.register("c", consumable);
		assertFalse("consumable-only registry → flag false (StatusPane hides MP)",
				LuaSpellRegistry.hasManaSpell());

		org.luaj.vm2.LuaTable mana = new org.luaj.vm2.LuaTable();
		mana.set("id", LuaValue.valueOf("m"));
		mana.set("name", LuaValue.valueOf("m"));
		mana.set("useMode", LuaValue.valueOf("mana"));
		LuaSpellRegistry.register("m", mana);
		assertTrue("a mana-mode spell flips the flag (StatusPane shows MP)",
				LuaSpellRegistry.hasManaSpell());
	}

	@Test
	public void testModHealSetsHasManaSpell() {
		// heal.lua ships useMode="mana" → loading test_mod flips the flag (C3:
		// vanilla runs without mods never load heal.lua, so the flag stays false).
		LuaEngine.init();
		assertTrue(LuaSpellRegistry.hasManaSpell());
	}

	// ---- RPD mana API ----

	@Test
	public void rpdManaApiRoundTrip() {
		Globals g = globals();
		Hero hero = newHero();
		hero.MPMax = 20;
		hero.MP = 8;
		int id = hero.id();
		try {
			assertEquals("heroMana reads MP", 8, g.load("return RPD.heroMana(" + id + ")").call().toint());
			assertEquals("heroManaMax reads cap", 20, g.load("return RPD.heroManaMax(" + id + ")").call().toint());

			assertTrue("spendMana within pool succeeds",
					g.load("return RPD.spendMana(" + id + ", 5)").call().toboolean());
			assertEquals("spendMana deducts", 3, hero.MP);

			assertTrue("restoreMana succeeds",
					g.load("return RPD.restoreMana(" + id + ", 100)").call().toboolean());
			assertEquals("restoreMana clamps to MPMax", 20, hero.MP);
		} finally {
			Actor.remove(hero);
		}
	}

	@Test
	public void rpdSpendManaFailsWhenShort() {
		Globals g = globals();
		Hero hero = newHero();
		hero.MPMax = 10;
		hero.MP = 2;
		int id = hero.id();
		try {
			assertFalse("spendMana below pool returns false",
					g.load("return RPD.spendMana(" + id + ", 5)").call().toboolean());
			assertEquals("MP untouched on failed spend", 2, hero.MP);
		} finally {
			Actor.remove(hero);
		}
	}

	@Test
	public void rpdManaApiRejectsFractionalAmount() {
		// codex round-1 #4: validAmount(0.5) would pass and (int)0.5=0 → no-op true.
		Globals g = globals();
		Hero hero = newHero();
		hero.MPMax = 10;
		hero.MP = 10;
		int id = hero.id();
		try {
			assertFalse("fractional spendMana must fail (int-only)",
					g.load("return RPD.spendMana(" + id + ", 0.5)").call().toboolean());
			assertFalse("zero spendMana must fail",
					g.load("return RPD.spendMana(" + id + ", 0)").call().toboolean());
			assertEquals("MP untouched by rejected amounts", 10, hero.MP);
		} finally {
			Actor.remove(hero);
		}
	}

	@Test
	public void rpdManaApiNilOnNonHero() {
		Globals g = globals();
		// No hero registered at id 999999 → resolveHero null → heroMana returns nil.
		assertTrue("non-Hero target → nil",
				g.load("return RPD.heroMana(999999)").call().isnil());
		assertTrue("non-Hero spend → false",
				g.load("return RPD.spendMana(999999, 1)").call().isboolean());
		assertFalse("non-Hero spend → false value",
				g.load("return RPD.spendMana(999999, 1)").call().toboolean());
	}
}
