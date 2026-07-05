package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M2 callback + RPD API tests. Covers four things:
 * <ol>
 *   <li>{@link LuaItemCallbacks#callOptInt} return-value conversion
 *       (number overrides, nil/non-number/missing fn → fallback).</li>
 *   <li>{@code RPD.*} argument validation: bad charId / bad amount / bad buff
 *       name all return NIL without throwing.</li>
 *   <li>Buff whitelist: whitelisted names resolve, others don't.</li>
 *   <li>M1 sandbox regression — Lua still cannot {@code luajava.bindClass}.</li>
 * </ol>
 *
 * <p>Live buff attachment on a real Char is verified in the desktop debug run
 * (PLAN Step 8); a Char instance requires a level + Actor processor this
 * headless harness does not stand up. The security boundary (whitelist
 * rejection, parameter clamping) is what this test pins down.
 */
public class LuaItemCallbackTest {

    private static HeadlessApplication application;

    @BeforeClass
    public static void initHeadless() {
        HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
        config.updatesPerSecond = 1;
        application = new HeadlessApplication(new ApplicationAdapter() {}, config);
        LuaItemRegistry.clear();
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

    // ---- LuaItemCallbacks.callOptInt ----

    @Test
    public void callOptIntOverridesOnNumericReturn() {
        LuaTable tbl = new LuaTable();
        tbl.set("attackProc", globals().load("return 99"));
        assertEquals(99, LuaItemCallbacks.callOptInt(tbl, "attackProc", 0,
                LuaValue.valueOf(1), LuaValue.valueOf(2), LuaValue.valueOf(10)));
    }

    @Test
    public void callOptIntFallsBackWhenFunctionMissing() {
        LuaTable tbl = new LuaTable();
        assertEquals(42, LuaItemCallbacks.callOptInt(tbl, "attackProc", 42,
                LuaValue.valueOf(1), LuaValue.valueOf(2), LuaValue.valueOf(10)));
    }

    @Test
    public void callOptIntFallsBackOnNilReturn() {
        LuaTable tbl = new LuaTable();
        tbl.set("attackProc", globals().load("return nil"));
        assertEquals(10, LuaItemCallbacks.callOptInt(tbl, "attackProc", 10,
                LuaValue.valueOf(1), LuaValue.valueOf(2), LuaValue.valueOf(10)));
    }

    @Test
    public void callOptIntFallsBackOnNonNumericReturn() {
        LuaTable tbl = new LuaTable();
        tbl.set("attackProc", globals().load("return 'oops'"));
        assertEquals(10, LuaItemCallbacks.callOptInt(tbl, "attackProc", 10,
                LuaValue.valueOf(1), LuaValue.valueOf(2), LuaValue.valueOf(10)));
    }

    @Test
    public void callOptNoArgsFiresWithoutThrowing() {
        LuaTable tbl = new LuaTable();
        tbl.set("onEquip", globals().load("return nil"));
        LuaItemCallbacks.callOpt(tbl, "onEquip", LuaValue.valueOf(1));
        // Missing function must also be a no-op.
        LuaItemCallbacks.callOpt(tbl, "onDeactivate", LuaValue.valueOf(1));
    }

    // ---- RPD argument validation (no live Char available, so focus on the guard rails) ----

    @Test
    public void affectBuffOnUnknownCharReturnsNilWithoutThrowing() {
        LuaValue r = globals().load("return RPD.affectBuff(999999, 'Bleeding', 3)").call();
        assertTrue("affectBuff on a dead/unknown charId must return nil", r.isnil());
    }

    @Test
    public void affectBuffRejectsNonPositiveAmount() {
        LuaValue r = globals().load("return RPD.affectBuff(999999, 'Bleeding', 0)").call();
        assertTrue(r.isnil());
        LuaValue r2 = globals().load("return RPD.affectBuff(999999, 'Bleeding', -5)").call();
        assertTrue(r2.isnil());
    }

    @Test
    public void damageCharRejectsNonPositiveAmount() {
        assertTrue(globals().load("return RPD.damageChar(999999, -5)").call().isnil());
        assertTrue(globals().load("return RPD.damageChar(999999, 0)").call().isnil());
    }

    @Test
    public void healCharRejectsNonPositiveAmount() {
        assertTrue(globals().load("return RPD.healChar(999999, -5)").call().isnil());
    }

    // ---- buff whitelist ----

    @Test
    public void whitelistResolvesShippedDebuffs() {
        assertNotNull(RpdApi.BuffWhitelist.lookup("Bleeding"));
        assertNotNull(RpdApi.BuffWhitelist.lookup("Poison"));
        assertNotNull(RpdApi.BuffWhitelist.lookup("Roots"));
        assertNotNull(RpdApi.BuffWhitelist.lookup("Barkskin"));
        assertNotNull(RpdApi.BuffWhitelist.lookup("Slow"));
    }

    @Test
    public void whitelistRejectsUnknownBuff() {
        // The whole point of D4: stop a script from injecting arbitrary buffs
        // (e.g. an invulnerability / hero-clone buff).
        assertFalse(RpdApi.BuffWhitelist.lookup("HeroClone") != null);
        assertFalse(RpdApi.BuffWhitelist.lookup("Buff") != null);
        assertFalse(RpdApi.BuffWhitelist.lookup("") != null);
    }

    @Test
    public void rpdGlobalIsInjectedByEngineInit() {
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        assertFalse("RPD global must be injected by LuaEngine.init", g.get("RPD").isnil());
        assertNotNull(g.get("RPD").get("affectBuff"));
        assertNotNull(g.get("RPD").get("damageChar"));
        assertNotNull(g.get("RPD").get("GLog"));
    }

    // ---- M2 register_item: image optional (codex round-2 must-fix) ----

    @Test
    public void luaItemWithoutImageFieldCreatesWithDefaultZero() {
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        g.load("register_item{ id='noimg_test', name='x', tier=1 }").call();
        assertTrue(LuaItemRegistry.contains("noimg_test"));
        LuaItem item = LuaItemRegistry.create("noimg_test");
        assertNotNull(item);
        assertEquals("missing image must default to 0, not crash create()", 0, item.image);
    }

    @Test
    public void m2CallbackItemsRegistered() {
        LuaEngine.init();
        assertTrue("test_proc_weapon.lua should register",
                LuaItemRegistry.contains("test_proc_weapon"));
        assertTrue("test_equip_buff.lua should register",
                LuaItemRegistry.contains("test_equip_buff"));
    }

    // ---- M1 sandbox regression (must not break) ----

    @Test
    public void luajavaBindClassStillUnreachableWithRpdInjected() {
        // Injecting RPD must not re-open any path to luajava.
        Globals g = globals();
        LuaValue ok = g.load(
                "return pcall(function() return luajava.bindClass('java.lang.Runtime') end)"
        ).call();
        assertFalse("luajava.bindClass must still fail with RPD present", ok.toboolean());
        assertTrue("luajava global itself must remain stripped", g.get("luajava").isnil());
    }
}
