package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.watabou.noosa.Game;
import com.watabou.utils.GameSettings;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Globals;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M20b: the {@code remixed_full} ally content. Pins three contracts for the two
 * new Lua allies shipped under {@code scripts/allies/}:
 *
 * <ol>
 *   <li><b>both allies register</b> when remixed_full is enabled — the M3b loader
 *       auto-scans {@code scripts/allies/*.lua}, so {@link LuaAllyRegistry}
 *       must contain {@code remixed_full_guard_pup} and {@code remixed_full_healing_wisp}.</li>
 *   <li><b>both allies construct</b> from their Lua tables — {@link LuaAllyRegistry#create}
 *       parses id/name/attack/defense/sprite + hp/ht without throwing, so a
 *       {@code spawnAlly} at runtime will yield a live {@link LuaAlly}.</li>
 *   <li><b>RPD.heroId() is wired and nil-safe</b> — the M20b primitive (decision A)
 *       that lets a support ally's {@code act(selfId)} reference the hero. In
 *       headless there is no hero, so it must return {@code nil} (not throw NPE),
 *       which is exactly the guard the dispatcher required.</li>
 * </ol>
 *
 * <p>Does not count allies (that is {@code RemixedFullPackTest}'s M20g job) and
 * does not touch {@code entry.lua}.
 */
public class RemixedFullAllyContentTest {

    private static final int TEST_VERSION_CODE = 896;
    private static HeadlessApplication application;
    private static int savedVersionCode;
    private static String savedVersion;

    @BeforeClass
    public static void initHeadless() {
        HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
        config.updatesPerSecond = 1;
        application = new HeadlessApplication(new ApplicationAdapter() {}, config);
        savedVersionCode = Game.versionCode;
        savedVersion = Game.version;
        Game.versionCode = TEST_VERSION_CODE;
        Game.version = "test";
    }

    @AfterClass
    public static void shutdown() {
        Game.versionCode = savedVersionCode;
        Game.version = savedVersion;
        try { if (application != null) application.exit(); } catch (Throwable ignored) {}
    }

    @Before
    public void resetState() throws Exception {
        GameSettings.set(new ModTestSupport.FakePreferences());
        ModRegistry.resetForTests();
        ModTestSupport.resetLuaState();
    }

    private void enableRemixedFull() throws Exception {
        ModRegistry.scanDir(ModTestSupport.realModsHandle());
        ModRegistry.setEnabled("remixed_full", true);
        // Keep the exact-id assertions free of pollution from other mods.
        ModRegistry.setEnabled("remished_lite", false);
        ModRegistry.setEnabled("test_mod", false);
        ModRegistry.setEnabled("regression_demo", false);
    }

    @Test
    public void enabled_remixedFullRegistersBothAllies() throws Exception {
        enableRemixedFull();
        assertTrue("remixed_full must be enabled", ModRegistry.isEnabled("remixed_full"));

        LuaEngine.init();

        assertTrue("guard_pup must register via the M3b allies loader",
                LuaAllyRegistry.contains("remixed_full_guard_pup"));
        assertTrue("healing_wisp must register via the M3b allies loader",
                LuaAllyRegistry.contains("remixed_full_healing_wisp"));
    }

    @Test
    public void bothAlliesConstructFromTheirLuaTables() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        assertNotNull("guard_pup must build a LuaAlly (hp/ht/name/sprite parse)",
                LuaAllyRegistry.create("remixed_full_guard_pup"));
        assertNotNull("healing_wisp must build a LuaAlly (hp/ht/name/sprite parse)",
                LuaAllyRegistry.create("remixed_full_healing_wisp"));
    }

    @Test
    public void heroIdIsWiredAndNilSafeWithoutHero() throws Exception {
        enableRemixedFull();
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();

        assertFalse("RPD global must be present", g.get("RPD").isnil());
        assertTrue("RPD.heroId must be a callable function",
                g.get("RPD").get("heroId").isfunction());

        // No Dungeon.hero exists in headless → must return nil, never throw.
        assertTrue(Dungeon.hero == null);
        LuaValue hid = g.load("return RPD.heroId()").call();
        assertTrue("RPD.heroId() nil-safe with no hero bound (decision A guard)",
                hid.isnil());
    }
}
