package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.DirectableAlly;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CrabSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.RatSprite;
import com.watabou.utils.Bundle;
import com.watabou.utils.SparseArray;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.reflect.Field;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M3b Lua ally (pet) tests. Mirrors {@link LuaMobTest}'s headless harness.
 *
 * <p>What is pinned down here:
 * <ol>
 *   <li>{@link LuaAllyRegistry} register/getTable/create/ids contract.</li>
 *   <li>{@link LuaAlly} hydrate: hp/ht/attack/defense/name/spriteClass, and the
 *       M3a HP/HT correctness rule — definitional fields re-hydrate without
 *       clobbering saved runtime HP/HT.</li>
 *   <li>{@code register_ally} validation: required fields enforced.</li>
 *   <li><b>CRITICAL correctness (D5/M3a fix)</b>: when Lua {@code act} returns
 *       true, {@link LuaAlly#act} skips {@code super.act()} (the upstream pet
 *       state machine) AND still advances actor time via {@code spend(TICK)}.</li>
 *   <li>Direct command dispatch: {@link LuaAlly} inherits {@code followHero}/
 *       {@code defendPos}/{@code targetChar} from {@link DirectableAlly} and
 *       they set the {@code defendingPos}/{@code movingToDefendPos} command
 *       fields (read via reflection — protected).</li>
 *   <li>{@code RPD.commandAlly} end-to-end: resolve → dispatch → Lua
 *       {@code onCommand} callback fires with the dispatched args.</li>
 *   <li>{@code RPD.spawnAlly/commandAlly/expelAlly} wiring + bad-input rejection.</li>
 *   <li>M1 sandbox regression — Lua still cannot {@code luajava.bindClass}.</li>
 * </ol>
 *
 * <p>The live {@code GameScene.add} spawn sprite path is verified by code review
 * + the desktop run, not headlessly — it needs a {@code Dungeon.level}/
 * {@code GameScene} this harness does not stand up (PLAN risk #4, same discipline
 * as {@link LuaMobTest}).
 */
public class LuaAllyTest {

	private static HeadlessApplication application;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		LuaAllyRegistry.clear();
		LuaEngine.resetForTests();
	}

	@AfterClass
	public static void shutdown() {
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	private Globals globals() {
		Globals g = LuaSandbox.exposedGlobals();
		g.set("RPD", RpdApi.build());
		return g;
	}

	// ---- LuaAllyRegistry ----

	@Test
	public void registryRegisterGetCreate() {
		LuaAllyRegistry.clear();
		LuaTable tbl = baseTable("reg_ally");
		LuaAllyRegistry.register("reg_ally", tbl);
		assertTrue(LuaAllyRegistry.contains("reg_ally"));
		assertEquals(1, LuaAllyRegistry.size());
		assertTrue(LuaAllyRegistry.ids().contains("reg_ally"));
		assertNotNull(LuaAllyRegistry.getTable("reg_ally"));
		LuaAlly a = LuaAllyRegistry.create("reg_ally");
		assertNotNull(a);
	}

	@Test
	public void registryCreateReturnsNullForUnknown() {
		LuaAllyRegistry.clear();
		assertEquals(null, LuaAllyRegistry.create("does_not_exist"));
		assertFalse(LuaAllyRegistry.contains("ghost"));
	}

	// ---- LuaAlly hydrate ----

	@Test
	public void hydrateSetsHpHtAttackDefenseNameSprite() throws Exception {
		LuaAllyRegistry.clear();
		LuaTable tbl = baseTable("hydr");
		tbl.set("ht", LuaValue.valueOf(42));   // distinct from hp to prove ht wins
		tbl.set("sprite", LuaValue.valueOf("rat"));
		LuaAllyRegistry.register("hydr", tbl);
		LuaAlly a = LuaAllyRegistry.create("hydr");
		assertNotNull(a);
		assertEquals("ally is friendly (DirectableAlly default)",
				Char.Alignment.ALLY, a.alignment);
		assertTrue("intelligentAlly inherited from DirectableAlly", intelligentAlly(a));
		assertEquals("hp comes from the lua hp field", 20, a.HP);
		assertEquals("ht comes from the lua ht field", 42, a.HT);
		assertEquals("defense maps to defenseSkill", 4, a.defenseSkill);
		assertEquals("attack drives attackSkill", 8, a.attackSkill(null));
		assertEquals("name comes from lua", "测试 Lua 宠物", a.name());
		assertEquals("sprite=rat resolves to RatSprite", RatSprite.class, a.spriteClass);
	}

	@Test
	public void htDefaultsToHpWhenOmitted() {
		LuaAllyRegistry.clear();
		LuaTable tbl = baseTable("htdef");
		LuaAllyRegistry.register("htdef", tbl);
		LuaAlly a = LuaAllyRegistry.create("htdef");
		assertEquals("ht should default to hp", 20, a.HT);
	}

	@Test
	public void unknownSpriteFallsBackToCrab() {
		LuaAllyRegistry.clear();
		LuaTable tbl = baseTable("spr");
		tbl.set("sprite", LuaValue.valueOf("nonexistent_sprite"));
		LuaAllyRegistry.register("spr", tbl);
		LuaAlly a = LuaAllyRegistry.create("spr");
		assertEquals("unknown sprite degrades to CrabSprite, not a crash",
				CrabSprite.class, a.spriteClass);
	}

	@Test
	public void damageRollStaysPositiveAroundAttack() {
		LuaAllyRegistry.clear();
		LuaTable tbl = baseTable("dmg");
		tbl.set("attack", LuaValue.valueOf(3));
		LuaAllyRegistry.register("dmg", tbl);
		LuaAlly a = LuaAllyRegistry.create("dmg");
		for (int i = 0; i < 50; i++) {
			int d = a.damageRoll();
			assertTrue("damageRoll must stay positive (attack-2..attack+2, floored)",
					d >= 1 && d <= 5);
		}
	}

	// ---- Persistence: Bundle round-trip must preserve wounded HP ----

	@Test
	public void bundleRoundTripPreservesWoundedHpAndRehydratesDefinition() {
		LuaAllyRegistry.clear();
		LuaTable tbl = baseTable("rt");
		tbl.set("ht", LuaValue.valueOf(30));   // distinct from hp (20)
		LuaAllyRegistry.register("rt", tbl);
		LuaAlly original = LuaAllyRegistry.create("rt");
		assertEquals("fresh ally starts at lua hp clamped to HT", 20, original.HP);
		assertEquals("HT from lua ht field", 30, original.HT);
		original.HP = 7;   // wound it — must survive save/load

		Bundle b = new Bundle();
		original.storeInBundle(b);
		LuaAlly restored = new LuaAlly();
		restored.restoreFromBundle(b);

		assertEquals("wounded HP must round-trip, not reset to full (M3a must-fix)",
				7, restored.HP);
		assertEquals("HT must round-trip", 30, restored.HT);
		assertEquals("lua_ally_id enables definition re-hydration",
				"测试 Lua 宠物", restored.name());
		assertEquals("definitional attack re-hydrated", 8, restored.attackSkill(null));
		assertEquals("definitional defense re-hydrated", 4, restored.defenseSkill);
	}

	// ---- CRITICAL: act() takeover path skips super AND spends ----

	@Test
	public void actLuaTakeoverSkipsUpstreamAiAndSpendsTick() throws Exception {
		LuaAllyRegistry.clear();
		LuaTable tbl = baseTable("takeover");
		// Lua act(selfId) returns true → Java must skip super.act() and spend(TICK).
		tbl.set("act", new OneArgFunction() {
			@Override public LuaValue call(LuaValue selfId) { return LuaValue.TRUE; }
		});
		LuaAllyRegistry.register("takeover", tbl);
		LuaAlly a = LuaAllyRegistry.create("takeover");
		assertNotNull(a);

		assertEquals("actor time starts at 0", 0f, actorTime(a), 0.0001f);
		// On the takeover path act() returns true without ever touching Dungeon
		// (super.act(), which needs Dungeon.level, is skipped). If super were
		// called this would NPE in the headless harness.
		boolean result = a.act();
		assertTrue("takeover path returns true", result);
		assertEquals("actor time advanced by exactly TICK (1f) — the spend fallback",
				Actor.TICK, actorTime(a), 0.0001f);
	}

	@Test
	public void actLuaErrorFallsThroughToUpstreamWithoutFreezing() throws Exception {
		LuaAllyRegistry.clear();
		LuaTable tbl = baseTable("boom");
		tbl.set("act", new OneArgFunction() {
			@Override public LuaValue call(LuaValue selfId) {
				throw new RuntimeException("simulated lua-side error");
			}
		});
		LuaAllyRegistry.register("boom", tbl);
		LuaAlly a = LuaAllyRegistry.create("boom");
		// A throwing Lua act must fall through to super.act(). In the headless
		// harness super.act() NPEs on Dungeon.level — that proves we reached
		// the fallback (did not silently return true + skip).
		boolean threw = false;
		try {
			a.act();
		} catch (NullPointerException npe) {
			threw = true;  // reached super.act() → Mob.act() → Char.act() → Dungeon.level
		}
		assertTrue("Lua error must fall through to upstream super.act()", threw);
	}

	// ---- Direct command dispatch (inherited from DirectableAlly) ----

	@Test
	public void followHeroClearsCommandFields() throws Exception {
		LuaAllyRegistry.clear();
		LuaAllyRegistry.register("cmd_follow", baseTable("cmd_follow"));
		LuaAlly a = LuaAllyRegistry.create("cmd_follow");
		assertNotNull(a);
		// seed a prior defend command so we can see follow clear it
		a.defendPos(42);
		assertEquals(42, defendingPos(a));
		assertTrue(movingToDefendPos(a));
		a.followHero();
		assertEquals("followHero clears defendingPos", -1, defendingPos(a));
		assertFalse("followHero clears movingToDefendPos", movingToDefendPos(a));
	}

	@Test
	public void defendPosSetsCommandFields() throws Exception {
		LuaAllyRegistry.clear();
		LuaAllyRegistry.register("cmd_defend", baseTable("cmd_defend"));
		LuaAlly a = LuaAllyRegistry.create("cmd_defend");
		assertNotNull(a);
		a.defendPos(99);
		assertEquals("defendPos sets defendingPos", 99, defendingPos(a));
		assertTrue("defendPos sets movingToDefendPos", movingToDefendPos(a));
	}

	// ---- commandAlly end-to-end: resolve → dispatch → onCommand callback ----

	@Test
	public void commandAllyDispatchesFollowAndFiresOnCommand() throws Exception {
		assertCommandAllyDispatch("follow", 0);
	}

	@Test
	public void commandAllyDispatchesDefendAndFiresOnCommand() throws Exception {
		// defend's targetId is a cell int, not a char id — no Char resolution.
		assertCommandAllyDispatch("defend", 123);
	}

	private void assertCommandAllyDispatch(String cmd, int targetId) throws Exception {
		LuaAllyRegistry.clear();
		Globals g = globals();
		LuaTable tbl = baseTable("cma_" + cmd);
		// onCommand writes its args into the Lua global _oncmd so we can read
		// them back from Java and prove the callback fired with dispatched args.
		tbl.set("onCommand", g.load(
				"return function(selfId, c, t) _oncmd = (selfId or -1)..':'..(c or '?')..':'..(t or -1) end"
		).call());
		LuaAllyRegistry.register("cma_" + cmd, tbl);

		LuaAlly a = LuaAllyRegistry.create("cma_" + cmd);
		registerInActor(a);
		try {
			int id = a.id();
			LuaValue res = g.load(
					"return RPD.commandAlly(" + id + ", '" + cmd + "', " + targetId + ")"
			).call();
			assertTrue("commandAlly returns nil on success", res.isnil());
			assertEquals("onCommand fired with the dispatched args",
					id + ":" + cmd + ":" + targetId,
					g.get("_oncmd").optjstring("MISSING"));
		} finally {
			unregisterFromActor(a);
		}
	}

	@Test
	public void commandAllyRejectsBadInput() {
		Globals g = globals();
		assertTrue("non-int charId → nil",
				g.load("return RPD.commandAlly('x', 'follow', 0)").call().isnil());
		assertTrue("unknown command → nil",
				g.load("return RPD.commandAlly(999, 'fly', 0)").call().isnil());
	}

	// ---- register_ally global (validation) ----

	@Test
	public void registerAllyAcceptsValidTable() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		g.load("register_ally{ id='ok_ally', name='x', hp=15, attack=5, defense=3 }").call();
		assertTrue("valid table should register", LuaAllyRegistry.contains("ok_ally"));
	}

	@Test
	public void registerAllyRejectsMissingRequiredFields() {
		LuaAllyRegistry.clear();
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		// missing hp
		g.load("register_ally{ id='no_hp', name='x', attack=5, defense=3 }").call();
		assertFalse("missing hp must be rejected", LuaAllyRegistry.contains("no_hp"));
		// missing attack
		g.load("register_ally{ id='no_atk', name='x', hp=10, defense=3 }").call();
		assertFalse("missing attack must be rejected", LuaAllyRegistry.contains("no_atk"));
		// missing defense
		g.load("register_ally{ id='no_def', name='x', hp=10, attack=5 }").call();
		assertFalse("missing defense must be rejected", LuaAllyRegistry.contains("no_def"));
	}

	@Test
	public void shippedTestAllyRegistersViaEngineInit() {
		// Earlier tests in this suite clear the registry; LuaEngine only scans
		// once per init, so force a fresh scan here before asserting.
		LuaAllyRegistry.clear();
		LuaEngine.resetForTests();
		LuaEngine.init();
		assertTrue("scripts/allies/test_ally.lua should register via LuaEngine.init",
				LuaAllyRegistry.contains("test_ally"));
	}

	// ---- RPD.spawnAlly / commandAlly / expelAlly wiring ----

	@Test
	public void allyApisExposedOnRpdGlobal() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		assertFalse("RPD global must be present", g.get("RPD").isnil());
		assertNotNull("RPD.spawnAlly must be wired", g.get("RPD").get("spawnAlly"));
		assertNotNull("RPD.commandAlly must be wired", g.get("RPD").get("commandAlly"));
		assertNotNull("RPD.expelAlly must be wired", g.get("RPD").get("expelAlly"));
	}

	@Test
	public void spawnAllyOnUnknownIdReturnsNilWithoutThrowing() {
		Globals g = globals();
		LuaValue r = g.load("return RPD.spawnAlly('no_such_ally', 0)").call();
		assertTrue("unknown ally id → nil, no throw", r.isnil());
	}

	@Test
	public void spawnAllyRejectsBadArgumentTypes() {
		Globals g = globals();
		assertTrue("non-string allyId → nil", g.load("return RPD.spawnAlly(123, 0)").call().isnil());
		assertTrue("non-int pos → nil", g.load("return RPD.spawnAlly('x', 'y')").call().isnil());
	}

	@Test
	public void expelAllyRejectsBadInput() {
		Globals g = globals();
		assertTrue("non-int charId → nil", g.load("return RPD.expelAlly('x')").call().isnil());
		assertTrue("non-LuaAlly charId → nil (999 not registered)",
				g.load("return RPD.expelAlly(999)").call().isnil());
	}

	// ---- M1 sandbox regression (must not break) ----

	@Test
	public void luajavaBindClassStillUnreachableWithAllyApisInjected() {
		Globals g = globals();
		LuaValue ok = g.load(
				"return pcall(function() return luajava.bindClass('java.lang.Runtime') end)"
		).call();
		assertFalse("luajava.bindClass must still fail with ally APIs present",
				ok.toboolean());
		assertTrue("luajava global itself must remain stripped", g.get("luajava").isnil());
	}

	// ---- helpers ----

	private static LuaTable baseTable(String id) {
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf(id));
		tbl.set("name", LuaValue.valueOf("测试 Lua 宠物"));
		tbl.set("hp", LuaValue.valueOf(20));
		tbl.set("attack", LuaValue.valueOf(8));
		tbl.set("defense", LuaValue.valueOf(4));
		return tbl;
	}

	private static float actorTime(Mob m) throws Exception {
		Field f = Actor.class.getDeclaredField("time");
		f.setAccessible(true);
		return f.getFloat(m);
	}

	private static int defendingPos(LuaAlly a) throws Exception {
		Field f = DirectableAlly.class.getDeclaredField("defendingPos");
		f.setAccessible(true);
		return f.getInt(a);
	}

	private static boolean movingToDefendPos(LuaAlly a) throws Exception {
		Field f = DirectableAlly.class.getDeclaredField("movingToDefendPos");
		f.setAccessible(true);
		return f.getBoolean(a);
	}

	private static boolean intelligentAlly(Mob m) throws Exception {
		Field f = Mob.class.getDeclaredField("intelligentAlly");
		f.setAccessible(true);
		return f.getBoolean(m);
	}

	/**
	 * Stands in for {@code GameScene.add} → {@code Actor.addDelayed} by registering
	 * the ally directly into {@link Actor}'s id/chars/all maps, so
	 * {@link Actor#findById} resolves it for {@code commandAlly}. Cleaned up in
	 * finally via {@link #unregisterFromActor}.
	 */
	@SuppressWarnings("unchecked")
	private static void registerInActor(LuaAlly a) throws Exception {
		int id = a.id();
		Field idsF = Actor.class.getDeclaredField("ids");
		idsF.setAccessible(true);
		((SparseArray<Actor>) idsF.get(null)).put(id, a);
		Field charsF = Actor.class.getDeclaredField("chars");
		charsF.setAccessible(true);
		((HashSet<Char>) charsF.get(null)).add(a);
		Field allF = Actor.class.getDeclaredField("all");
		allF.setAccessible(true);
		((HashSet<Actor>) allF.get(null)).add(a);
	}

	@SuppressWarnings("unchecked")
	private static void unregisterFromActor(LuaAlly a) throws Exception {
		int id = a.id();
		Field idsF = Actor.class.getDeclaredField("ids");
		idsF.setAccessible(true);
		((SparseArray<Actor>) idsF.get(null)).remove(id);
		Field charsF = Actor.class.getDeclaredField("chars");
		charsF.setAccessible(true);
		((HashSet<Char>) charsF.get(null)).remove(a);
		Field allF = Actor.class.getDeclaredField("all");
		allF.setAccessible(true);
		((HashSet<Actor>) allF.get(null)).remove(a);
	}
}
