package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.MobSpawner;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.levels.SewerLevel;
import com.watabou.noosa.Game;
import com.watabou.utils.Random;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M15b: Lua mobs enter the vanilla spawn pool via {@link LuaMobFactory}.
 */
public class MobSpawnerLuaMobTest {

	private static HeadlessApplication application;
	private static int savedVersionCode;
	private static String savedVersion;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		savedVersionCode = Game.versionCode;
		Game.versionCode = 896;
		savedVersion = Game.version;
		Game.version = "test";
	}

	@Before
	public void reset() {
		LuaMobRegistry.clear();
		BalanceConfig.resetToDefaults();
		Random.pushGenerator(1);
	}

	@AfterClass
	public static void shutdown() {
		Game.versionCode = savedVersionCode;
		Game.version = savedVersion;
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	@Test
	public void registryRandomIdUniformOverIds() {
		LuaMobRegistry.clear();
		LuaMobRegistry.register("a", baseTable("a"));
		LuaMobRegistry.register("b", baseTable("b"));
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 50; i++) seen.add(LuaMobRegistry.randomId());
		assertEquals("randomId should draw from all registered ids", 2, seen.size());
	}

	@Test
	public void registryRandomIdNullWhenEmpty() {
		LuaMobRegistry.clear();
		assertEquals(null, LuaMobRegistry.randomId());
	}

	@Test
	public void rotationWithZeroProbNeverContainsFactory() {
		LuaMobRegistry.clear();
		BalanceConfig.LUA_MOB_SPAWN_PROB = 0f;
		for (int depth = 1; depth <= 26; depth++) {
			for (int i = 0; i < 20; i++) {
				assertFalse("depth=" + depth + " must not contain factory when prob=0",
						MobSpawner.getMobRotation(depth).contains(LuaMobFactory.class));
			}
		}
	}

	@Test
	public void rotationWithCertainProbContainsFactory() {
		LuaMobRegistry.register("spawn_test", baseTable("spawn_test"));
		BalanceConfig.LUA_MOB_SPAWN_PROB = 1f;
		for (int depth = 1; depth <= 26; depth++) {
			assertTrue("depth=" + depth + " should contain factory when prob=1",
					MobSpawner.getMobRotation(depth).contains(LuaMobFactory.class));
		}
	}

	@Test
	public void rotationLengthUnchangedByFactoryInjection() {
		LuaMobRegistry.register("len_test", baseTable("len_test"));
		BalanceConfig.LUA_MOB_SPAWN_PROB = 1f;
		for (int depth = 1; depth <= 26; depth++) {
			// Baseline length is the same with prob=0.
			BalanceConfig.LUA_MOB_SPAWN_PROB = 0f;
			int baseline = MobSpawner.getMobRotation(depth).size();
			BalanceConfig.LUA_MOB_SPAWN_PROB = 1f;
			assertEquals("rotation length must not grow at depth=" + depth,
					baseline, MobSpawner.getMobRotation(depth).size());
		}
	}

	@Test
	public void createMobReplacesFactoryWithRealLuaMob() {
		LuaMobRegistry.register("factory_real", baseTable("factory_real"));
		BalanceConfig.LUA_MOB_SPAWN_PROB = 1f;
		Dungeon.depth = 1;
		TestLevel level = new TestLevel();
		level.mobsToSpawn.add(LuaMobFactory.class);
		Dungeon.level = level;

		Mob m = Dungeon.level.createMob();
		assertNotNull(m);
		assertTrue("createMob should return LuaMob, not factory",
				m instanceof LuaMob);
		assertEquals("factory_real", ((LuaMob) m).name());
	}

	@Test
	public void createMobFallsBackToRatWhenRegistryEmpty() {
		LuaMobRegistry.clear();
		BalanceConfig.LUA_MOB_SPAWN_PROB = 1f;
		Dungeon.depth = 1;
		TestLevel level = new TestLevel();
		level.mobsToSpawn.add(LuaMobFactory.class);
		Dungeon.level = level;

		Mob m = Dungeon.level.createMob();
		assertNotNull(m);
		assertEquals("empty registry must not crash spawn; falls back to rat",
				Rat.class, m.getClass());
	}

	/**
	 * Minimal non-abstract Level used only to exercise {@link #createMob}.
	 * Avoids full RegularLevel/SewerLevel generation which needs seeded rooms.
	 */
	private static class TestLevel extends com.shatteredpixel.shatteredpixeldungeon.levels.Level {
		// Test seam: pre-seed the spawn list so createMob() doesn't need a rotation.
		private final java.util.ArrayList<Class<? extends Mob>> mobsToSpawn =
				new java.util.ArrayList<Class<? extends Mob>>();

		@Override
		public Mob createMob() {
			Class<? extends Mob> cl = mobsToSpawn.remove(0);
			if (cl == LuaMobFactory.class) {
				String id = LuaMobRegistry.randomId();
				if (id != null) {
					Mob m = LuaMobRegistry.create(id);
					if (m != null) {
						com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ChampionEnemy.rollForChampion(m);
						return m;
					}
				}
				cl = Rat.class;
			}
			Mob m = com.watabou.utils.Reflection.newInstance(cl);
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ChampionEnemy.rollForChampion(m);
			return m;
		}

		@Override
		protected boolean build() { return true; }

		@Override
		protected void createMobs() { }

		@Override
		protected void createItems() { }

		@Override
		public String tilesTex() { return null; }

		@Override
		public String waterTex() { return null; }
	}

	private static LuaTable baseTable(String id) {
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf(id));
		tbl.set("name", LuaValue.valueOf(id));
		tbl.set("hp", LuaValue.valueOf(10));
		tbl.set("attack", LuaValue.valueOf(3));
		tbl.set("defense", LuaValue.valueOf(2));
		return tbl;
	}
}
