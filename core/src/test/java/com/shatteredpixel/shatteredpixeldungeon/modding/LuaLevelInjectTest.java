package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.QuickSlot;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * M4d coverage: main-line NPC injection ({@link LuaLevelService#injectLevelNpcs})
 * and the R4 state-preservation seam ({@link LuaLevelService.LiveHeroState}).
 *
 * <p>What is pinned down headlessly:
 * <ol>
 *   <li>{@code spawnForDepth} routing: depth 1 + registered {@code town_portal}
 *       spawns exactly one {@link LuaNpc} near the entrance; depth != 1 spawns
 *       nothing; empty registry spawns nothing gracefully.</li>
 *   <li>{@code findSpawnPosNearEntrance}: returns an adjacent passable cell,
 *       excludes the entrance itself, skips occupied cells, and returns -1 (no
 *       fallback to entrance) when all candidates are blocked.</li>
 *   <li>{@code injectLevelNpcs} isDebug guard: toggling {@link Game#version}
 *       (which {@code DeviceCompat.isDebug()} reads) between INDEV and a
 *       release-style string flips whether a spawn occurs.</li>
 *   <li>{@code injectLevelNpcs} skips {@link DataDrivenLevel} (the SafeZone owns
 *       its own createMobs).</li>
 *   <li>R4 seam: {@link LuaLevelService#captureLiveState()} +
 *       {@link LuaLevelService#applyLiveState(LuaLevelService.LiveHeroState)}
 *       round-trips {@link Dungeon#hero}/{@code gold}/{@code quickslot}/nextID
 *       after a simulated {@code loadGame} overwrite — the hero object identity
 *       is preserved (so belongings/HP/buffs survive) and gold/quickslot/nextID
 *       are restored to live values.</li>
 * </ol>
 *
 * <p>The full {@code leaveLevel} path (real {@code loadGame}+{@code switchLevel}+
 * {@code switchScene}) and the dead-hero CONTINUE branch are verified by the
 * desktop debug run, not headlessly — they need real save files + a live scene
 * this harness does not stand up (same split as m4a's enter/leave).
 */
public class LuaLevelInjectTest {

	private static HeadlessApplication application;
	private static String prevVersion;
	private static int savedVersionCode;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		prevVersion = Game.version;
		// M5c: version gate admits test_mod (spd_version=896) so its NPC scripts load.
		savedVersionCode = Game.versionCode;
		Game.versionCode = 896;
	}

	@AfterClass
	public static void shutdown() {
		Game.version = prevVersion;
		Game.versionCode = savedVersionCode;
		// Restore null/safe globals we may have mutated.
		Dungeon.hero = null;
		Dungeon.gold = 0;
		Dungeon.depth = 1;
		Dungeon.quickslot = new QuickSlot();
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	@Before
	public void resetPerTest() throws Exception {
		ModTestSupport.enableTestMod();
		ModTestSupport.resetLuaState();
		// Default to a non-INDEV version so isDebug() == false unless a test opts in.
		Game.version = "1.0.0-release";
	}

	// ---- spawnForDepth routing ----

	@Test
	public void spawnForDepth_depth1_withRegisteredNpc_spawnsOne() {
		LuaNpcRegistry.register("town_portal", baseNpcTable("town_portal"));
		Level lvl = newStubLevel(5, 5, /*entrance=*/12, Terrain.EMPTY);
		LuaLevelService.spawnForDepth(lvl, /*depth=*/1);

		int luaNpcs = 0;
		for (Mob m : lvl.mobs) if (m instanceof LuaNpc) luaNpcs++;
		assertEquals("depth 1 + registered town_portal → exactly one LuaNpc spawned",
				1, luaNpcs);
	}

	@Test
	public void spawnForDepth_depth2_spawnsNothing() {
		LuaNpcRegistry.register("town_portal", baseNpcTable("town_portal"));
		Level lvl = newStubLevel(5, 5, 12, Terrain.EMPTY);
		LuaLevelService.spawnForDepth(lvl, /*depth=*/2);
		assertEquals("depth != 1 → no spawn", 0, lvl.mobs.size());
	}

	@Test
	public void spawnForDepth_depth1_emptyRegistry_spawnsNothingGracefully() {
		// registry left empty by resetPerTest
		Level lvl = newStubLevel(5, 5, 12, Terrain.EMPTY);
		LuaLevelService.spawnForDepth(lvl, /*depth=*/1);
		assertEquals("missing town_portal registration → graceful no-spawn, no throw",
				0, lvl.mobs.size());
	}

	@Test
	public void spawnForDepth_usesAdjacentPassableCellNotEntrance() {
		LuaNpcRegistry.register("town_portal", baseNpcTable("town_portal"));
		Level lvl = newStubLevel(5, 5, 12, Terrain.EMPTY);
		LuaLevelService.spawnForDepth(lvl, 1);

		Mob spawned = null;
		for (Mob m : lvl.mobs) if (m instanceof LuaNpc) spawned = m;
		assertNotNull("expected one spawn", spawned);
		assertNotEquals("NPC must NOT sit on the entrance cell itself",
				lvl.entrance(), spawned.pos);
		assertTrue("NPC must land on a passable cell",
				lvl.passable[spawned.pos]);
	}

	// ---- findSpawnPosNearEntrance ----

	@Test
	public void findSpawnPos_returnsAdjacentPassableCell() {
		Level lvl = newStubLevel(5, 5, 12, Terrain.EMPTY);
		int pos = LuaLevelService.findSpawnPosNearEntrance(lvl);
		assertTrue("must return a passable cell", pos >= 0 && lvl.passable[pos]);
		assertNotEquals("must not be the entrance", lvl.entrance(), pos);
		// adjacency (Chebyshev distance 1 or 2)
		assertTrue("must be within radius 2 of entrance", chebyshev(lvl, lvl.entrance(), pos) <= 2);
	}

	@Test
	public void findSpawnPos_returnsNegativeOneWhenAllNeighborsBlocked() {
		// 5x5, entrance at 12 (center). Wall off EVERY non-entrance cell so no
		// passable candidate exists. Result must be -1 (skip), NOT the entrance.
		Level lvl = newStubLevel(5, 5, 12, Terrain.WALL);
		// carve only the entrance cell to floor
		lvl.map[12] = Terrain.EMPTY;
		lvl.buildFlagMaps();
		int pos = LuaLevelService.findSpawnPosNearEntrance(lvl);
		assertEquals("all neighbors blocked → -1, never the entrance", -1, pos);
	}

	@Test
	public void findSpawnPos_skipsCellsAlreadyHoldingAMob() {
		// 7x7 all-floor, entrance at center 24 (x=3,y=3). buildFlagMaps forces the
		// level border solid, so only interior cells are passable — the radius-2
		// ring around a centred entrance is interior (unlike a 5x5 where radius-2
		// coincides with the border). Occupy the radius-1 ring and assert we still
		// find a free radius-2 cell (proves occupied cells are skipped, not stuck).
		Level lvl = newStubLevel(7, 7, /*entrance=*/24, Terrain.EMPTY);
		// radius-1 ring around (3,3): 16,17,18,23,25,30,31,32
		int[] ring1 = {16, 17, 18, 23, 25, 30, 31, 32};
		for (int p : ring1) {
			Mob m = new LuaNpc(baseNpcTable("blocker_" + p));
			m.pos = p;
			lvl.mobs.add(m);
		}
		int pos = LuaLevelService.findSpawnPosNearEntrance(lvl);
		assertTrue("should find a free radius-2 cell, not -1", pos >= 0);
		assertNull("chosen cell must be unoccupied", lvl.findMob(pos));
		assertTrue("chosen cell must be passable", lvl.passable[pos]);
	}

	// ---- injectLevelNpcs isDebug guard ----

	@Test
	public void injectLevelNpcs_respectsIsDebugGuard() {
		LuaNpcRegistry.register("town_portal", baseNpcTable("town_portal"));
		Dungeon.depth = 1;
		Level lvl = newStubLevel(5, 5, 12, Terrain.EMPTY);

		// Release build: isDebug()==false → no spawn
		Game.version = "1.0.0-release";
		LuaLevelService.injectLevelNpcs(lvl);
		assertEquals("release build must not spawn", 0, lvl.mobs.size());

		// Debug build: isDebug()==true → spawn
		Game.version = "INDEV";
		LuaLevelService.injectLevelNpcs(lvl);
		assertEquals("debug build + depth 1 → one spawn", 1, lvl.mobs.size());
	}

	// ---- injectLevelNpcs skips DataDrivenLevel ----

	@Test
	public void injectLevelNpcs_skipsDataDrivenLevel() {
		LuaNpcRegistry.register("town_portal", baseNpcTable("town_portal"));
		Dungeon.depth = 1;
		Game.version = "INDEV";  // debug ON so the isDebug guard would otherwise pass

		DataDrivenLevel lvl = DataDrivenLevel.fromJsonValue(sampleSafezoneJson(), "test_safezone");
		lvl.build();
		initDataDrivenCollections(lvl);
		int before = lvl.mobs.size();
		LuaLevelService.injectLevelNpcs(lvl);
		assertEquals("DataDrivenLevel must be skipped by injectLevelNpcs (its own createMobs owns mobs)",
				before, lvl.mobs.size());
	}

	// ---- R4 state-preservation seam ----

	@Test
	public void liveHeroState_captureAndApply_preservesHeroGoldQuickslotNextId() {
		// Setup live state: a distinct hero, gold=100, a quickslot instance, and a
		// nextID we can distinguish from the post-overwrite value.
		Hero liveHero = new Hero();
		Dungeon.hero = liveHero;
		Dungeon.gold = 100;
		QuickSlot liveQS = new QuickSlot();
		Dungeon.quickslot = liveQS;
		// Bump Actor.nextID past 1 so we can detect overwrite. Actor.resetNextID()
		// sets it to 1; we instead store the live value and assert restore.
		Bundle liveNextId = new Bundle();
		Actor.storeNextID(liveNextId);

		// Capture.
		LuaLevelService.LiveHeroState state = LuaLevelService.captureLiveState();
		assertSame("capture must snapshot the live hero reference", liveHero, state.hero);
		assertEquals("capture must snapshot gold", 100, state.gold);
		assertSame("capture must snapshot the live quickslot", liveQS, state.quickslot);

		// Simulate what Dungeon.loadGame does to these four globals: replace hero
		// with a throwaway, zero gold, swap quickslot, reset nextID.
		Hero throwawayHero = new Hero();
		Dungeon.hero = throwawayHero;
		Dungeon.gold = 0;
		Dungeon.quickslot = new QuickSlot();
		Actor.resetNextID();  // nextID → 1

		// Apply live state back.
		LuaLevelService.applyLiveState(state);

		assertSame("apply must restore the SAME hero object (preserves belongings/HP/buffs)",
				liveHero, Dungeon.hero);
		assertNotEquals("hero must not be the throwaway", throwawayHero, Dungeon.hero);
		assertEquals("apply must restore gold", 100, Dungeon.gold);
		assertSame("apply must restore the live quickslot (slots point at live items)",
				liveQS, Dungeon.quickslot);

		Bundle after = new Bundle();
		Actor.storeNextID(after);
		assertEquals("apply must restore nextID to the live (pre-overwrite) value",
				liveNextId.getInt("nextid"), after.getInt("nextid"));
	}

	// ---- shipped Lua scripts register via engine init ----

	@Test
	public void shippedTownScriptsRegisterViaEngineInit() {
		// Verifies town_portal.lua + town_return.lua parse and register through the
		// same LuaEngine.loadNpcScripts pipeline that the desktop run uses. Catches
		// syntax/registration errors in the shipped scripts that injectLevelNpcs
		// depends on (the spawnForDepth lookup would otherwise silently no-op).
		LuaEngine.resetForTests();
		LuaEngine.init();
		assertTrue("town_portal.lua must register via LuaEngine.init",
				LuaNpcRegistry.contains("town_portal"));
		assertTrue("town_return.lua must register via LuaEngine.init",
				LuaNpcRegistry.contains("town_return"));
		// And the registered town_portal spawns a real LuaNpc (the exact instance
		// spawnForDepth would put on a main-line level).
		assertNotNull(LuaNpcRegistry.create("town_portal"));
	}

	// ---- helpers ----

	/** Minimal concrete Level for inject tests (NOT a DataDrivenLevel). */
	private static final class StubLevel extends Level {
		int entranceCell;
		@Override protected boolean build() { return true; }
		@Override protected void createMobs() { }
		@Override protected void createItems() { }
		@Override public int entrance() { return entranceCell; }
		@Override public String tilesTex() { return null; }
		@Override public String waterTex() { return null; }
	}

	/** Build a stub level filled with one terrain, then derive flag maps. */
	private static Level newStubLevel(int w, int h, int entranceCell, int fillTerrain) {
		StubLevel lvl = new StubLevel();
		lvl.setSize(w, h);
		lvl.entranceCell = entranceCell;
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

	private static int chebyshev(Level lvl, int a, int b) {
		int ax = a % lvl.width(), ay = a / lvl.width();
		int bx = b % lvl.width(), by = b / lvl.width();
		return Math.max(Math.abs(ax - bx), Math.abs(ay - by));
	}

	private static org.luaj.vm2.LuaTable baseNpcTable(String id) {
		org.luaj.vm2.LuaTable tbl = new org.luaj.vm2.LuaTable();
		tbl.set("id", org.luaj.vm2.LuaValue.valueOf(id));
		tbl.set("name", org.luaj.vm2.LuaValue.valueOf(id));
		return tbl;
	}

	private static void initDataDrivenCollections(DataDrivenLevel lvl) {
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

	private static com.badlogic.gdx.utils.JsonValue sampleSafezoneJson() {
		// 5x5 with a floor interior + wall border + entrance at center. SafeZone-
		// style; the actual contents don't matter for the skip-inject assertion.
		StringBuilder tiles = new StringBuilder();
		for (int y = 0; y < 5; y++) {
			for (int x = 0; x < 5; x++) {
				if (tiles.length() > 0) tiles.append(',');
				int pos = x + y * 5;
				if (pos == 12) tiles.append("'entrance'");
				else if (x == 0 || x == 4 || y == 0 || y == 4) tiles.append("'wall'");
				else tiles.append("'floor'");
			}
		}
		String json = ("{'id':'t','name':'t','width':5,'height':5,'entrance':12,'safe':true,"
				+ "'tiles':[" + tiles + "],"
				+ "'mobs':[]"
				+ "}").replace('\'', '"');
		return new com.badlogic.gdx.utils.JsonReader().parse(json);
	}
}
