package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.sprites.BlacksmithSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.RatKingSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ShopkeeperSprite;
import com.watabou.utils.Bundle;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.TwoArgFunction;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M4b Lua NPC tests. Mirrors {@link LuaMobTest}'s headless harness.
 *
 * <p>What is pinned down here:
 * <ol>
 *   <li>{@link LuaNpcRegistry} register/getTable/create/ids/contains/size contract.</li>
 *   <li>{@link LuaNpc} hydrate: name + spriteClass (default/whitelist/unknown fallback).</li>
 *   <li>RatKing-clone invincibility: {@code defenseSkill=INFINITE_EVASION},
 *       {@code damage} no-op, {@code add(Buff)=false}, {@code reset=true},
 *       {@code chooseEnemy=null} (via reflection — Mob.chooseEnemy is protected).</li>
 *   <li>{@code register_npc} validation: id/name required.</li>
 *   <li><b>interact routing (codex must-fix)</b>: {@link LuaNpc#dispatchOnInteract}
 *       fires {@code onInteract} only when the caller IS the hero; a non-hero or
 *       null hero never fires it.</li>
 *   <li>{@code RPD.npcYell}/{@code RPD.showDialog} wired + bad-arg rejection.</li>
 *   <li>{@link DataDrivenLevel#createMobs} {@code lua_npc:<id>} branch: a
 *       registered NPC lands in the level's mobs; an unregistered id is skipped.</li>
 *   <li>M1 sandbox regression — {@code luajava.bindClass} still unreachable.</li>
 * </ol>
 *
 * <p>The full {@code interact()} path (linked sprite + {@code Dungeon.hero}) and
 * the live {@code GameScene.show} dialog render are verified by code review + the
 * desktop run, not headlessly (PLAN risk #2 / #3) — same split as
 * {@code LuaMobTest}.
 */
public class LuaNpcTest {

	private static HeadlessApplication application;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		LuaNpcRegistry.clear();
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

	// ---- LuaNpcRegistry ----

	@Test
	public void registryRegisterGetCreate() {
		LuaNpcRegistry.clear();
		LuaTable tbl = baseTable("reg_npc");
		LuaNpcRegistry.register("reg_npc", tbl);
		assertTrue(LuaNpcRegistry.contains("reg_npc"));
		assertEquals(1, LuaNpcRegistry.size());
		assertTrue(LuaNpcRegistry.ids().contains("reg_npc"));
		assertNotNull(LuaNpcRegistry.getTable("reg_npc"));
		assertNotNull(LuaNpcRegistry.create("reg_npc"));
	}

	@Test
	public void registryCreateReturnsNullForUnknown() {
		LuaNpcRegistry.clear();
		assertEquals(null, LuaNpcRegistry.create("does_not_exist"));
		assertFalse(LuaNpcRegistry.contains("ghost"));
	}

	// ---- LuaNpc hydrate ----

	@Test
	public void hydrateSetsNameAndDefaultSprite() {
		LuaNpcRegistry.clear();
		LuaTable tbl = baseTable("hydr");
		LuaNpcRegistry.register("hydr", tbl);
		LuaNpc n = LuaNpcRegistry.create("hydr");
		assertNotNull(n);
		assertEquals("name comes from lua", "测试 Lua NPC", n.name());
		assertEquals("description mirrors name (NPC has no rich desc in MVP)",
				"测试 Lua NPC", n.description());
		assertEquals("no sprite field → default RatKingSprite (SafeZone-themed)",
				RatKingSprite.class, n.spriteClass);
	}

	@Test
	public void whitelistSpriteResolvesAndUnknownFallsBack() {
		LuaNpcRegistry.clear();
		LuaTable tbl = baseTable("spr");
		tbl.set("sprite", LuaValue.valueOf("shopkeeper"));
		LuaNpcRegistry.register("spr", tbl);
		assertEquals("shopkeeper is whitelisted",
				ShopkeeperSprite.class, LuaNpcRegistry.create("spr").spriteClass);

		LuaTable tbl2 = baseTable("spr2");
		tbl2.set("sprite", LuaValue.valueOf("blacksmith"));
		LuaNpcRegistry.register("spr2", tbl2);
		assertEquals("blacksmith is whitelisted",
				BlacksmithSprite.class, LuaNpcRegistry.create("spr2").spriteClass);

		LuaTable tbl3 = baseTable("spr3");
		tbl3.set("sprite", LuaValue.valueOf("nonexistent_sprite"));
		LuaNpcRegistry.register("spr3", tbl3);
		assertEquals("unknown sprite degrades to RatKingSprite, not a crash",
				RatKingSprite.class, LuaNpcRegistry.create("spr3").spriteClass);
	}

	// ---- invincibility (RatKing clone) ----

	@Test
	public void defenseSkillIsInfiniteEvasion() {
		LuaNpcRegistry.clear();
		LuaNpcRegistry.register("def", baseTable("def"));
		LuaNpc n = LuaNpcRegistry.create("def");
		assertEquals("NPC must be unhittable", Char.INFINITE_EVASION, n.defenseSkill(null));
	}

	@Test
	public void damageIsNoOp() {
		LuaNpcRegistry.clear();
		LuaNpcRegistry.register("dmg", baseTable("dmg"));
		LuaNpc n = LuaNpcRegistry.create("dmg");
		int hpBefore = n.HP;
		n.damage(99, this);
		assertEquals("damage must never reduce HP (invincible)", hpBefore, n.HP);
	}

	@Test
	public void addBuffReturnsFalse() {
		LuaNpcRegistry.clear();
		LuaNpcRegistry.register("buff", baseTable("buff"));
		LuaNpc n = LuaNpcRegistry.create("buff");
		// Buff() is abstract; a concrete no-op subclass is enough for the contract test.
		Buff b = new Buff() { };
		assertFalse("NPC rejects every buff (SafeZone-safe)", n.add(b));
	}

	@Test
	public void resetReturnsTrue() {
		LuaNpcRegistry.clear();
		LuaNpcRegistry.register("rst", baseTable("rst"));
		LuaNpc n = LuaNpcRegistry.create("rst");
		assertTrue("reset=true so the NPC survives level re-entry", n.reset());
	}

	@Test
	public void chooseEnemyReturnsNullViaReflection() throws Exception {
		LuaNpcRegistry.clear();
		LuaNpcRegistry.register("ce", baseTable("ce"));
		LuaNpc n = LuaNpcRegistry.create("ce");
		// Mob.chooseEnemy is protected; reflect to verify the null return without
		// having to put the test in the mobs package.
		Method m = com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob.class
				.getDeclaredMethod("chooseEnemy");
		m.setAccessible(true);
		Object enemy = m.invoke(n);
		assertNull("chooseEnemy=null → NPC never aggros", enemy);
	}

	// ---- persistence ----

	@Test
	public void bundleRoundTripsLuaNpcIdAndRehydratesName() {
		LuaNpcRegistry.clear();
		LuaNpcRegistry.register("rt", baseTable("rt"));
		LuaNpc original = LuaNpcRegistry.create("rt");
		assertEquals("fresh NPC name from lua", "测试 Lua NPC", original.name());

		Bundle b = new Bundle();
		original.storeInBundle(b);
		LuaNpc restored = new LuaNpc();
		restored.restoreFromBundle(b);

		assertEquals("lua_npc_id round-trips", "rt", restoredLuaNpcId(restored));
		assertEquals("name re-hydrated from registry after restore",
				"测试 Lua NPC", restored.name());
		assertEquals("sprite re-hydrated", RatKingSprite.class, restored.spriteClass);
	}

	@Test
	public void restoreDegradesGracefullyWhenRegistryEmpty() {
		LuaNpcRegistry.clear();
		LuaNpcRegistry.register("gone", baseTable("gone"));
		LuaNpc src = LuaNpcRegistry.create("gone");
		Bundle b = new Bundle();
		src.storeInBundle(b);

		LuaNpcRegistry.clear();  // simulate engine init failing / script removed
		LuaNpc restored = new LuaNpc();
		restored.restoreFromBundle(b);
		assertEquals("missing definition → degraded name, no crash",
				"??? (gone)", restored.name());
	}

	private static String restoredLuaNpcId(LuaNpc n) {
		try {
			Field f = LuaNpc.class.getDeclaredField("luaNpcId");
			f.setAccessible(true);
			return (String) f.get(n);
		} catch (Exception e) {
			throw new AssertionError("luaNpcId not readable", e);
		}
	}

	// ---- register_npc global ----

	@Test
	public void registerNpcAcceptsValidTable() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		g.load("register_npc{ id='ok_npc', name='x' }").call();
		assertTrue("valid table should register", LuaNpcRegistry.contains("ok_npc"));
	}

	@Test
	public void registerNpcRejectsMissingRequiredFields() {
		// LuaEngine.init() is idempotent and (re)scans only on first call, so the
		// shipped test_npc registers once here; clear afterwards so the rejection
		// checks start from a known-empty registry.
		LuaEngine.init();
		LuaNpcRegistry.clear();
		Globals g = LuaEngine.instance().globals();

		g.load("register_npc{ name='no_id' }").call();
		assertEquals("missing id must be rejected", 0, LuaNpcRegistry.size());

		g.load("register_npc{ id='no_name' }").call();
		assertEquals("missing name must be rejected", 0, LuaNpcRegistry.size());
		assertFalse(LuaNpcRegistry.contains("no_name"));
	}

	@Test
	public void shippedTestNpcRegistersViaEngineInit() {
		LuaNpcRegistry.clear();
		LuaEngine.resetForTests();
		LuaEngine.init();
		assertTrue("scripts/npcs/test_npc.lua should register via LuaEngine.init",
				LuaNpcRegistry.contains("test_npc"));
	}

	// ---- interact routing (codex must-fix) ----

	@Test
	public void dispatchOnInteractFiresWhenCallerIsHero() {
		LuaNpcRegistry.clear();
		LuaNpcRegistry.register("act", baseTable("act"));
		LuaNpc npc = LuaNpcRegistry.create("act");
		final LuaValue[] captured = new LuaValue[2];
		LuaNpcRegistry.getTable("act").set("onInteract", new TwoArgFunction() {
			@Override public LuaValue call(LuaValue selfId, LuaValue heroId) {
				captured[0] = selfId; captured[1] = heroId;
				return NIL;
			}
		});

		npc.dispatchOnInteract(npc, npc);  // caller IS hero
		assertNotNull("onInteract must fire when caller == hero", captured[0]);
	}

	@Test
	public void dispatchOnInteractSkipsWhenCallerIsNotHero() {
		LuaNpcRegistry.clear();
		LuaNpcRegistry.register("nhero", baseTable("nhero"));
		LuaNpc npc = LuaNpcRegistry.create("nhero");
		final int[] calls = {0};
		LuaNpcRegistry.getTable("nhero").set("onInteract", new TwoArgFunction() {
			@Override public LuaValue call(LuaValue selfId, LuaValue heroId) {
				calls[0]++; return NIL;
			}
		});

		// A second NPC instance is a stand-in for a non-hero Char (ally/clone).
		LuaNpc someoneElse = LuaNpcRegistry.create("nhero");
		npc.dispatchOnInteract(npc, someoneElse);
		assertEquals("non-hero caller must not trigger onInteract", 0, calls[0]);

		npc.dispatchOnInteract(npc, null);  // hero absent (Dungeon.hero == null in headless)
		assertEquals("null hero must not trigger onInteract", 0, calls[0]);
	}

	// ---- RPD.npcYell / showDialog wiring ----

	@Test
	public void npcYellAndShowDialogExposedOnRpdGlobal() {
		LuaEngine.init();
		Globals g = LuaEngine.instance().globals();
		assertFalse("RPD global must be present", g.get("RPD").isnil());
		assertNotNull("RPD.npcYell must be wired", g.get("RPD").get("npcYell"));
		assertNotNull("RPD.showDialog must be wired", g.get("RPD").get("showDialog"));
	}

	@Test
	public void npcYellRejectsBadArgumentsWithoutThrowing() {
		Globals g = globals();
		assertTrue("non-int charId → nil", g.load("return RPD.npcYell('x','hi')").call().isnil());
		assertTrue("unknown charId → nil", g.load("return RPD.npcYell(99999,'hi')").call().isnil());
	}

	@Test
	public void showDialogRejectsBadArgumentsWithoutThrowing() {
		Globals g = globals();
		assertTrue("non-int charId → nil", g.load("return RPD.showDialog('x','hi')").call().isnil());
		assertTrue("unknown charId → nil", g.load("return RPD.showDialog(99999,'hi')").call().isnil());
	}

	// ---- DataDrivenLevel.createMobs lua_npc:<id> branch ----

	@Test
	public void createMobsInstantiatesLuaNpcFromRegistry() {
		LuaNpcRegistry.clear();
		LuaNpcRegistry.register("lvl_npc", baseTable("lvl_npc"));

		DataDrivenLevel lvl = DataDrivenLevel.fromJsonValue(sampleJsonWithLuaNpc(), "test");
		lvl.build();
		initLevelActorCollections(lvl);
		lvl.createMobs();

		int luaNpcCount = 0;
		for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob m : lvl.mobs) {
			if (m instanceof LuaNpc) luaNpcCount++;
		}
		assertEquals("the lua_npc:test spec should produce one LuaNpc on the level",
				1, luaNpcCount);
	}

	@Test
	public void createMobsSkipsUnknownLuaNpcIdWithoutThrowing() {
		LuaNpcRegistry.clear();  // no "ghost_npc" registered
		DataDrivenLevel lvl = DataDrivenLevel.fromJsonValue(sampleJsonWithUnknownLuaNpc(), "test");
		lvl.build();
		initLevelActorCollections(lvl);
		lvl.createMobs();

		assertEquals("unknown lua_npc id is skipped, level stays empty",
				0, lvl.mobs.size());
	}

	private static void initLevelActorCollections(DataDrivenLevel lvl) {
		// Mirror Level.create()'s ordering: allocate the actor/feature collections
		// (incl. blobs — buildFlagMaps iterates blobs.values()) BEFORE buildFlagMaps,
		// so passable[] is populated for createMobs' pos validation.
		lvl.mobs = new HashSet<>();
		lvl.heaps = new com.watabou.utils.SparseArray<>();
		lvl.blobs = new HashMap<>();
		lvl.plants = new com.watabou.utils.SparseArray<>();
		lvl.traps = new com.watabou.utils.SparseArray<>();
		lvl.customTiles = new ArrayList<>();
		lvl.customWalls = new ArrayList<>();
		lvl.visited = new boolean[lvl.length()];
		lvl.mapped = new boolean[lvl.length()];
		lvl.transitions = new ArrayList<>();
		lvl.buildFlagMaps();
	}

	private static com.badlogic.gdx.utils.JsonValue sampleJsonWithLuaNpc() {
		String json = ("{'id':'t','name':'t','width':4,'height':4,'entrance':5,'safe':true,"
				+ "'tiles':['wall','wall','wall','wall',"
				+ "'wall','floor','floor','wall',"
				+ "'wall','floor','floor','wall',"
				+ "'wall','wall','wall','wall'],"
				+ "'mobs':[{'type':'lua_npc:lvl_npc','pos':6}]"
				+ "}").replace('\'', '"');
		return new com.badlogic.gdx.utils.JsonReader().parse(json);
	}

	private static com.badlogic.gdx.utils.JsonValue sampleJsonWithUnknownLuaNpc() {
		String json = ("{'id':'t','name':'t','width':4,'height':4,'entrance':5,'safe':true,"
				+ "'tiles':['wall','wall','wall','wall',"
				+ "'wall','floor','floor','wall',"
				+ "'wall','floor','floor','wall',"
				+ "'wall','wall','wall','wall'],"
				+ "'mobs':[{'type':'lua_npc:ghost_npc','pos':6}]"
				+ "}").replace('\'', '"');
		return new com.badlogic.gdx.utils.JsonReader().parse(json);
	}

	// ---- M1 sandbox regression ----

	@Test
	public void luajavaBindClassStillUnreachableWithNpcGlobalsInjected() {
		Globals g = globals();
		LuaValue ok = g.load(
				"return pcall(function() return luajava.bindClass('java.lang.Runtime') end)"
		).call();
		assertFalse("luajava.bindClass must still fail with register_npc/npcYell present",
				ok.toboolean());
		assertTrue("luajava global itself must remain stripped", g.get("luajava").isnil());
	}

	// ---- helpers ----

	private static LuaTable baseTable(String id) {
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf(id));
		tbl.set("name", LuaValue.valueOf("测试 Lua NPC"));
		return tbl;
	}
}
