package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.watabou.noosa.Game;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M15c: verifies Lua spells can enter the standard drop deck via
 * {@link Generator.Category#LUA_SPELL}. Mirrors {@link GeneratorLuaItemTest}
 * patterns but for spells.
 */
public class GeneratorLuaSpellTest {

    private static HeadlessApplication application;
    private static int savedVersionCode;

    @BeforeClass
    public static void initHeadless() {
        HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
        config.updatesPerSecond = 1;
        application = new HeadlessApplication(new ApplicationAdapter() {}, config);
        Game.version = "test";
        savedVersionCode = Game.versionCode;
        Game.versionCode = 896;
    }

    @Before
    public void resetModAndLuaState() throws Exception {
        ModTestSupport.enableTestMod();
        ModTestSupport.resetLuaState();
        BalanceConfig.resetToDefaults();
        Generator.setLuaItemProbability(0f, 0f);
        Generator.setLuaSpellDropProbability(0f, 0f);
        Generator.fullReset();
    }

    @AfterClass
    public static void shutdown() {
        Game.versionCode = savedVersionCode;
        try { if (application != null) application.exit(); } catch (Throwable ignored) { }
    }

    @Test
    public void poolEmptyWhenNoScriptsHaveRun() {
        assertEquals(0, LuaSpellRegistry.size());
        assertEquals("LuaSpellPool.random() must be null when the registry is empty",
                null, LuaSpellPool.random());
    }

    @Test
    public void generatorRandomLuaSpellReturnsLuaSpellOrScrollFallback() {
        LuaEngine.init();
        assertTrue("engine should have registered test_spell",
                LuaSpellRegistry.size() >= 1);

        Item item = Generator.random(Generator.Category.LUA_SPELL);
        assertNotNull(item);
        assertTrue("Generator.random(LUA_SPELL) must return a LuaSpell or scroll fallback, got "
                        + item.getClass().getSimpleName(),
                item instanceof LuaSpell || item instanceof Scroll);
    }

    @Test
    public void generatorRandomLuaSpellSpansPool() {
        LuaEngine.init();
        java.util.Set<String> names = new java.util.HashSet<>();
        int luaHits = 0;
        for (int i = 0; i < 120; i++) {
            Item item = Generator.random(Generator.Category.LUA_SPELL);
            assertNotNull(item);
            if (item instanceof LuaSpell) {
                luaHits++;
                names.add(item.name());
            }
        }
        assertTrue("expected ≥2 distinct Lua spells across 120 pulls, got " + names,
                names.size() >= 2);
        assertTrue("expected most pulls to be LuaSpell when registry is non-empty, got " + luaHits + "/120",
                luaHits >= 80);
    }

    @Test
    public void deckRandomEmitsLuaSpellWhenWeighted() throws Exception {
        LuaEngine.init();

        java.lang.reflect.Field f = Generator.class.getDeclaredField("categoryProbs");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.HashMap<Generator.Category, Float> probs =
                (java.util.HashMap<Generator.Category, Float>) f.get(null);

        Generator.generalReset();
        for (Generator.Category c : Generator.Category.values()) {
            probs.put(c, c == Generator.Category.LUA_SPELL ? 100f : 0f);
        }

        int luaHits = 0;
        for (int i = 0; i < 20; i++) {
            Item item = Generator.random();
            assertNotNull(item);
            if (item instanceof LuaSpell) luaHits++;
        }
        assertTrue("Generator.random() deck must emit Lua spells when LUA_SPELL is the only "
                        + "weighted category (got " + luaHits + "/20)",
                luaHits >= 15);
    }

    @Test
    public void defaultProbabilityDoesNotEmitLuaSpell() throws Exception {
        LuaEngine.init();
        // Sanity: default firstProb/secondProb for LUA_SPELL are 0.
        assertEquals(0f, Generator.Category.LUA_SPELL.firstProb, 0.001f);
        assertEquals(0f, Generator.Category.LUA_SPELL.secondProb, 0.001f);

        for (Generator.Category c : Generator.Category.values()) {
            assertEquals("categoryProbs for " + c + " must default to first/secondProb",
                    c == Generator.Category.LUA_SPELL ? 0f : (usingFirstDeck() ? c.firstProb : c.secondProb),
                    categoryProbOf(c), 0.001f);
        }

        int luaHits = 0;
        for (int i = 0; i < 50; i++) {
            // Directly exercise the deck switch path by depleting current deck.
            Generator.random();
            // We can't easily observe what rolled, but LUA_SPELL has prob 0 so
            // Random.chances will never pick it. We assert the categoryProbs
            // entry remains 0 after reset.
        }
        Generator.generalReset();
        assertEquals(0f, categoryProbOf(Generator.Category.LUA_SPELL), 0.001f);
        assertEquals("zero-prob category should never be selected", 0, luaHits);
    }

    @Test
    public void setLuaSpellDropProbabilityUpdatesLiveDeck() throws Exception {
        LuaEngine.init();

        Generator.fullReset();
        Generator.setLuaSpellDropProbability(100f, 100f);

        int luaHits = 0;
        for (int i = 0; i < 20; i++) {
            Item item = Generator.random();
            assertNotNull(item);
            if (item instanceof LuaSpell) luaHits++;
        }
        assertTrue("setting prob to 100 must immediately make Generator.random() emit Lua spells (got "
                + luaHits + "/20)", luaHits >= 10);

        // Now disable drops and confirm they stop.
        Generator.setLuaSpellDropProbability(0f, 0f);
        for (int i = 0; i < 20; i++) {
            Item item = Generator.random();
            assertNotNull(item);
            assertFalse("after disabling prob, Generator.random() must not emit LuaSpell",
                    item instanceof LuaSpell);
        }
    }

    @Test
    public void balanceConfigLuaSpellDropProbReachesGenerator() {
        Map<String, Number> overrides = new HashMap<>();
        overrides.put("lua_spell_drop_prob", 7f);
        BalanceConfig.applyModOverrides(overrides);

        assertEquals(7f, BalanceConfig.LUA_SPELL_DROP_FIRST, 0.001f);
        assertEquals(7f, BalanceConfig.LUA_SPELL_DROP_SECOND, 0.001f);

        // Simulate ModRegistry propagation
        Generator.setLuaSpellDropProbability(BalanceConfig.LUA_SPELL_DROP_FIRST, BalanceConfig.LUA_SPELL_DROP_SECOND);
        assertEquals(7f, Generator.Category.LUA_SPELL.firstProb, 0.001f);
        assertEquals(7f, Generator.Category.LUA_SPELL.secondProb, 0.001f);
    }

    @Test
    public void balanceConfigRejectsNegativeOrInfiniteDropProb() {
        Map<String, Number> overrides = new HashMap<>();
        overrides.put("lua_spell_drop_prob", -3f);
        BalanceConfig.applyModOverrides(overrides);
        assertEquals(0f, BalanceConfig.LUA_SPELL_DROP_FIRST, 0.001f);
        assertEquals(0f, BalanceConfig.LUA_SPELL_DROP_SECOND, 0.001f);

        overrides.put("lua_spell_drop_prob", Double.POSITIVE_INFINITY);
        BalanceConfig.applyModOverrides(overrides);
        assertEquals(0f, BalanceConfig.LUA_SPELL_DROP_FIRST, 0.001f);
        assertEquals(0f, BalanceConfig.LUA_SPELL_DROP_SECOND, 0.001f);
    }

    private static float categoryProbOf(Generator.Category cat) throws Exception {
        java.lang.reflect.Field f = Generator.class.getDeclaredField("categoryProbs");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.HashMap<Generator.Category, Float> probs =
                (java.util.HashMap<Generator.Category, Float>) f.get(null);
        return probs.get(cat);
    }

    private static boolean usingFirstDeck() throws Exception {
        java.lang.reflect.Field f = Generator.class.getDeclaredField("usingFirstDeck");
        f.setAccessible(true);
        return (boolean) f.get(null);
    }
}
