package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.watabou.utils.PathFinder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SafeZoneEnterTest {

	private static final String ASSET = "mods/levels/test_safezone.json";
	private static HeadlessApplication application;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
	}

	@AfterClass
	public static void shutdown() {
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	@Test
	public void safeZoneAssetHasCenteredPaddingAndRemappedEntities() {
		JsonValue root = new JsonReader().parse(Gdx.files.internal(ASSET).readString("UTF-8"));
		assertEquals(32, root.getInt("width"));
		assertEquals(32, root.getInt("height"));
		assertEquals(1024, root.require("tiles").size);
		assertEquals(297, root.getInt("entrance"));
		assertEquals(726, root.getInt("exit"));

		String[] tiles = tiles(root.require("tiles"));
		assertEquals("entrance", tiles[297]);
		assertEquals("exit", tiles[726]);
		assertEquals("wall", tiles[0]);
		assertEquals("wall", tiles[31]);
		assertEquals("wall", tiles[992]);
		assertEquals("wall", tiles[1023]);

		Map<String, Integer> mobs = mobPositions(root.get("mobs"));
		assertEquals(528, (int) mobs.get("rat_king"));
		assertEquals(462, (int) mobs.get("lua_npc:test_npc"));
		assertEquals(496, (int) mobs.get("lua_shop:test_shop"));
		assertEquals(298, (int) mobs.get("lua_npc:town_return"));
		assertEquals("floor", tiles[528]);
		assertEquals("floor", tiles[462]);
		assertEquals("floor", tiles[496]);
		assertEquals("floor", tiles[298]);

		JsonValue gold = root.require("items").child;
		assertEquals("gold", gold.getString("type"));
		assertEquals(434, gold.getInt("pos"));
		assertEquals("floor", tiles[434]);
	}

	@Test
	public void safeZoneAssetCreatesWithBoundedObserveNeighborhoods() {
		DataDrivenLevel level = DataDrivenLevel.fromAsset(ASSET, "test_safezone");
		assertNotNull(level);
		level.create();

		assertEquals(32, level.width());
		assertEquals(32, level.height());
		assertEquals(1024, level.length());
		assertEquals(297, level.entrance());
		assertEquals(Terrain.ENTRANCE, level.map[297]);
		assertEquals(Terrain.EXIT, level.map[726]);
		assertTrue(level.passable[297]);

		for (int pos : new int[]{297, 298, 462, 496, 528, 726}) {
			assertInCenteredSafeZone(pos);
			assertNeighborhoodInBounds(level, pos);
		}

		for (int y = 8; y <= 23; y++) {
			for (int x = 8; x <= 23; x++) {
				assertNeighborhoodInBounds(level, x + y * 32);
			}
		}
	}

	private static String[] tiles(JsonValue arr) {
		String[] result = new String[arr.size];
		int i = 0;
		for (JsonValue t = arr.child; t != null; t = t.next) {
			result[i++] = t.asString();
		}
		return result;
	}

	private static Map<String, Integer> mobPositions(JsonValue arr) {
		Map<String, Integer> result = new HashMap<>();
		for (JsonValue m = arr.child; m != null; m = m.next) {
			result.put(m.getString("type"), m.getInt("pos"));
		}
		return result;
	}

	private static void assertInCenteredSafeZone(int pos) {
		int x = pos % 32;
		int y = pos / 32;
		assertTrue("x in centered safe zone for " + pos, x >= 8 && x <= 23);
		assertTrue("y in centered safe zone for " + pos, y >= 8 && y <= 23);
	}

	private static void assertNeighborhoodInBounds(DataDrivenLevel level, int pos) {
		for (int offset : PathFinder.NEIGHBOURS9) {
			int n = pos + offset;
			assertTrue("neighbor in bounds: pos=" + pos + " offset=" + offset + " n=" + n,
					n >= 0 && n < level.length());
		}
	}
}
