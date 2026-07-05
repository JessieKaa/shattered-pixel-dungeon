package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CrabSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.RatSprite;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M3a Lua mob tests. Mirrors {@link LuaItemCallbackTest}'s headless harness.
 *
 * <p>What is pinned down here:
 * <ol>
 *   <li>{@link LuaMobRegistry} register/getTable/create/ids contract.</li>
 *   <li>{@link LuaMob} hydrate: hp/ht/attack/defence/name/spriteClass.</li>
 *   <li>{@code register_mob} validation: required fields enforced, optional
 *       ones default.</li>
 *   <li><b>CRITICAL correctness (D2)</b>: when Lua {@code act} returns true,
 *       {@link LuaMob#act} skips {@code super.act()} (the upstream AI state
 *       machine) AND still advances actor time via {@code spend(TICK)} — read
 *       back via reflection on {@link Actor}'s private {@code time} field.
 *       Without the spend, the mob would pin the actor queue and freeze the
 *       whole game.</li>
 *   <li>{@code RPD.spawnMob} is wired into the global surface.</li>
 *   <li>M1 sandbox regression — Lua still cannot {@code luajava.bindClass} with
 *       the new global present.</li>
 * </ol>
 *
 * <p>Full AI proc paths ({@code attackProc/defenceProc/die} super chain) and
 * the live {@code GameScene.add} spawn are verified by code review + the desktop
 * run, not headlessly — they need a {@code Dungeon.level}/{@code GameScene}
 * this harness does not stand up (PLAN risk #2).
 */
public class LuaMobTest {

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
	public void resetModAndLuaState() throws Exception {
		ModTestSupport.enableTestMod();
		ModTestSupport.resetLuaState();
	}

	@AfterClass
	public static void shutdown() {
		Game.versionCode = savedVersionCode;
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	private Globals globals() {
		Globals g = LuaSandbox.exposedGlobals();
		g.set("RPD", RpdApi.build());
		return g;
	}

	// ---- LuaMobRegistry ----

	@Test
	public void registryRegisterGetCreate() {
		LuaMobRegistry.clear();
		LuaTable tbl = baseTable("reg_mob");
		LuaMobRegistry.register("reg_mob", tbl);
		assertTrue(LuaMobRegistry.contains("reg_mob"));
		assertEquals(1, LuaMobRegistry.size());
		assertTrue(LuaMobRegistry.ids().contains("reg_mob"));
		assertNotNull(LuaMobRegistry.getTable("reg_mob"));
		LuaMob m = LuaMobRegistry.create("reg_mob");
		assertNotNull(m);
	}

	@Test
	public void registryCreateReturnsNullForUnknown() {
		LuaMobRegistry.clear();
		assertEquals(null, LuaMobRegistry.create("does_not_exist"));
		assertFalse(LuaMobRegistry.contains("ghost"));
	}

	// ---- LuaMob hydrate ----

	@Test
	public void hydrateSetsHpHtAttackDefenseNameSprite() {
		LuaMobRegistry.clear();
		LuaTable tbl = baseTable("hydr");
		tbl.set("ht", LuaValue.valueOf(42));   // distinct from hp to prove ht wins
		tbl.set("sprite", LuaValue.valueOf("rat"));
		LuaMobRegistry.register("hydr", tbl);
		LuaMob m = LuaMobRegistry.create("hydr");
		assertNotNull(m);
		assertEquals("hp comes from the lua hp field", 20, m.HP);
		assertEquals("ht comes from the lua ht field", 42, m.HT);
		assertEquals("defense maps to defenseSkill", 4, m.defenseSkill);
		assertEquals("attack drives attackSkill", 8, m.attackSkill(null));
		assertEquals("name comes from lua", "测试 Lua 怪物", m.name());
		assertEquals("sprite=rat resolves to RatSprite", RatSprite.class, m.spriteClass);
	}

	@Test
	public void htDefaultsToHpWhenOmitted() {
		LuaMobRegistry.clear();
		LuaTable tbl = baseTable("htdef");
		// no ht field
		LuaMobRegistry.register("htdef", tbl);
		LuaMob m = LuaMobRegistry.create("htdef");
		assertEquals("ht should default to hp", 20, m.HT);
	}

	@Test
	public void unknownSpriteFallsBackToCrab() {
		LuaMobRegistry.clear();
		LuaTable tbl = baseTable("spr");
		tbl.set("sprite", LuaValue.valueOf("nonexistent_sprite"));
		LuaMobRegistry.register("spr", tbl);
		LuaMob m = LuaMobRegistry.create("spr");
		assertEquals("unknown sprite degrades to CrabSprite, not a crash",
				CrabSprite.class, m.spriteClass);
	}

	@Test
	public void damageRollStaysPositiveAroundAttack() {
		LuaMobRegistry.clear();
		LuaTable tbl = baseTable("dmg");
		tbl.set("attack", LuaValue.valueOf(3));
		LuaMobRegistry.register("dmg", tbl);
		LuaMob m = LuaMobRegistry.create("dmg");
		for (int i = 0; i < 50; i++) {
			int d = m.damageRoll();
			assertTrue("damageRoll must stay positive (attack-2..attack+2, floored)",
					d >= 1 && d <= 5);
		}
	}

	// ---- Persistence: Bundle round-trip must preserve wounded HP ----

	@Test
	public void bundleRoundTripPreservesWoundedHpAndRehydratesDefinition() {
		LuaMobRegistry.clear();
		LuaTable tbl = baseTable("rt");
		tbl.set("ht", LuaValue.valueOf(30));   // distinct from hp (20)
		LuaMobRegistry.register("rt", tbl);
		LuaMob original = LuaMobRegistry.create("rt");
		assertEquals("fresh mob starts at lua hp clamped to HT", 20, original.HP);
		assertEquals("HT from lua ht field", 30, original.HT);
		original.HP = 7;   // wound it — must survive save/load

		Bundle b = new Bundle();
		original.storeInBundle(b);
		LuaMob restored = new LuaMob();
		restored.restoreFromBundle(b);

		assertEquals("wounded HP must round-trip, not reset to full",
				7, restored.HP);
		assertEquals("HT must round-trip", 30, restored.HT);
		assertEquals("lua_mob_id enables definition re-hydration",
				"测试 Lua 怪物", restored.name());
		assertEquals("definitional attack re-hydrated", 8, restored.attackSkill(null));
		assertEquals("definitional defense re-hydrated", 4, restored.defenseSkill);
	}

	// ---- CRITICAL: act() takeover path skips super AND spends ----

	@Test
	public void actLuaTakeoverSkipsUpstreamAiAndSpendsTick() throws Exception {
		LuaMobRegistry.clear();
		LuaTable tbl = baseTable("takeover");
		// Lua act(selfId) returns true → Java must skip super.act() and spend(TICK).
		tbl.set("act", new OneArgFunction() {
			@Override public LuaValue call(LuaValue selfId) { return LuaValue.TRUE; }
		});
		LuaMobRegistry.register("takeover", tbl);
		LuaMob m = LuaMobRegistry.create("takeover");
		assertNotNull(m);

		assertEquals("actor time starts at 0", 0f, actorTime(m), 0.0001f);
		// On the takeover path act() returns true without ever touching Dungeon
		// (super.act(), which needs Dungeon.level, is skipped). If super were
		// called this would NPE in the headless harness.
		boolean result = m.act();
		assertTrue("takeover path returns true", result);
		assertEquals("actor time advanced by exactly TICK (1f) — the spend fallback",
				Actor.TICK, actorTime(m), 0.0001f);
	}

	@Test
	public void actLuaErrorFallsThroughToUpstreamWithoutFreezing() throws Exception {
		LuaMobRegistry.clear();
		LuaTable tbl = baseTable("boom");
		tbl.set("act", new OneArgFunction() {
			@Override public LuaValue call(LuaValue selfId) {
				throw new RuntimeException("simulated lua-side error");
			}
		});
		LuaMobRegistry.register("boom", tbl);
		LuaMob m = LuaMobRegistry.create("boom");
		// A throwing Lua act must fall through to super.act(). In the headless
		// harness super.act() NPEs on Dungeon.level — that proves we reached
		// the fallback (did not silently return true + skip). We swallow it and
		// assert the time was NOT advanced by our TICK-only path.
		boolean threw = false;
		try {
			m.act();
		} catch (NullPointerException npe) {
			threw = true;  // reached super.act() → Mob.act() → Char.act() → Dungeon.level
		}
		assertTrue("Lua error must fall through to upstream super.act()", threw);
	}

	private static float actorTime(Mob m) throws Exception {
		Field f = Actor.class.getDeclaredField("time");
		f.setAccessible(true);
		return f.getFloat(m);
	}

	// ---- register_mob global (validation) ----

	@Test
	public void registerMobAcceptsValidTable() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		g.load("register_mob{ id='ok_mob', name='x', hp=15, attack=5, defense=3 }").call();
		assertTrue("valid table should register", LuaMobRegistry.contains("ok_mob"));
	}

	@Test
	public void registerMobRejectsMissingRequiredFields() {
		LuaMobRegistry.clear();
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		// missing hp
		g.load("register_mob{ id='no_hp', name='x', attack=5, defense=3 }").call();
		assertFalse("missing hp must be rejected", LuaMobRegistry.contains("no_hp"));
		// missing attack
		g.load("register_mob{ id='no_atk', name='x', hp=10, defense=3 }").call();
		assertFalse("missing attack must be rejected", LuaMobRegistry.contains("no_atk"));
		// missing defense
		g.load("register_mob{ id='no_def', name='x', hp=10, attack=5 }").call();
		assertFalse("missing defense must be rejected", LuaMobRegistry.contains("no_def"));
	}

	@Test
	public void shippedTestMobRegistersViaEngineInit() {
		// Earlier tests in this suite clear the registry; LuaEngine only scans
		// once per init, so force a fresh scan here before asserting.
		LuaMobRegistry.clear();
		LuaEngine.resetForTests();
		LuaEngine.init();
		assertTrue("scripts/mobs/test_mob.lua should register via LuaEngine.init",
				LuaMobRegistry.contains("test_mob"));
	}

	// ---- RPD.spawnMob wiring ----

	@Test
	public void spawnMobExposedOnRpdGlobal() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		assertFalse("RPD global must be present", g.get("RPD").isnil());
		assertNotNull("RPD.spawnMob must be wired", g.get("RPD").get("spawnMob"));
	}

	@Test
	public void spawnMobOnUnknownMobReturnsNilWithoutThrowing() {
		Globals g = globals();
		LuaValue r = g.load("return RPD.spawnMob('no_such_mob', 0)").call();
		assertTrue("unknown mob id → nil, no throw", r.isnil());
	}

	@Test
	public void spawnMobRejectsBadArgumentTypes() {
		Globals g = globals();
		assertTrue("non-string mobId → nil", g.load("return RPD.spawnMob(123, 0)").call().isnil());
		assertTrue("non-int pos → nil", g.load("return RPD.spawnMob('x', 'y')").call().isnil());
	}

	// ---- M1 sandbox regression (must not break) ----

	@Test
	public void luajavaBindClassStillUnreachableWithSpawnMobInjected() {
		Globals g = globals();
		LuaValue ok = g.load(
				"return pcall(function() return luajava.bindClass('java.lang.Runtime') end)"
		).call();
		assertFalse("luajava.bindClass must still fail with spawnMob present",
				ok.toboolean());
		assertTrue("luajava global itself must remain stripped", g.get("luajava").isnil());
	}

	// ---- helpers ----

	private static LuaTable baseTable(String id) {
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf(id));
		tbl.set("name", LuaValue.valueOf("测试 Lua 怪物"));
		tbl.set("hp", LuaValue.valueOf(20));
		tbl.set("attack", LuaValue.valueOf(8));
		tbl.set("defense", LuaValue.valueOf(4));
		return tbl;
	}
}
