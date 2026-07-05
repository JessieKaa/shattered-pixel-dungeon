package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Headless verification of the M0 Lua→Java pipeline: {@link LuaEngine#init()}
 * must bootstrap luaj, run {@code scripts/init.lua}, which in turn dofiles
 * {@code scripts/items/test_sword.lua} and registers a weapon via the
 * {@code register_item} global. This is the option-C fallback acceptance from
 * the PLAN — it does not touch the in-game UI, only the registration chain.
 *
 * <p>Assets are on the test classpath via {@code core/build.gradle}'s
 * {@code sourceSets.test.resources.srcDirs}, so {@code Gdx.files.internal}
 * resolves under the headless backend.
 */
public class LuaEngineTest {

	private static HeadlessApplication application;

	@BeforeClass
	public static void initHeadless() {
		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = 1;
		application = new HeadlessApplication(new ApplicationAdapter() {}, config);
		LuaItemRegistry.clear();
	}

	@AfterClass
	public static void shutdown() {
		try { if (application != null) application.exit(); } catch (Throwable ignored) { }
	}

	@Test
	public void initRegistersTestSwordFromAssets() {
		LuaEngine.init();

		assertTrue("test_sword should be registered after LuaEngine.init()",
				LuaItemRegistry.contains("test_sword"));

		LuaItem item = LuaItemRegistry.create("test_sword");
		assertNotNull("create(test_sword) must not return null", item);
		assertEquals("测试剑 (Lua)", item.name());
		assertEquals(2, item.tier);
		assertEquals(104, item.image);
		// MeleeWeapon tier=2 formula: min(lvl)=tier+lvl, max(lvl)=5*(tier+1)+lvl*(tier+1)
		assertEquals(2, item.min(0));
		assertEquals(15, item.max(0));
		assertEquals("测试剑 (Lua) desc should come from Lua",
				"一把由 Lua 脚本定义的剑。这是 SPD Lua modding 平台的 M0 可行性验证物品 —— 攻击力/力量需求由 tier=2 推导，与原版短剑相同。",
				item.desc());
	}
}
