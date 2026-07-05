package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.watabou.utils.Bundle;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M3d LuaSpell tests. Mirrors {@link LuaItemCallbackTest}'s headless harness.
 *
 * <p>What is pinned down here:
 * <ol>
 *   <li>{@link LuaSpellRegistry} register/getTable/create/ids contract.</li>
 *   <li>{@link LuaSpell} hydrate: name/desc/image from the Lua table, and the
 *       consumable conventions (stackable / defaultAction=AC_USE /
 *       isUpgradable=false / isIdentified=true / actions contains AC_USE).</li>
 *   <li>{@code register_spell} validation: id+name required, image/onUse
 *       optional.</li>
 *   <li><b>D3 detach consume (CRITICAL)</b>: {@code detach(bag)} decrements
 *       quantity (3→2) without removing the stack, and removes it when
 *       quantity==1 — never hand-writing {@code quantity--}. Uses a bare
 *       {@link Bag} whose {@code owner} is null so {@code grabItems} is a
 *       no-op and the test needs no live Hero/level.</li>
 *   <li><b>D2 onUse callback</b>: {@code callOpt(tbl,"onUse",heroId)} fires the
 *       Lua function — the exact call {@code execute} makes.</li>
 *   <li><b>D6 isSimilar</b>: same-id spells merge, different-id spells do not
 *       (default {@code Item.isSimilar} would merge any two LuaSpells).</li>
 *   <li><b>D5 Bundle round-trip</b>: quantity + lua_spell_id survive a
 *       store/restore cycle and name re-hydrates from the registry.</li>
 *   <li>M1 sandbox regression — Lua still cannot {@code luajava.bindClass}.</li>
 * </ol>
 *
 * <p>The integrated {@code execute(hero, AC_USE)} path calls
 * {@code GameScene.cancel()} which dereferences a static {@code cellSelector}
 * that is null without a real GameScene — so the full path is verified by code
 * review + desktop run, not headlessly (PLAN risk #2, M2 precedent).
 */
public class LuaSpellTest {

	private static HeadlessApplication application;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		// Game.version is null in the bare headless harness; mirror a non-empty
		// value so static initialisers downstream of Item do not trip (LuaHeroTest
		// does the same for the Document path).
		com.watabou.noosa.Game.version = "test";
		LuaSpellRegistry.clear();
		LuaEngine.resetForTests();
	}

	@AfterClass
	public static void shutdown() {
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	// ---- registry contract ----

	@Test
	public void testSpellRegisteredByEngineInit() {
		LuaEngine.init();
		assertTrue("test_spell.lua should register on init",
				LuaSpellRegistry.contains("test_spell"));
		assertNotNull(LuaSpellRegistry.getTable("test_spell"));
		assertTrue(LuaSpellRegistry.ids().contains("test_spell"));
		assertTrue(LuaSpellRegistry.size() >= 1);
	}

	@Test
	public void createReturnsLuaSpellWithHydratedFields() {
		LuaEngine.init();
		LuaSpell spell = LuaSpellRegistry.create("test_spell");
		assertNotNull(spell);
		assertEquals("测试法术 (Lua)", spell.name());
		assertEquals("M3d 消耗性法术:使用即消耗,onUse 回调验证 charId 范式 + RPD API。", spell.desc());
		assertEquals(0, spell.image);
	}

	@Test
	public void createUnknownIdReturnsNull() {
		LuaEngine.init();
		assertFalse(LuaSpellRegistry.contains("does_not_exist"));
		assertEquals(null, LuaSpellRegistry.create("does_not_exist"));
	}

	// ---- register_spell validation ----

	@Test
	public void registerSpellRejectsMissingName() {
		LuaEngine.init();
		int before = LuaSpellRegistry.size();
		LuaEngine.instance().globals().load("register_spell{ id='noname' }").call();
		assertFalse(LuaSpellRegistry.contains("noname"));
		assertEquals("a malformed definition must not change registry size", before, LuaSpellRegistry.size());
	}

	@Test
	public void registerSpellRejectsMissingId() {
		LuaEngine.init();
		int before = LuaSpellRegistry.size();
		LuaEngine.instance().globals().load("register_spell{ name='noid' }").call();
		assertFalse(LuaSpellRegistry.contains("noid"));
		assertEquals(before, LuaSpellRegistry.size());
	}

	@Test
	public void registerSpellAcceptsOptionalImageAndOnUse() {
		LuaEngine.init();
		LuaEngine.instance().globals().load(
				"register_spell{ id='minimal', name='min' }").call();
		assertTrue(LuaSpellRegistry.contains("minimal"));
		LuaSpell s = LuaSpellRegistry.create("minimal");
		assertNotNull(s);
		assertEquals("missing image must default to 0", 0, s.image);
	}

	// ---- consumable conventions ----

	@Test
	public void consumableConventionsHold() {
		LuaEngine.init();
		LuaSpell spell = LuaSpellRegistry.create("test_spell");
		assertTrue("spells must be stackable", spell.stackable);
		assertEquals(LuaSpell.AC_USE, spell.defaultAction());
		assertFalse("spells are not upgradable", spell.isUpgradable());
		assertTrue("spells spawn pre-identified", spell.isIdentified());
		// actions(hero) is independent of hero in Item/food; pass null safely.
		assertTrue(spell.actions(null).contains(LuaSpell.AC_USE));
	}

	// ---- D3 detach consume (CRITICAL) ----

	@Test
	public void detachDecrementsQuantityWhenGreaterThanOne() {
		LuaEngine.init();
		LuaSpell spell = LuaSpellRegistry.create("test_spell");
		spell.quantity(3);
		Bag bag = new Bag();
		bag.items.add(spell);

		LuaSpell detached = (LuaSpell) spell.detach(bag);

		assertEquals("original stack in bag is decremented", 2, spell.quantity());
		assertTrue("original stack stays in the bag", bag.items.contains(spell));
		assertNotNull("detach returns the consumed split-off", detached);
		assertEquals(1, detached.quantity());
	}

	@Test
	public void detachRemovesStackWhenQuantityIsOne() {
		LuaEngine.init();
		LuaSpell spell = LuaSpellRegistry.create("test_spell");
		spell.quantity(1);
		Bag bag = new Bag();
		bag.items.add(spell);

		spell.detach(bag);

		assertFalse("quantity==1 detach removes the item from the bag",
				bag.items.contains(spell));
	}

	// ---- D2 onUse callback ----

	@Test
	public void onUseCallbackFiresViaCallOpt() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		// Register an inline spell whose onUse flips a global flag. The closure
		// captures g, so the flag is readable back from g after the call — this
		// is the same callOpt path LuaSpell.execute uses.
		g.set("_m3d_flag", LuaValue.FALSE);
		g.load("register_spell{ id='cb', name='cb', " +
				"onUse=function(heroId) _m3d_flag = true; _m3d_hero = heroId end }").call();
		LuaTable tbl = LuaSpellRegistry.getTable("cb");
		assertNotNull(tbl);

		LuaItemCallbacks.callOpt(tbl, "onUse", LuaValue.valueOf(42));

		assertTrue("onUse must run when present", g.get("_m3d_flag").toboolean());
		assertEquals(42, g.get("_m3d_hero").toint());
	}

	@Test
	public void onUseMissingIsNoOp() {
		LuaEngine.init();
		// 'minimal' has no onUse field; callOpt must swallow that gracefully.
		LuaTable tbl = LuaSpellRegistry.getTable("test_spell");
		// Even with a real table, calling a missing fn name is a no-op.
		LuaItemCallbacks.callOpt(tbl, "noSuchCallback", LuaValue.valueOf(1));
	}

	// ---- D6 isSimilar (prevent cross-id merge corruption) ----

	@Test
	public void isSimilarTrueForSameId() {
		LuaEngine.init();
		LuaSpell a = LuaSpellRegistry.create("test_spell");
		LuaSpell b = LuaSpellRegistry.create("test_spell");
		assertTrue(a.isSimilar(b));
	}

	@Test
	public void isSimilarFalseForDifferentId() {
		LuaEngine.init();
		LuaSpell a = LuaSpellRegistry.create("test_spell");
		LuaEngine.instance().globals().load(
				"register_spell{ id='other_spell', name='other' }").call();
		LuaSpell b = LuaSpellRegistry.create("other_spell");
		assertNotNull(b);
		assertFalse("different lua_spell_id must NOT merge — default Item.isSimilar would corrupt both",
				a.isSimilar(b));
	}

	// ---- D5 Bundle round-trip ----

	@Test
	public void bundleRoundTripPreservesQuantityAndId() {
		LuaEngine.init();
		LuaSpell spell = LuaSpellRegistry.create("test_spell");
		spell.quantity(3);

		Bundle b = new Bundle();
		spell.storeInBundle(b);

		LuaSpell restored = new LuaSpell();
		restored.restoreFromBundle(b);

		assertEquals("quantity must round-trip via super.storeInBundle", 3, restored.quantity());
		assertEquals("lua_spell_id must round-trip and re-hydrate name",
				"测试法术 (Lua)", restored.name());
		assertEquals("M3d 消耗性法术:使用即消耗,onUse 回调验证 charId 范式 + RPD API。", restored.desc());
	}

	@Test
	public void bundleRoundTripOfSplitCopyRehydrates() {
		// split(1) (used by detach when quantity>1) builds a copy via
		// Reflection.newInstance + bundle round-trip; verify the copy re-hydrates.
		LuaEngine.init();
		LuaSpell spell = LuaSpellRegistry.create("test_spell");
		spell.quantity(2);
		Bag bag = new Bag();
		bag.items.add(spell);

		LuaSpell detached = (LuaSpell) spell.detach(bag);

		assertNotNull(detached);
		assertEquals(1, detached.quantity());
		assertEquals("split copy re-hydrates from the registry",
				"测试法术 (Lua)", detached.name());
	}

	// ---- M1 sandbox regression ----

	@Test
	public void luajavaBindClassStillUnreachable() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		LuaValue ok = g.load(
				"return pcall(function() return luajava.bindClass('java.lang.Runtime') end)"
		).call();
		assertFalse("luajava.bindClass must still fail after M3d additions", ok.toboolean());
		assertTrue("luajava global itself must remain stripped", g.get("luajava").isnil());
	}
}
