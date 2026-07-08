package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.watabou.utils.Bundle;
import org.junit.AfterClass;
import org.junit.Before;
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
	private static int savedVersionCode;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		// Game.version is null in the bare headless harness; mirror a non-empty
		// value so static initialisers downstream of Item do not trip (LuaHeroTest
		// does the same for the Document path).
		com.watabou.noosa.Game.version = "test";
		// M5c: version gate admits test_mod (spd_version=896) so its spell script loads.
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
	public void m6dRepresentativeSpellsRegisteredByEngineInit() {
		LuaEngine.init();
		String[] ids = {"heal", "haste", "charm", "lightning_bolt", "town_portal", "summon_beast", "raise_dead", "sprout"};
		for (String id : ids) {
			assertTrue("M6d representative spell should register: " + id, LuaSpellRegistry.contains(id));
		}
		assertEquals("test_spell + 8 M6d + 22 M10a spells", 31, LuaSpellRegistry.size());
	}

	@Test
	public void m10aSpellsRegisteredByEngineInit() {
		LuaEngine.init();
		// M10a: 22 register_spell ports (21 real remished spells + remished_test_spell).
		// custom_spells_list / spells_by_affinity are data stubs and intentionally do NOT register.
		String[] ids = {
			"anesthesia", "backstab", "blood_transfusion", "body_armor", "calm",
			"cloak", "corpse_explosion", "curse_item", "dark_sacrifice", "dash",
			"die_hard", "exhumation", "hide_in_grass", "kunai_throw", "magic_arrow",
			"nature_armor", "order", "possess", "roar", "shoot_in_eye", "smash",
			"remished_test_spell"
		};
		for (String id : ids) {
			assertTrue("M10a spell should register: " + id, LuaSpellRegistry.contains(id));
		}
	}

	@Test
	public void m6dSpellMetadataHydrates() {
		LuaEngine.init();
		LuaSpell portal = LuaSpellRegistry.create("town_portal");
		assertNotNull(portal);
		assertEquals("传送术", portal.name());
		assertEquals(2f, portal.castTime(), 0.001f);
		assertEquals(3, portal.spellCost());
		// M7c: town_portal migrated from self (enterTown) to cell (teleportChar).
		assertEquals("cell", portal.targeting());
		assertTrue("cell targeting must set usesTargeting for quickslot auto-aim",
				portal.usesTargeting);
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

	// ---- M7c targeting (self/cell/enemy) ----

	/**
	 * M7c targeting hydrates the three valid values and flips {@code usesTargeting}
	 * for cell/enemy (quickslot auto-aim alignment, Wand template).
	 *
	 * <p>The full {@code execute()→GameScene.selectCell} path is NOT exercised here:
	 * it dereferences the static {@code cellSelector} which is null without a real
	 * GameScene (see class docs + PLAN risk #1). The contract that the right Lua
	 * callback fires is pinned by {@link #onUseAtCallbackFiresViaCallOpt} (cell) and
	 * {@link #onUseCallbackFiresViaCallOpt} (self); the selectCell/cancel/enemy-reject
	 * branching is verified by code review + desktop run.
	 */
	@Test
	public void targetingHydratesSelfCellEnemy() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		g.load("register_spell{ id='t_self', name='s', targeting='self' }").call();
		g.load("register_spell{ id='t_cell', name='c', targeting='cell' }").call();
		g.load("register_spell{ id='t_enemy', name='e', targeting='enemy' }").call();

		LuaSpell self = LuaSpellRegistry.create("t_self");
		LuaSpell cell = LuaSpellRegistry.create("t_cell");
		LuaSpell enemy = LuaSpellRegistry.create("t_enemy");
		assertEquals("self", self.targeting());
		assertEquals("cell", cell.targeting());
		assertEquals("enemy", enemy.targeting());

		assertFalse("self must not enable quickslot targeting", self.usesTargeting);
		assertTrue("cell must enable quickslot targeting", cell.usesTargeting);
		assertTrue("enemy must enable quickslot targeting", enemy.usesTargeting);
	}

	@Test
	public void targetingBadValueFallsBackToSelf() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		// "char" was charm.lua's pre-M7c value; any unknown value must fall back.
		g.load("register_spell{ id='t_char', name='c', targeting='char' }").call();
		g.load("register_spell{ id='t_garbage', name='g', targeting='banana' }").call();

		assertEquals("char must fall back to self",
				"self", LuaSpellRegistry.create("t_char").targeting());
		assertEquals("unknown value must fall back to self",
				"self", LuaSpellRegistry.create("t_garbage").targeting());
		assertFalse("fallback must not enable quickslot targeting",
				LuaSpellRegistry.create("t_char").usesTargeting);
	}

	@Test
	public void onUseAtCallbackFiresViaCallOpt() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		// onUseAt(heroId, cellId) is the M7c cell/enemy callback. callOpt is
		// varargs, so the 2-arg call works the same as execute's applyAtCell.
		g.set("_m7c_flag", LuaValue.FALSE);
		g.load("register_spell{ id='atcell', name='a', " +
				"onUseAt=function(heroId, cell) _m7c_flag=true; _m7c_hero=heroId; _m7c_cell=cell end }"
		).call();
		LuaTable tbl = LuaSpellRegistry.getTable("atcell");
		assertNotNull(tbl);

		LuaItemCallbacks.callOpt(tbl, "onUseAt",
				LuaValue.valueOf(77), LuaValue.valueOf(123));

		assertTrue("onUseAt must run when present", g.get("_m7c_flag").toboolean());
		assertEquals(77, g.get("_m7c_hero").toint());
		assertEquals("cellId must reach Lua as the 2nd arg", 123, g.get("_m7c_cell").toint());
	}

	@Test
	public void m11dStubSpellsHaveCostsAndNoDegradeText() {
		LuaEngine.init();
		// M11d: curse_item/order/possess are no longer zero-cost stubs.
		LuaSpell curse = LuaSpellRegistry.create("curse_item");
		LuaSpell order = LuaSpellRegistry.create("order");
		LuaSpell possess = LuaSpellRegistry.create("possess");
		assertNotNull(curse);
		assertNotNull(order);
		assertNotNull(possess);
		assertEquals("curse_item costs 5 mana", 5, curse.spellCost());
		assertEquals("order costs 8 mana", 8, order.spellCost());
		assertEquals("possess costs 10 mana", 10, possess.spellCost());
		assertEquals("mana", curse.useMode());
		assertEquals("mana", order.useMode());
		assertEquals("mana", possess.useMode());
		assertEquals("self", curse.targeting());
		assertEquals("enemy", order.targeting());
		assertEquals("enemy", possess.targeting());
		assertFalse("curse_item desc must not say 降级", curse.desc().contains("降级"));
		assertFalse("curse_item desc must not say 零消耗", curse.desc().contains("零消耗"));
		assertFalse("order desc must not say 降级", order.desc().contains("降级"));
		assertFalse("possess desc must not say 无效", possess.desc().contains("无效"));
	}

	@Test
	public void migratedSpellsHaveCorrectTargeting() {
		LuaEngine.init();
		assertEquals("cell", LuaSpellRegistry.create("lightning_bolt").targeting());
		assertEquals("cell", LuaSpellRegistry.create("town_portal").targeting());
		assertEquals("cell", LuaSpellRegistry.create("summon_beast").targeting());
		assertEquals("cell", LuaSpellRegistry.create("raise_dead").targeting());
		assertEquals("enemy", LuaSpellRegistry.create("charm").targeting());
		// untouched self spells stay self + no quickslot targeting
		assertEquals("self", LuaSpellRegistry.create("heal").targeting());
		assertEquals("self", LuaSpellRegistry.create("haste").targeting());
		assertEquals("self", LuaSpellRegistry.create("sprout").targeting());
		assertFalse(LuaSpellRegistry.create("heal").usesTargeting);
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
