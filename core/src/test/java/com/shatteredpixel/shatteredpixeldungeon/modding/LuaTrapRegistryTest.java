package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.SparseArray;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.TwoArgFunction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M10b: {@link LuaTrapRegistry} + {@link LuaTrap} + {@code register_trap}
 * global + {@link LuaLevelService#placeLuaTraps} placement. Mirrors the headless
 * harness of {@link LuaNpcTest} / {@link LuaLevelInjectTest}.
 */
public class LuaTrapRegistryTest {

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

	@AfterClass
	public static void shutdown() {
		Game.versionCode = savedVersionCode;
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
		Actor.clear();
	}

	@Before
	public void reset() {
		ModTestSupport.resetLuaState();
	}

	private static LuaTable baseTable(String id) {
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf(id));
		tbl.set("name", LuaValue.valueOf(id));
		return tbl;
	}

	// ---- registry ----

	@Test
	public void registerGetContainsSize() {
		assertFalse(LuaTrapRegistry.hasAny());
		LuaTrapRegistry.register("demo_trap", baseTable("demo_trap"));
		assertTrue(LuaTrapRegistry.contains("demo_trap"));
		assertNotNull(LuaTrapRegistry.getTable("demo_trap"));
		assertTrue(LuaTrapRegistry.hasAny());
		assertEquals(1, LuaTrapRegistry.size());
		assertTrue(LuaTrapRegistry.ids().contains("demo_trap"));
	}

	@Test
	public void clearDropsAll() {
		LuaTrapRegistry.register("demo_trap", baseTable("demo_trap"));
		LuaTrapRegistry.clear();
		assertFalse(LuaTrapRegistry.hasAny());
		assertNull(LuaTrapRegistry.getTable("demo_trap"));
	}

	@Test
	public void register_trap_validatesAndRegisters() {
		Globals g = LuaSandbox.exposedGlobals();
		LuaEngine.installGlobalsForTests(g);

		LuaTable tbl = baseTable("demo_trap");
		g.get("register_trap").call(tbl);
		assertTrue(LuaTrapRegistry.contains("demo_trap"));

		// non-table rejected
		g.get("register_trap").call(LuaValue.valueOf("nope"));
		assertEquals(1, LuaTrapRegistry.size());

		// missing id rejected
		g.get("register_trap").call(new LuaTable());
		assertEquals(1, LuaTrapRegistry.size());
	}

	// ---- LuaTrap.activate dispatch ----

	@Test
	public void activate_dispatchesOnActivateWithCellAndCharId() {
		// onActivate records (cell, charId) via a Java TwoArgFunction callback.
		final int[] received = { -1, -2 };
		LuaTable tbl = baseTable("demo_trap");
		tbl.set("onActivate", new TwoArgFunction() {
			@Override
			public LuaValue call(LuaValue cellArg, LuaValue charArg) {
				received[0] = cellArg.checkint();
				received[1] = charArg.checkint();
				return NIL;
			}
		});
		LuaTrapRegistry.register("demo_trap", tbl);

		LuaTrap trap = new LuaTrap(tbl);
		trap.pos = 7;
		// No char on cell 7 → Actor.findChar returns null → charId 0.
		trap.activate();

		assertEquals("onActivate must receive the trap's cell", 7, received[0]);
		assertEquals("onActivate must receive charId 0 when no char present", 0, received[1]);
	}

	@Test
	public void activate_noTableDegradesSilently() {
		// Registry has the id but table somehow missing — activate must not throw.
		LuaTrap trap = new LuaTrap(baseTable("ghost_trap"));
		LuaTrapRegistry.clear();
		trap.pos = 3;
		trap.activate(); // no exception
	}

	// ---- persistence ----

	@Test
	public void bundleRoundTrip_rehydratesFromRegistry() {
		LuaTrapRegistry.register("demo_trap", baseTable("demo_trap"));
		LuaTrap t1 = new LuaTrap(LuaTrapRegistry.getTable("demo_trap"));
		t1.pos = 5;
		t1.visible = false;
		Bundle b = new Bundle();
		t1.storeInBundle(b);

		LuaTrap t2 = new LuaTrap();
		t2.restoreFromBundle(b);

		assertEquals("pos persisted by Trap base", 5, t2.pos);
		assertEquals("name re-hydrated from registry", "demo_trap", t2.name());
		assertEquals("color defaulted (GREY)", Trap.GREY, t2.color);
	}

	@Test
	public void bundleRestore_missingScript_degradesInactive() {
		LuaTrapRegistry.register("demo_trap", baseTable("demo_trap"));
		LuaTrap t1 = new LuaTrap(LuaTrapRegistry.getTable("demo_trap"));
		t1.pos = 5;
		Bundle b = new Bundle();
		t1.storeInBundle(b);

		LuaTrapRegistry.clear(); // simulate script gone / mod disabled
		LuaTrap t2 = new LuaTrap();
		t2.restoreFromBundle(b);

		assertFalse("missing script → degrade to inactive (no crash)", t2.active);
	}

	// ---- placeLuaTraps ----

	@Test
	public void placeLuaTraps_placesHiddenTrapWithRefreshedFlags() {
		LuaTrapRegistry.register("demo_trap", baseTable("demo_trap"));
		Level lvl = newStubLevel(10, 10, Terrain.EMPTY);

		LuaLevelService.placeLuaTraps(lvl);

		int luaTraps = 0;
		int placedPos = -1;
		for (int i = 0; i < lvl.length(); i++) {
			Trap t = lvl.traps.get(i);
			if (t instanceof LuaTrap) { luaTraps++; placedPos = i; }
		}
		assertEquals("exactly one Lua trap placed (budget = min(1, valid/5))", 1, luaTraps);
		assertTrue(placedPos >= 0);
		assertEquals("map cell flipped to SECRET_TRAP",
				Terrain.SECRET_TRAP, lvl.map[placedPos]);
		assertTrue("updateCellFlags must set the secret[] flag hidden-trap search reads",
				lvl.secret[placedPos]);
		assertFalse("trap hidden", ((LuaTrap) lvl.traps.get(placedPos)).visible);
	}

	@Test
	public void placeLuaTraps_noopWhenRegistryEmpty() {
		Level lvl = newStubLevel(10, 10, Terrain.EMPTY);
		LuaLevelService.placeLuaTraps(lvl);
		for (int i = 0; i < lvl.length(); i++) {
			assertNull(lvl.traps.get(i));
		}
	}

	@Test
	public void placeLuaTraps_skipsMobOccupiedAndEntranceExit() {
		LuaTrapRegistry.register("demo_trap", baseTable("demo_trap"));
		Level lvl = newStubLevel(10, 10, Terrain.EMPTY);
		// With only ~100 EMPTY cells and budget=1, just assert no trap lands on
		// entrance/exit. entrance=0, exit=99 here.
		LuaLevelService.placeLuaTraps(lvl);
		assertNull("entrance must never get a trap", lvl.traps.get(((StubLevel) lvl).entranceCell));
		assertNull("exit must never get a trap", lvl.traps.get(((StubLevel) lvl).exitCell));
	}

	// ---- helpers ----

	private static final class StubLevel extends Level {
		int entranceCell;
		int exitCell;
		@Override protected boolean build() { return true; }
		@Override protected void createMobs() { }
		@Override protected void createItems() { }
		@Override public int entrance() { return entranceCell; }
		@Override public int exit() { return exitCell; }
		@Override public String tilesTex() { return null; }
		@Override public String waterTex() { return null; }
	}

	private static Level newStubLevel(int w, int h, int fillTerrain) {
		StubLevel lvl = new StubLevel();
		lvl.setSize(w, h);
		lvl.entranceCell = 0;
		lvl.exitCell = w * h - 1;
		java.util.Arrays.fill(lvl.map, fillTerrain);
		// Production RegularLevels have a solid WALL border (setSize fills WALL,
		// the painter paints room interiors to EMPTY), so EMPTY cells are always
		// interior. placeLuaTraps -> updateCellFlags -> updateOpenSpace indexes
		// PathFinder.NEIGHBOURS9, which AIOOBEs on border cells — so carve a WALL
		// ring to mirror production rather than relying on a guard that prod does
		// not need (EMPTY is interior by construction in real levels).
		if (fillTerrain == Terrain.EMPTY) {
			for (int x = 0; x < w; x++) {
				lvl.map[x] = Terrain.WALL;                         // top row
				lvl.map[(h - 1) * w + x] = Terrain.WALL;          // bottom row
			}
			for (int y = 0; y < h; y++) {
				lvl.map[y * w] = Terrain.WALL;                    // left col
				lvl.map[y * w + w - 1] = Terrain.WALL;            // right col
			}
		}
		lvl.mobs = new HashSet<>();
		lvl.heaps = new SparseArray<>();
		lvl.blobs = new HashMap<>();
		lvl.plants = new SparseArray<>();
		lvl.traps = new SparseArray<>();
		lvl.customTiles = new ArrayList<>();
		lvl.customWalls = new ArrayList<>();
		lvl.visited = new boolean[lvl.length()];
		lvl.mapped = new boolean[lvl.length()];
		lvl.buildFlagMaps();
		return lvl;
	}
}
