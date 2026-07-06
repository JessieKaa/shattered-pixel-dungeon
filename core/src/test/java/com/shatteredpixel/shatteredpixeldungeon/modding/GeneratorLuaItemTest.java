package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.watabou.noosa.Game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the M1/M6d Generator dual-source: {@link Generator#random(Generator.Category)}
 * with {@code LUA_ITEM} must return a Lua-defined {@link Item} drawn from
 * {@link LuaItemRegistry}, and the pool must report empty (so Generator can fall
 * back) when no Lua scripts have run.
 *
 * <p>The deck-facing {@code Generator.random()} is NOT exercised here: the
 * {@code LUA_ITEM} category has firstProb/secondProb 0, so it is never selected
 * by the standard drop deck (C3 — original balance preserved). The desktop debug
 * run (PLAN Step 8) covers the in-game drop path with the probability temporarily
 * raised.
 */
public class GeneratorLuaItemTest {

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

    @Test
    public void poolEmptyWhenNoScriptsHaveRun() {
        // Registry was cleared in @Before and the engine has not been init()ed.
        assertEquals(0, LuaItemRegistry.size());
        assertNull("LuaItemPool.random() must be null when the registry is empty",
                LuaItemPool.random());
    }

    @Test
    public void generatorRandomLuaItemReturnsLuaDefinedItem() {
        LuaEngine.init();
        assertTrue("engine should have registered test items (test_sword/axe/dagger)",
                LuaItemRegistry.size() >= 3);

        Item item = Generator.random(Generator.Category.LUA_ITEM);
        assertNotNull(item);
        assertTrue("Generator.random(LUA_ITEM) must return a Lua-defined item, got " + item.getClass().getSimpleName(),
                item instanceof LuaItem || item instanceof LuaMaterial);
        assertNotNull(item.name());
        assertFalse("name must hydrate from Lua, not stay degraded: " + item.name(),
                item.name().startsWith("???"));
    }

    @Test
    public void generatorRandomLuaItemSpansPool() {
        // Pull several times and confirm at least two distinct Lua items are reachable,
        // proving the pool isn't pinned to a single id.
        LuaEngine.init();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < 60; i++) {
            Item item = Generator.random(Generator.Category.LUA_ITEM);
            assertNotNull(item);
            assertTrue(item instanceof LuaItem || item instanceof LuaMaterial);
            names.add(item.name());
        }
        assertTrue("expected ≥2 distinct Lua items across 60 pulls, got " + names,
                names.size() >= 2);
    }

    /**
     * Equivalent automated coverage for PLAN Step 8 (desktop-debug "see a Lua item
     * drop"). The autonomous worktree has no display, so instead of launching the
     * game and killing monsters, this drives the real deck entry point
     * {@link Generator#random()} with {@code LUA_ITEM} weighted as the only
     * category — the exact path a monster drop takes. Verifies the full chain:
     * deck → {@code Random.chances} → {@code random(LUA_ITEM)} → {@code randomLuaItem()}.
     */
    @Test
    public void deckRandomEmitsLuaItemWhenWeighted() throws Exception {
        LuaEngine.init();

        java.lang.reflect.Field f = Generator.class.getDeclaredField("categoryProbs");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.HashMap<Generator.Category, Float> probs =
                (java.util.HashMap<Generator.Category, Float>) f.get(null);

        Generator.generalReset();
        for (Generator.Category c : Generator.Category.values()) {
            probs.put(c, c == Generator.Category.LUA_ITEM ? 100f : 0f);
        }

        int luaHits = 0;
        for (int i = 0; i < 20; i++) {
            Item item = Generator.random();
            assertNotNull(item);
            if (item instanceof LuaItem || item instanceof LuaMaterial) luaHits++;
        }
        assertTrue("Generator.random() deck must emit Lua-defined items when LUA_ITEM is the only "
                        + "weighted category (got " + luaHits + "/20)",
                luaHits >= 15);
    }
}
