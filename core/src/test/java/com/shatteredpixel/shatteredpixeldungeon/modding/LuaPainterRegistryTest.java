package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.watabou.noosa.Game;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M10b: {@link LuaPainterRegistry} contract + {@code register_painter} global
 * validation. Mirrors the headless harness of {@link LuaNpcTest}.
 */
public class LuaPainterRegistryTest {

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

	private static LuaTable baseTable(String id) {
		LuaTable tbl = new LuaTable();
		tbl.set("id", LuaValue.valueOf(id));
		return tbl;
	}

	@Test
	public void registerGetContainsSize() {
		assertFalse("empty registry has no painters", LuaPainterRegistry.hasAny());
		LuaPainterRegistry.register("ShopRoom", baseTable("ShopRoom"));
		assertTrue(LuaPainterRegistry.contains("ShopRoom"));
		assertNotNull(LuaPainterRegistry.getTable("ShopRoom"));
		assertTrue(LuaPainterRegistry.hasAny());
		assertEquals(1, LuaPainterRegistry.size());
	}

	@Test
	public void getTableReturnsNullForUnknown() {
		assertNull(LuaPainterRegistry.getTable("nope"));
		assertFalse(LuaPainterRegistry.contains("nope"));
	}

	@Test
	public void clearDropsAll() {
		LuaPainterRegistry.register("ShopRoom", baseTable("ShopRoom"));
		LuaPainterRegistry.clear();
		assertFalse(LuaPainterRegistry.hasAny());
		assertEquals(0, LuaPainterRegistry.size());
		assertNull(LuaPainterRegistry.getTable("ShopRoom"));
	}

	@Test
	public void registerRejectsNullAndEmpty() {
		LuaPainterRegistry.register(null, baseTable("x"));
		LuaPainterRegistry.register("", baseTable("x"));
		LuaPainterRegistry.register("x", null);
		assertFalse(LuaPainterRegistry.hasAny());
	}

	@Test
	public void register_painter_validatesAndRegisters() {
		Globals g = LuaSandbox.exposedGlobals();
		LuaEngine.installGlobalsForTests(g);

		// valid table -> registered
		LuaTable tbl = baseTable("ShopRoom");
		g.get("register_painter").call(tbl);
		assertTrue(LuaPainterRegistry.contains("ShopRoom"));

		// non-table -> rejected, registry unchanged
		g.get("register_painter").call(LuaValue.valueOf("not a table"));
		assertEquals(1, LuaPainterRegistry.size());

		// missing id -> rejected
		g.get("register_painter").call(new LuaTable());
		assertEquals(1, LuaPainterRegistry.size());
	}

	@Test
	public void shippedPainterAndTrapScriptsRegisterViaEngineInit() throws Exception {
		// Verifies demo_painter.lua + demo_trap.lua parse and register through the
		// same LuaEngine loader pipeline the desktop run uses (mods/test_mod/
		// scripts/painters|traps). Catches syntax/registration errors in the
		// shipped scripts that the build wrap / injectLevelTraps depend on.
		ModTestSupport.enableTestMod();
		LuaEngine.resetForTests();
		LuaEngine.init();
		assertTrue("demo_painter.lua must register 'ShopRoom' via LuaEngine.init",
				LuaPainterRegistry.contains("ShopRoom"));
		assertTrue("demo_trap.lua must register 'demo_trap' via LuaEngine.init",
				LuaTrapRegistry.contains("demo_trap"));
	}
}
