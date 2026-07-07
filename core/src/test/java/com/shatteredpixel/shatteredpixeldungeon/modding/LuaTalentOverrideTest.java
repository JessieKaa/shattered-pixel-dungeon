package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.watabou.noosa.Game;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M7e (D6=(a)) coverage for {@link LuaTalentOverride} and the
 * {@code register_talent_override} Lua global. Pins down the Option-B contract:
 * desc whole-string replacement + maxPoints <b>lower-only</b> (a Lua maxPoints
 * above the vanilla cap is rejected at register time, not clamped). Bad fields
 * are skipped independently; a bad id never throws.
 *
 * <p>Driven through the real {@link LuaEngine} globals (so the Lua→Java path is
 * exercised end-to-end), with the registry wiped in {@code @Before} so each test
 * is isolated from the shipped test_mod override scripts.
 */
public class LuaTalentOverrideTest {

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
    public void resetState() throws Exception {
        ModTestSupport.enableTestMod();
        ModTestSupport.resetLuaState();
    }

    /** Init the engine, then wipe the shipped test_mod overrides so each test starts empty. */
    private Globals cleanGlobals() {
        LuaEngine.init();
        LuaTalentOverride.clear();
        return LuaEngine.instance().globals();
    }

    private void register(Globals g, String luaTableLiteral) {
        g.load("register_talent_override(" + luaTableLiteral + ")").call();
    }

    // ---- maxPoints lower-only ----

    @Test
    public void lowerMaxPoints_takesEffect() {
        Globals g = cleanGlobals();
        register(g, "{ id='HEARTY_MEAL', maxPoints=1 }");
        LuaTalentOverride.Override o = LuaTalentOverride.get(Talent.HEARTY_MEAL);
        assertNotNull(o);
        assertEquals(Integer.valueOf(1), o.maxPoints);
        assertEquals("maxPoints() must return the lowered override", 1, Talent.HEARTY_MEAL.maxPoints());
        assertEquals("baseMaxPoints() must always return the vanilla cap", 2, Talent.HEARTY_MEAL.baseMaxPoints());
    }

    @Test
    public void rejectsMaxPointsAboveVanilla() {
        Globals g = cleanGlobals();
        register(g, "{ id='HEARTY_MEAL', maxPoints=3 }"); // vanilla=2, 3>2 → reject field
        LuaTalentOverride.Override o = LuaTalentOverride.get(Talent.HEARTY_MEAL);
        // Entry registered (desc below would apply) but with null maxPoints → vanilla retained.
        assertNull("maxPoints field must be skipped (not clamped) when above vanilla", o == null ? null : o.maxPoints);
        assertEquals("maxPoints() must fall back to vanilla when the raise is rejected",
                2, Talent.HEARTY_MEAL.maxPoints());
    }

    @Test
    public void rejectsMaxPointsAboveVanilla_standaloneOverrideOnly() {
        // When maxPoints is the ONLY field and it is rejected, nothing is stored.
        Globals g = cleanGlobals();
        register(g, "{ id='IRON_WILL', maxPoints=99 }"); // vanilla=2
        assertNull("no override should be registered when the only field is rejected",
                LuaTalentOverride.get(Talent.IRON_WILL));
        assertEquals(2, Talent.IRON_WILL.maxPoints());
    }

    @Test
    public void rejectsMaxPointsBelowOne() {
        Globals g = cleanGlobals();
        register(g, "{ id='HEARTY_MEAL', maxPoints=0 }");
        assertNull(LuaTalentOverride.get(Talent.HEARTY_MEAL));
        assertEquals(2, Talent.HEARTY_MEAL.maxPoints());
    }

    @Test
    public void rejectsNonIntMaxPoints() {
        Globals g = cleanGlobals();
        register(g, "{ id='HEARTY_MEAL', maxPoints='two' }");
        assertNull(LuaTalentOverride.get(Talent.HEARTY_MEAL));
        assertEquals(2, Talent.HEARTY_MEAL.maxPoints());
    }

    // ---- desc override ----

    @Test
    public void descOverride_takesEffect() {
        Globals g = cleanGlobals();
        register(g, "{ id='HEARTY_MEAL', desc='Lua flavor text' }");
        assertEquals("Lua flavor text", Talent.HEARTY_MEAL.desc());
        assertEquals("Lua flavor text", Talent.HEARTY_MEAL.desc(false));
        assertEquals("Lua flavor text", Talent.HEARTY_MEAL.desc(true));
    }

    @Test
    public void rejectsNonStringDesc() {
        Globals g = cleanGlobals();
        register(g, "{ id='HEARTY_MEAL', desc=123 }");
        assertNull(LuaTalentOverride.get(Talent.HEARTY_MEAL));
    }

    // ---- partial-valid: good field applies, bad field skipped ----

    @Test
    public void partialValid_appliesGoodFieldSkipsBad() {
        Globals g = cleanGlobals();
        // maxPoints=3 rejected (>vanilla 2), desc applied → override registered with null maxPoints.
        register(g, "{ id='HEARTY_MEAL', maxPoints=3, desc='kept' }");
        LuaTalentOverride.Override o = LuaTalentOverride.get(Talent.HEARTY_MEAL);
        assertNotNull("entry must still register when at least one field is valid", o);
        assertNull("bad maxPoints field must be skipped", o.maxPoints);
        assertEquals("maxPoints() falls back to vanilla", 2, Talent.HEARTY_MEAL.maxPoints());
        assertEquals("kept", Talent.HEARTY_MEAL.desc());
    }

    @Test
    public void allFieldsBad_notRegistered() {
        Globals g = cleanGlobals();
        int before = LuaTalentOverride.size();
        register(g, "{ id='HEARTY_MEAL', maxPoints=99, desc=456 }"); // both rejected
        assertNull(LuaTalentOverride.get(Talent.HEARTY_MEAL));
        assertEquals("no entry when every field is invalid", before, LuaTalentOverride.size());
    }

    // ---- bad id never throws ----

    @Test
    public void badId_skippedWithoutThrowing() {
        Globals g = cleanGlobals();
        int before = LuaTalentOverride.size();
        register(g, "{ id='NOT_A_REAL_TALENT', maxPoints=1 }");
        assertEquals("unknown id must not register or throw", before, LuaTalentOverride.size());
    }

    @Test
    public void nonTableArgument_skippedWithoutThrowing() {
        Globals g = cleanGlobals();
        // A non-table arg must not crash the engine.
        g.load("register_talent_override('not a table')").call();
        g.load("register_talent_override(42)").call();
        assertEquals(0, LuaTalentOverride.size());
    }

    // ---- upsert / clear / vanilla equivalence ----

    @Test
    public void upsert_lastCallWins() {
        Globals g = cleanGlobals();
        register(g, "{ id='HEARTY_MEAL', maxPoints=1, desc='first' }");
        register(g, "{ id='HEARTY_MEAL', maxPoints=2, desc='second' }");
        LuaTalentOverride.Override o = LuaTalentOverride.get(Talent.HEARTY_MEAL);
        assertNotNull(o);
        assertEquals(Integer.valueOf(2), o.maxPoints);
        assertEquals("second", Talent.HEARTY_MEAL.desc());
    }

    @Test
    public void clear_removesAllOverrides() {
        Globals g = cleanGlobals();
        register(g, "{ id='HEARTY_MEAL', maxPoints=1 }");
        assertTrue(LuaTalentOverride.size() >= 1);
        LuaTalentOverride.clear();
        assertEquals(0, LuaTalentOverride.size());
        assertNull(LuaTalentOverride.get(Talent.HEARTY_MEAL));
        assertEquals("cleared maxPoints falls back to vanilla", 2, Talent.HEARTY_MEAL.maxPoints());
    }

    @Test
    public void vanillaEquivalence_noOverrideReturnsVanilla() {
        cleanGlobals(); // empty registry
        assertNull(LuaTalentOverride.get(Talent.HEARTY_MEAL));
        assertEquals(2, Talent.HEARTY_MEAL.maxPoints());
        assertEquals(2, Talent.HEARTY_MEAL.baseMaxPoints());
        // desc() must return the vanilla Messages value (not an override, not null).
        String vanillaDesc = Talent.HEARTY_MEAL.desc();
        assertNotNull(vanillaDesc);
        assertTrue("vanilla desc should come from Messages, not be empty",
                vanillaDesc.length() > 0);
    }

    @Test
    public void t3_t4_talentsHaveHigherVanillaCap() {
        // Sanity: T3/T4 talents have vanilla maxPoints 3/4 (two-arg constructor),
        // so lowering them exercises a different cap than the T1 max=2 cases above.
        cleanGlobals();
        assertEquals(3, Talent.HOLD_FAST.baseMaxPoints());   // Warrior T3
        assertEquals(4, Talent.BODY_SLAM.baseMaxPoints());   // Heroic Leap T4
    }
}
