package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.painters.Painter;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.watabou.noosa.Game;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.TwoArgFunction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M10b: {@link LuaPainterAdapter} routing + the {@code setTile} safety gate.
 * Reuses the {@code StubLevel} headless pattern from {@link LuaLevelInjectTest}.
 *
 * <p>The adapter is exercised end-to-end via {@code adapter.paint(level, rooms)}
 * with a no-op delegate (so the upstream pipeline is stubbed and only the Lua
 * overlay is under test). The delegate path itself is upstream code, not M10b.
 */
public class LuaPainterAdapterTest {

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
	}

	@Before
	public void reset() {
		ModTestSupport.resetLuaState();
	}

	/** Painter whose paint(decorate) callback records order + mutates a cell. */
	private static LuaTable painterThatSets(final int cell, final int terrain, final boolean[] accepted) {
		LuaTable tbl = new LuaTable();
		tbl.set("paint", new TwoArgFunction() {
			@Override
			public LuaValue call(LuaValue levelTbl, LuaValue roomTbl) {
				LuaValue res = roomTbl.get("setTile").call(LuaValue.valueOf(cell), LuaValue.valueOf(terrain));
				accepted[0] = res.toboolean();
				return NIL;
			}
		});
		return tbl;
	}

	@Test
	public void overlay_firesPaintAndMutatesMap() {
		Level lvl = newStubLevel(8, 8, Terrain.EMPTY);
		Room room = newStubRoom(1, 1, 6, 6); // interior cells x,y in 2..5
		final boolean[] paintCalled = { false };
		final int[] paintedCell = { -1 };
		LuaTable tbl = new LuaTable();
		tbl.set("paint", new TwoArgFunction() {
			@Override
			public LuaValue call(LuaValue levelTbl, LuaValue roomTbl) {
				paintCalled[0] = true;
				int cell = roomTbl.checktable().get("cells").get(1).checkint();
				paintedCell[0] = cell;
				roomTbl.get("setTile").call(LuaValue.valueOf(cell), LuaValue.valueOf(Terrain.EMPTY_DECO));
				return NIL;
			}
		});
		LuaPainterRegistry.register("StubRoom", tbl);

		ArrayList<Room> rooms = new ArrayList<>();
		rooms.add(room);
		new LuaPainterAdapter(noopPainter()).paint(lvl, rooms);

		assertTrue("paint callback must fire for a registered room class", paintCalled[0]);
		assertTrue(paintedCell[0] >= 0);
		assertEquals("setTile must mutate level.map (EMPTY -> EMPTY_DECO)",
				Terrain.EMPTY_DECO, lvl.map[paintedCell[0]]);
	}

	@Test
	public void overlay_firesDecorateAfterPaint() {
		Level lvl = newStubLevel(8, 8, Terrain.EMPTY);
		Room room = newStubRoom(1, 1, 6, 6);
		final java.util.List<String> order = new ArrayList<>();
		LuaTable tbl = new LuaTable();
		tbl.set("paint", new TwoArgFunction() {
			@Override public LuaValue call(LuaValue l, LuaValue r) { order.add("paint"); return NIL; }
		});
		tbl.set("decorate", new TwoArgFunction() {
			@Override public LuaValue call(LuaValue l, LuaValue r) { order.add("decorate"); return NIL; }
		});
		LuaPainterRegistry.register("StubRoom", tbl);

		ArrayList<Room> rooms = new ArrayList<>();
		rooms.add(room);
		new LuaPainterAdapter(noopPainter()).paint(lvl, rooms);

		assertEquals("paint then decorate", java.util.Arrays.asList("paint", "decorate"), order);
	}

	@Test
	public void unregisteredRoomClass_notOverlaid() {
		Level lvl = newStubLevel(8, 8, Terrain.EMPTY);
		Room room = newStubRoom(1, 1, 6, 6); // simpleName "StubRoom"
		final boolean[] called = { false };
		LuaTable tbl = new LuaTable();
		tbl.set("paint", new TwoArgFunction() {
			@Override public LuaValue call(LuaValue l, LuaValue r) { called[0] = true; return NIL; }
		});
		LuaPainterRegistry.register("SomeOtherRoom", tbl); // different key

		ArrayList<Room> rooms = new ArrayList<>();
		rooms.add(room);
		new LuaPainterAdapter(noopPainter()).paint(lvl, rooms);

		assertFalse("a room whose class is not registered must not be overlaid", called[0]);
	}

	@Test
	public void setTile_rejectsProtectedDoorTerrain() {
		int cell = 18; // interior of room (1,1,6,6) on 8x8
		Level lvl = newStubLevel(8, 8, Terrain.EMPTY);
		lvl.map[cell] = Terrain.DOOR;
		Room room = newStubRoom(1, 1, 6, 6);
		boolean[] accepted = { true };
		LuaPainterRegistry.register("StubRoom", painterThatSets(cell, Terrain.EMPTY_DECO, accepted));

		ArrayList<Room> rooms = new ArrayList<>();
		rooms.add(room);
		new LuaPainterAdapter(noopPainter()).paint(lvl, rooms);

		assertFalse("setTile on a DOOR cell must be rejected", accepted[0]);
		assertEquals("DOOR cell unchanged", Terrain.DOOR, lvl.map[cell]);
	}

	@Test
	public void setTile_rejectsGrassTerrain() {
		int cell = 18;
		Level lvl = newStubLevel(8, 8, Terrain.EMPTY);
		lvl.map[cell] = Terrain.HIGH_GRASS;
		Room room = newStubRoom(1, 1, 6, 6);
		boolean[] accepted = { true };
		LuaPainterRegistry.register("StubRoom", painterThatSets(cell, Terrain.EMPTY_DECO, accepted));

		ArrayList<Room> rooms = new ArrayList<>();
		rooms.add(room);
		new LuaPainterAdapter(noopPainter()).paint(lvl, rooms);

		assertFalse("setTile on HIGH_GRASS must be rejected (overlay runs after paintGrass)", accepted[0]);
		assertEquals(Terrain.HIGH_GRASS, lvl.map[cell]);
	}

	@Test
	public void setTile_rejectsNonWhitelistTarget() {
		int cell = 18;
		Level lvl = newStubLevel(8, 8, Terrain.EMPTY);
		Room room = newStubRoom(1, 1, 6, 6);
		boolean[] accepted = { true };
		// EMPTY -> WATER is forbidden (WATER not in target whitelist)
		LuaPainterRegistry.register("StubRoom", painterThatSets(cell, Terrain.WATER, accepted));

		ArrayList<Room> rooms = new ArrayList<>();
		rooms.add(room);
		new LuaPainterAdapter(noopPainter()).paint(lvl, rooms);

		assertFalse("setTile to WATER must be rejected (target not whitelisted)", accepted[0]);
		assertEquals("cell stays EMPTY", Terrain.EMPTY, lvl.map[cell]);
	}

	@Test
	public void setTile_rejectsSolidWallDecoTarget() {
		// WALL_DECO sounds decorative but its flags equal WALL (SOLID|LOS_BLOCKING),
		// so allowing it would let a painter seal a room's path. Must be rejected.
		int cell = 18;
		Level lvl = newStubLevel(8, 8, Terrain.EMPTY);
		Room room = newStubRoom(1, 1, 6, 6);
		boolean[] accepted = { true };
		LuaPainterRegistry.register("StubRoom", painterThatSets(cell, Terrain.WALL_DECO, accepted));

		ArrayList<Room> rooms = new ArrayList<>();
		rooms.add(room);
		new LuaPainterAdapter(noopPainter()).paint(lvl, rooms);

		assertFalse("setTile to WALL_DECO must be rejected (SOLID, would block path)", accepted[0]);
		assertEquals("cell stays EMPTY", Terrain.EMPTY, lvl.map[cell]);
	}

	@Test
	public void setTile_rejectsNonInteriorCell() {
		int outside = 0; // corner, not in any room interior
		Level lvl = newStubLevel(8, 8, Terrain.EMPTY);
		Room room = newStubRoom(1, 1, 6, 6);
		boolean[] accepted = { true };
		LuaPainterRegistry.register("StubRoom", painterThatSets(outside, Terrain.EMPTY_DECO, accepted));

		ArrayList<Room> rooms = new ArrayList<>();
		rooms.add(room);
		new LuaPainterAdapter(noopPainter()).paint(lvl, rooms);

		assertFalse("setTile outside the room interior must be rejected", accepted[0]);
		assertEquals(Terrain.EMPTY, lvl.map[outside]);
	}

	@Test
	public void brokenPainterDoesNotCrashLevelgen() {
		Level lvl = newStubLevel(8, 8, Terrain.EMPTY);
		Room room = newStubRoom(1, 1, 6, 6);
		LuaTable tbl = new LuaTable();
		tbl.set("paint", new TwoArgFunction() {
			@Override public LuaValue call(LuaValue l, LuaValue r) {
				throw new RuntimeException("script bug");
			}
		});
		LuaPainterRegistry.register("StubRoom", tbl);

		ArrayList<Room> rooms = new ArrayList<>();
		rooms.add(room);
		boolean ok = new LuaPainterAdapter(noopPainter()).paint(lvl, rooms);

		assertTrue("delegate paint result preserved even if Lua overlay throws", ok);
		// map untouched (delegate was no-op)
		assertEquals(Terrain.EMPTY, lvl.map[18]);
	}

	// ---- helpers ----

	private static Painter noopPainter() {
		return new Painter() {
			@Override
			public boolean paint(Level level, ArrayList<Room> rooms) {
				return true;
			}
		};
	}

	private static final class StubRoom extends Room {
		@Override
		public void paint(Level level) {
			// no-op — the adapter test drives overlay directly, not upstream room paint
		}
	}

	private static Room newStubRoom(int left, int top, int right, int bottom) {
		StubRoom r = new StubRoom();
		r.left = left;
		r.top = top;
		r.right = right;
		r.bottom = bottom;
		return r;
	}

	private static final class StubLevel extends Level {
		@Override protected boolean build() { return true; }
		@Override protected void createMobs() { }
		@Override protected void createItems() { }
		@Override public int entrance() { return 0; }
		@Override public int exit() { return length() - 1; }
		@Override public String tilesTex() { return null; }
		@Override public String waterTex() { return null; }
	}

	private static Level newStubLevel(int w, int h, int fillTerrain) {
		StubLevel lvl = new StubLevel();
		lvl.setSize(w, h);
		java.util.Arrays.fill(lvl.map, fillTerrain);
		lvl.mobs = new HashSet<>();
		lvl.heaps = new com.watabou.utils.SparseArray<>();
		lvl.blobs = new HashMap<>();
		lvl.plants = new com.watabou.utils.SparseArray<>();
		lvl.traps = new com.watabou.utils.SparseArray<>();
		lvl.customTiles = new ArrayList<>();
		lvl.customWalls = new ArrayList<>();
		lvl.visited = new boolean[lvl.length()];
		lvl.mapped = new boolean[lvl.length()];
		lvl.buildFlagMaps();
		return lvl;
	}
}
