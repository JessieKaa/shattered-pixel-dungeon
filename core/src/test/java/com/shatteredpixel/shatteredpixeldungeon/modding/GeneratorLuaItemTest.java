package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.watabou.utils.Bundle;
import org.junit.After;
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
 * <p>M15a: with {@code lua_item_drop_prob > 0} in enabled mod balance, the standard
 * deck {@link Generator#random()} can also emit Lua items. The default
 * firstProb/secondProb remain 0 so vanilla balance is preserved when no mod opts in.
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

    @After
    public void resetBalance() {
        BalanceConfig.resetToDefaults();
        Generator.setLuaItemProbability(0f, 0f);
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
     * M15a: when an enabled mod declares {@code balance.lua_item_drop_prob > 0},
     * {@link Generator#random()} can roll LUA_ITEM and emit a Lua-defined item.
     * test_mod's manifest sets the prob to 100 (large enough to dominate the deck).
     */
    @Test
    public void deckRandomEmitsLuaItemWithModBalance() throws Exception {
        LuaEngine.init();
        assertTrue("test_mod must have enabled balance override",
                BalanceConfig.LUA_ITEM_DROP_PROB > 0);

        int luaHits = 0;
        for (int i = 0; i < 40; i++) {
            Item item = Generator.random();
            assertNotNull(item);
            if (item instanceof LuaItem || item instanceof LuaMaterial) luaHits++;
        }
        assertTrue("Generator.random() deck must emit Lua-defined items when lua_item_drop_prob > 0 "
                        + "(got " + luaHits + "/40)",
                luaHits >= 30);
    }

    /**
     * M15a C3: when the mod is disabled, {@code lua_item_drop_prob} is not applied and
     * the standard deck must never emit a Lua item.
     */
    @Test
    public void deckRandomNoLuaItemWhenModDisabled() throws Exception {
        ModRegistry.setEnabled("test_mod", false);
        LuaEngine.init();

        assertEquals("disabled mod must leave LUA_ITEM_DROP_PROB at default 0",
                0f, BalanceConfig.LUA_ITEM_DROP_PROB, 0f);

        for (int i = 0; i < 20; i++) {
            Item item = Generator.random();
            assertNotNull(item);
            assertFalse("vanilla deck must not emit Lua items when no mod opts in (got "
                            + item.getClass().getSimpleName() + ")",
                    item instanceof LuaItem || item instanceof LuaMaterial);
        }
    }

    /**
     * M15a: {@link Generator#fullReset()} re-applies the runtime override after it
     * switches decks, so Lua items keep dropping across deck switches.
     */
    @Test
    public void luaItemProbabilityPersistsAcrossFullReset() throws Exception {
        LuaEngine.init();
        assertTrue(BalanceConfig.LUA_ITEM_DROP_PROB > 0);

        java.lang.reflect.Field f = Generator.class.getDeclaredField("categoryProbs");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.HashMap<Generator.Category, Float> probs =
                (java.util.HashMap<Generator.Category, Float>) f.get(null);

        int luaHits = 0;
        for (int pass = 0; pass < 10; pass++) {
            Generator.fullReset();
            assertEquals("fullReset() must preserve lua_item_drop_prob override (pass " + pass + ")",
                    BalanceConfig.LUA_ITEM_DROP_PROB,
                    probs.get(Generator.Category.LUA_ITEM), 0f);
            Item item = Generator.random();
            assertNotNull(item);
            if (item instanceof LuaItem || item instanceof LuaMaterial) luaHits++;
        }
        assertTrue("Lua items must drop across repeated fullReset() calls (got " + luaHits + "/10)",
                luaHits >= 5);
    }

    /**
     * M15a: lua_item_drop_prob is runtime-only. A bundle saved while the prob was
     * non-zero must not resurrect that prob after the runtime override is cleared
     * (e.g. mod disabled / new game).
     */
    @Test
    public void luaItemProbabilityNotPersistedThroughBundle() throws Exception {
        LuaEngine.init();
        assertTrue(BalanceConfig.LUA_ITEM_DROP_PROB > 0);

        java.lang.reflect.Field f = Generator.class.getDeclaredField("categoryProbs");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.HashMap<Generator.Category, Float> probs =
                (java.util.HashMap<Generator.Category, Float>) f.get(null);

        // Save while prob is high.
        Bundle bundle = new Bundle();
        Generator.storeInBundle(bundle);

        // Runtime override is cleared (e.g. user disabled the mod).
        BalanceConfig.LUA_ITEM_DROP_PROB = 0f;
        Generator.setLuaItemProbability(0f, 0f);

        // Restore must re-apply the current runtime value, not the stale bundle value.
        Generator.restoreFromBundle(bundle);
        assertEquals("bundle restore must not persist stale LUA_ITEM probability",
                0f, probs.get(Generator.Category.LUA_ITEM), 0f);

        for (int i = 0; i < 10; i++) {
            Item item = Generator.random();
            assertNotNull(item);
            assertFalse("restored game with cleared override must not drop Lua items",
                    item instanceof LuaItem || item instanceof LuaMaterial);
        }
    }

    /**
     * M6e balance #5: the default {@link LuaItemPool#random()} (the path
     * {@code Generator.random(LUA_ITEM)} takes) must skip material-typed ids so a
     * crafting component never drops from a weapon-shaped roll. Materials and
     * weapons no longer share a pool.
     */
    @Test
    public void defaultPoolExcludesMaterials() {
        LuaEngine.init();
        // Sanity: the test_mod registry actually carries both shapes.
        assertTrue("rotten_organ must be registered as a material",
                LuaItemRegistry.isMaterial("rotten_organ"));
        assertFalse("test_sword must not be a material",
                LuaItemRegistry.isMaterial("test_sword"));

        for (int i = 0; i < 50; i++) {
            Item item = LuaItemPool.random();
            assertNotNull(item);
            assertFalse("default random() must not emit a material (got " + item.getClass().getSimpleName() + ")",
                    item instanceof LuaMaterial);
        }

        // The material-only entry point does the inverse.
        Item mat = LuaItemPool.randomMaterial();
        assertNotNull("randomMaterial() must return a material when registered", mat);
        assertTrue("randomMaterial() must emit a LuaMaterial", mat instanceof LuaMaterial);
    }

    /**
     * Legacy automated coverage for PLAN Step 8 (desktop-debug "see a Lua item
     * drop"). Kept to prove the direct deck path works when LUA_ITEM is the only
     * weighted category.
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
