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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M18a {@code RPD.chooseOption} tests.
 *
 * <p>What is pinned down here:
 * <ol>
 *   <li>{@code RPD.chooseOption} is registered on the RPD global.</li>
 *   <li>Bad {@code charId} (non-int / unknown) is rejected with NIL, no throw —
 *       same shape as the {@code showDialog}/{@code npcYell} rejection tests.</li>
 *   <li>{@link RpdApi#parseOptionsTable} seam: valid array table → String[]
 *       (order preserved); non-string element / non-table / empty → null.</li>
 *   <li>{@link RpdApi#dispatchChoice} seam: 0-based select index → 1-based Lua
 *       callback arg; dismiss (-1) and missing/non-function callback → no-op.</li>
 * </ol>
 *
 * <p>The live WndOptions render ({@code GameScene.show} on the render thread) is
 * verified by code review + the desktop run, not headlessly — same split as
 * {@code LuaNpcTest} / {@code LuaShopTest} (PLAN risk: no scene graph headless).
 */
public class ChooseOptionTest {

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

    @Before
    public void resetModAndLuaState() throws Exception {
        ModTestSupport.enableTestMod();
        ModTestSupport.resetLuaState();
    }

    @AfterClass
    public static void shutdown() {
        Game.versionCode = savedVersionCode;
        try { if (application != null) application.exit(); } catch (Throwable ignored) { }
    }

    private Globals globals() {
        Globals g = LuaSandbox.exposedGlobals();
        g.set("RPD", RpdApi.build());
        return g;
    }

    // ---- registration ----

    @Test
    public void chooseOptionExposedOnRpdGlobal() {
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        assertFalse("RPD global must be present", g.get("RPD").isnil());
        assertNotNull("RPD.chooseOption must be wired", g.get("RPD").get("chooseOption"));
        assertFalse("chooseOption must be callable (a function), not nil",
                g.get("RPD").get("chooseOption").isnil());
    }

    // ---- bad-arg rejection (no throw) ----

    @Test
    public void chooseOptionRejectsBadCharIdWithoutThrowing() {
        Globals g = globals();
        assertTrue("non-int charId → nil",
                g.load("return RPD.chooseOption('x','t',{'a'},function() end)").call().isnil());
        assertTrue("unknown charId → nil",
                g.load("return RPD.chooseOption(99999,'t',{'a'},function() end)").call().isnil());
    }

    @Test
    public void chooseOptionRejectsMissingCallbackWithoutThrowing() {
        // charId valid shape but unknown → nil before callback is even consulted;
        // this asserts the missing-callback path is not reached with a crash.
        Globals g = globals();
        assertTrue("missing callback arg → nil, no throw",
                g.load("return RPD.chooseOption(99999,'t',{'a'})").call().isnil());
    }

    // ---- parseOptionsTable seam ----

    @Test
    public void parseOptionsTableValidStringArray() {
        Globals g = globals();
        LuaTable t = (LuaTable) g.load("return {'买酒','聊天','离开'}").call();
        assertArrayEquals("1-indexed Lua array → 0-indexed Java, order preserved",
                new String[]{"买酒", "聊天", "离开"}, RpdApi.parseOptionsTable(t));
    }

    @Test
    public void parseOptionsTableRejectsNonStringElement() {
        Globals g = globals();
        LuaValue t = g.load("return {1,'b'}").call();
        assertNull("non-string element → null", RpdApi.parseOptionsTable(t));
    }

    @Test
    public void parseOptionsTableRejectsNonTableAndEmpty() {
        assertNull("non-table (string) → null",
                RpdApi.parseOptionsTable(LuaValue.valueOf("not a table")));
        assertNull("nil → null", RpdApi.parseOptionsTable(LuaValue.NIL));
        Globals g = globals();
        LuaValue empty = g.load("return {}").call();
        assertNull("empty table → null", RpdApi.parseOptionsTable(empty));
    }

    // ---- dispatchChoice seam ----

    @Test
    public void dispatchChoiceInvokesCallbackOneBased() {
        Globals g = globals();
        g.load("_captured = nil; function _cb(idx) _captured = idx end").call();
        LuaValue cb = g.get("_cb");

        assertTrue("selectedIndex 0 → callback fires, returns true", RpdApi.dispatchChoice(cb, 0));
        assertEquals("callback receives 1-based index", 1, g.get("_captured").toint());

        assertTrue("selectedIndex 2 → callback fires", RpdApi.dispatchChoice(cb, 2));
        assertEquals("callback receives 1-based index (3)", 3, g.get("_captured").toint());
    }

    @Test
    public void dispatchChoiceNoOpOnDismissOrMissingCallback() {
        Globals g = globals();
        g.load("_captured = nil; function _cb(idx) _captured = idx end").call();
        LuaValue cb = g.get("_cb");

        assertFalse("dismiss (-1) must not fire callback", RpdApi.dispatchChoice(cb, -1));
        assertNull("_captured stays nil on dismiss", g.get("_captured").optjstring(null));

        assertFalse("nil callback → false", RpdApi.dispatchChoice(LuaValue.NIL, 0));
        assertFalse("non-function callback → false",
                RpdApi.dispatchChoice(LuaValue.valueOf("str"), 0));
    }
}
