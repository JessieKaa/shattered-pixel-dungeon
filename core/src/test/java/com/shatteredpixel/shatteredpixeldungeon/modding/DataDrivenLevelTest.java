package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.SparseArray;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * M4a {@link DataDrivenLevel} unit coverage. Verifies the three load-bearing pieces a
 * headless harness can reach without a full {@code Dungeon}/{@code GameScene}:
 * <ol>
 *   <li>tile-name → {@link Terrain} mapping (incl. {@code floor}→{@code EMPTY} alias and
 *       unknown→wall fallback).</li>
 *   <li>JSON parse → {@link DataDrivenLevel#build()} lays tiles correctly; entrance cell,
 *       width/height, mob/item spec counts come through.</li>
 *   <li>{@link Bundle} round-trip of {@code lua_level_id} + {@code entrance_cell} (R3).</li>
 * </ol>
 *
 * <p>Full {@code create()}/{@code switchLevel}/live {@code GameScene} render is verified by
 * the desktop debug run, not headlessly — it needs a Dungeon + scene this harness does not
 * stand up. {@code LuaLevelService} enter/leave is assertable for the CONTINUE wiring only.
 */
public class DataDrivenLevelTest {

	private static HeadlessApplication application;
	private static int prevVersionCode;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		// Level.restoreFromBundle rejects version < v2_5_4 (802) as "old save". The real app
		// sets Game.versionCode on startup; mirror that so the round-trip test can restore.
		prevVersionCode = Game.versionCode;
		Game.versionCode = 802;
	}

	@AfterClass
	public static void shutdown() {
		Game.versionCode = prevVersionCode;
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	// ---- tile name mapping ----

	@Test
	public void tileNameMappingCoversKeyTiles() {
		assertEquals(Terrain.WALL, DataDrivenLevel.tileNameToIdForTest("wall"));
		assertEquals(Terrain.EMPTY, DataDrivenLevel.tileNameToIdForTest("empty"));
		assertEquals("'floor' is the friendly alias for empty",
				Terrain.EMPTY, DataDrivenLevel.tileNameToIdForTest("floor"));
		assertEquals(Terrain.ENTRANCE, DataDrivenLevel.tileNameToIdForTest("entrance"));
		assertEquals(Terrain.EXIT, DataDrivenLevel.tileNameToIdForTest("exit"));
		assertEquals(Terrain.WATER, DataDrivenLevel.tileNameToIdForTest("water"));
		assertEquals(Terrain.DOOR, DataDrivenLevel.tileNameToIdForTest("door"));
		assertEquals(Terrain.HIGH_GRASS, DataDrivenLevel.tileNameToIdForTest("high_grass"));
	}

	@Test
	public void tileNameMappingIsCaseInsensitiveAndDefaultsToWall() {
		assertEquals(Terrain.WALL, DataDrivenLevel.tileNameToIdForTest("WALL"));
		assertEquals(Terrain.EMPTY, DataDrivenLevel.tileNameToIdForTest("FlOoR"));
		assertEquals("unknown names degrade to wall, never crash",
				Terrain.WALL, DataDrivenLevel.tileNameToIdForTest("totally_not_a_tile"));
		assertEquals(Terrain.WALL, DataDrivenLevel.tileNameToIdForTest(null));
	}

	// ---- JSON parse + build ----

	@Test
	public void jsonParsesAndBuildsTilesAndEntrance() {
		DataDrivenLevel lvl = DataDrivenLevel.fromJsonValue(sampleJson(), "test_safezone");
		assertNotNull(lvl);
		// width()/height()/length() come from Level fields set by setSize() inside build(),
		// so build first then assert geometry.
		lvl.build();
		assertEquals(16, lvl.width());
		assertEquals(16, lvl.height());
		assertEquals(256, lvl.length());
		assertEquals("entrance cell is the parsed JSON value, exposed via the override",
				17, lvl.entrance());

		assertEquals("entrance tile laid at entranceCell",
				Terrain.ENTRANCE, (int) lvl.map[17]);
		assertEquals("exit tile is visual-only at its cell", Terrain.EXIT, (int) lvl.map[238]);
		assertEquals("border is wall", Terrain.WALL, (int) lvl.map[0]);
		assertEquals("interior is floor/empty", Terrain.EMPTY, (int) lvl.map[1 + 2 * 16]);
	}

	@Test
	public void jsonRejectsTilesLengthMismatch() {
		// NB: assemble full string first, THEN replace — `"a" + "b".replace(...)` would
		// only replace on "b" (method call binds tighter than +).
		String bad = ("{'id':'x','name':'x','width':4,'height':4,'entrance':5,"
				+ "'tiles':['wall','wall','wall']}").replace('\'', '"');
		try {
			DataDrivenLevel.fromJsonValue(new JsonReader().parse(bad), "x");
			fail("expected IllegalArgumentException for tiles length mismatch");
		} catch (IllegalArgumentException ok) {
			assertTrue("message should name tiles: " + ok.getMessage(),
					ok.getMessage().contains("tiles"));
		}
	}

	@Test
	public void jsonRejectsEntranceOutOfBounds() {
		// 2x2 = 4 tiles, entrance=99 is OOB
		String bad = ("{'id':'x','name':'x','width':2,'height':2,'entrance':99,"
				+ "'tiles':['wall','wall','wall','wall']}").replace('\'', '"');
		try {
			DataDrivenLevel.fromJsonValue(new JsonReader().parse(bad), "x");
			fail("expected IllegalArgumentException for OOB entrance");
		} catch (IllegalArgumentException ok) {
			assertTrue("message should name entrance: " + ok.getMessage(),
					ok.getMessage().contains("entrance"));
		}
	}

	@Test
	public void registryHoldsRegisteredLevels() {
		LuaLevelRegistry.clear();
		assertEquals(0, LuaLevelRegistry.size());
		org.luaj.vm2.LuaTable tbl = new org.luaj.vm2.LuaTable();
		tbl.set("id", org.luaj.vm2.LuaValue.valueOf("sz"));
		tbl.set("name", org.luaj.vm2.LuaValue.valueOf("SafeZone"));
		LuaLevelRegistry.register("sz", tbl);
		assertTrue(LuaLevelRegistry.contains("sz"));
		assertNotNull(LuaLevelRegistry.getTable("sz"));
		LuaLevelRegistry.clear();
	}

	// ---- bundle round-trip (R3) ----

	@Test
	public void bundleRoundTripsEntranceAndId() {
		DataDrivenLevel src = DataDrivenLevel.fromJsonValue(sampleJson(), "test_safezone");
		src.build();
		// build() allocates map/flag arrays via setSize but not the actor collections
		// (Level.create() does). Initialise them so super.storeInBundle doesn't NPE.
		src.mobs = new HashSet<>();
		src.heaps = new SparseArray<>();
		src.blobs = new HashMap<>();
		src.plants = new SparseArray<>();
		src.traps = new SparseArray<>();
		src.customTiles = new ArrayList<>();
		src.customWalls = new ArrayList<>();
		src.visited = new boolean[src.length()];
		src.mapped = new boolean[src.length()];
		src.transitions = new ArrayList<>();

		Bundle b = new Bundle();
		src.storeInBundle(b);

		DataDrivenLevel restored = new DataDrivenLevel();
		restored.restoreFromBundle(b);

		assertEquals("entrance cell survives the round-trip", 17, restored.entrance());
		assertEquals("lua_level_id survives the round-trip",
				"test_safezone", restored.luaLevelId());
		assertEquals("entrance tile survives the round-trip",
				Terrain.ENTRANCE, (int) restored.map[17]);
		assertEquals("width survives", 16, restored.width());
	}

	// ---- Lua mobs/items spawn from JSON prefixes ----

	@Test
	public void luaMobSpawnsFromPrefix() {
		LuaMobRegistry.clear();
		LuaMobRegistry.register("dd_test_mob", mobTable("dd_test_mob"));

		String json = "{"
				+ "'id':'test_mob_spawn','name':'Mob Spawn',"
				+ "'width':16,'height':16,'tiles':[" + tiles16x16() + "],"
				+ "'entrance':17,'exit':238,'safe':true,"
				+ "'mobs':[{'type':'lua_mob:dd_test_mob','pos':50}]"
				+ "}";
		DataDrivenLevel lvl = DataDrivenLevel.fromJsonValue(
				new JsonReader().parse(json.replace('\'', '"')), "test_mob_spawn");
		lvl.create();

		assertEquals("lua_mob spec becomes one mob", 1, lvl.mobs.size());
		Object[] mobs = lvl.mobs.toArray();
		assertTrue("spawned mob is a LuaMob", mobs[0] instanceof LuaMob);
		assertEquals("lua_mob placed at JSON pos", 50, ((LuaMob) mobs[0]).pos);

		LuaMobRegistry.clear();
	}

	@Test
	public void luaMobUnknownIdIsSkipped() {
		String json = "{"
				+ "'id':'test_mob_skip','name':'Mob Skip',"
				+ "'width':16,'height':16,'tiles':[" + tiles16x16() + "],"
				+ "'entrance':17,'exit':238,'safe':true,"
				+ "'mobs':[{'type':'lua_mob:does_not_exist','pos':50}]"
				+ "}";
		DataDrivenLevel lvl = DataDrivenLevel.fromJsonValue(
				new JsonReader().parse(json.replace('\'', '"')), "test_mob_skip");
		lvl.create();
		assertEquals("unknown lua_mob id is skipped", 0, lvl.mobs.size());
	}

	@Test
	public void luaItemSpawnsFromPrefix() {
		LuaItemRegistry.clear();
		LuaItemRegistry.register("dd_test_item", itemTable("dd_test_item"));

		String json = "{"
				+ "'id':'test_item_spawn','name':'Item Spawn',"
				+ "'width':16,'height':16,'tiles':[" + tiles16x16() + "],"
				+ "'entrance':17,'exit':238,'safe':true,"
				+ "'items':[{'type':'lua_item:dd_test_item','pos':90}]"
				+ "}";
		DataDrivenLevel lvl = DataDrivenLevel.fromJsonValue(
				new JsonReader().parse(json.replace('\'', '"')), "test_item_spawn");
		lvl.create();

		assertNotNull("lua_item spec creates a heap at the JSON pos", lvl.heaps.get(90));
		assertEquals("heap contains the single lua item", 1, lvl.heaps.get(90).size());
		assertTrue("spawned item is a LuaItem", lvl.heaps.get(90).peek() instanceof LuaItem);

		LuaItemRegistry.clear();
	}

	@Test
	public void luaItemUnknownIdIsSkipped() {
		String json = "{"
				+ "'id':'test_item_skip','name':'Item Skip',"
				+ "'width':16,'height':16,'tiles':[" + tiles16x16() + "],"
				+ "'entrance':17,'exit':238,'safe':true,"
				+ "'items':[{'type':'lua_item:does_not_exist','pos':90}]"
				+ "}";
		DataDrivenLevel lvl = DataDrivenLevel.fromJsonValue(
				new JsonReader().parse(json.replace('\'', '"')), "test_item_skip");
		lvl.create();
		assertEquals("unknown lua_item id is skipped", null, lvl.heaps.get(90));
	}

	private static org.luaj.vm2.LuaTable mobTable(String id) {
		org.luaj.vm2.LuaTable tbl = new org.luaj.vm2.LuaTable();
		tbl.set("id", org.luaj.vm2.LuaValue.valueOf(id));
		tbl.set("name", org.luaj.vm2.LuaValue.valueOf("test mob"));
		tbl.set("hp", org.luaj.vm2.LuaValue.valueOf(10));
		tbl.set("attack", org.luaj.vm2.LuaValue.valueOf(3));
		tbl.set("defense", org.luaj.vm2.LuaValue.valueOf(2));
		return tbl;
	}

	private static org.luaj.vm2.LuaTable itemTable(String id) {
		org.luaj.vm2.LuaTable tbl = new org.luaj.vm2.LuaTable();
		tbl.set("id", org.luaj.vm2.LuaValue.valueOf(id));
		tbl.set("name", org.luaj.vm2.LuaValue.valueOf("test item"));
		tbl.set("desc", org.luaj.vm2.LuaValue.valueOf("for tests"));
		tbl.set("tier", org.luaj.vm2.LuaValue.valueOf(1));
		tbl.set("image", org.luaj.vm2.LuaValue.valueOf(0));
		tbl.set("type", org.luaj.vm2.LuaValue.valueOf("weapon"));
		return tbl;
	}

	// ---- LuaLevelService CONTINUE wiring ----

	@Test
	public void inDataLevelIsFalseWhenDungeonLevelNull() {
		// Dungeon.level is null in the headless harness, so the guard must read false.
		assertEquals(false, LuaLevelService.inDataLevel());
	}

	private static JsonValue sampleJson() {
		String json = "{"
				+ "'id':'test_safezone',"
				+ "'name':'Test SafeZone',"
				+ "'width':16,'height':16,"
				+ "'tiles':["
				+ tiles16x16()
				+ "],"
				+ "'entrance':17,'exit':238,'safe':true,"
				+ "'mobs':[{'type':'rat_king','pos':136}],"
				+ "'items':[{'type':'gold','pos':90,'quantity':50}]"
				+ "}";
		return new JsonReader().parse(json.replace('\'', '"'));
	}

	/** 256 tiles: wall border + floor interior + entrance at 17 + exit at 238. */
	private static String tiles16x16() {
		StringBuilder sb = new StringBuilder();
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				if (sb.length() > 0) sb.append(',');
				int pos = x + y * 16;
				if (pos == 17) sb.append("'entrance'");
				else if (pos == 238) sb.append("'exit'");
				else if (x == 0 || x == 15 || y == 0 || y == 15) sb.append("'wall'");
				else sb.append("'floor'");
			}
		}
		return sb.toString();
	}
}
