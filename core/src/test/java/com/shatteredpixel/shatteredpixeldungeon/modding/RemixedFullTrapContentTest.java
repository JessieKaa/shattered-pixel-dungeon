package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.GameSettings;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M20a: {@code remixed_full} Lua trap content. Pins two contracts:
 *
 * <ol>
 *   <li><b>enabled registers both traps</b>: the M10b loader compiles
 *       {@code scripts/traps/*.lua} for every enabled mod, so enabling
 *       {@code remixed_full} must surface {@code remixed_full_alarm_trap} (pure
 *       trigger) and {@code remixed_full_charged_dart_trap} (M19a per-instance
 *       data) in {@link LuaTrapRegistry}.</li>
 *   <li><b>the data trap actually uses M19a persistence</b>: each
 *       {@code onActivate} call mutates the instance-owned {@code data.charges}
 *       in place (Lua tables are reference-typed, and {@link LuaTrap#activate}
 *       passes the instance's table as the third arg), and the mutation must
 *       survive a {@code Bundle} round-trip via {@code LuaDataCodec}.</li>
 * </ol>
 *
 * <p>The engine mechanics (registry ops, activate dispatch, deep-copy isolation,
 * legacy 2-arg tolerance) are already pinned by {@link LuaTrapRegistryTest};
 * this test stays at the content level (registration + a real Lua decrement
 * round-trip), mirroring the headless harness of {@link RemixedFullPackTest}.
 */
public class RemixedFullTrapContentTest {

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
        BalanceConfig.resetToDefaults();
        Generator.setLuaItemProbability(0f, 0f);
    }

    private void enableRemixedFull() throws Exception {
        ModRegistry.scanDir(ModTestSupport.realModsHandle());
        ModRegistry.setEnabled("remixed_full", true);
        // Keep the exact-registration slate clean: other builtin mods off.
        ModRegistry.setEnabled("remished_lite", false);
        ModRegistry.setEnabled("test_mod", false);
        ModRegistry.setEnabled("regression_demo", false);
    }

    @Test
    public void enabled_registersAlarmAndChargedDartTraps() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        assertTrue("remixed_full must be explicitly enabled",
                ModRegistry.isEnabled("remixed_full"));
        assertTrue("alarm trap registered by the M10b loader",
                LuaTrapRegistry.contains("remixed_full_alarm_trap"));
        assertTrue("charged dart trap registered by the M10b loader",
                LuaTrapRegistry.contains("remixed_full_charged_dart_trap"));
        assertTrue("registry must have at least the two remixed_full traps",
                LuaTrapRegistry.hasAny());
    }

    @Test
    public void chargedDartTrap_chargesDecrementAcrossBundleRoundTrip() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        LuaTable spec = LuaTrapRegistry.getTable("remixed_full_charged_dart_trap");
        assertNotNull("charged dart trap spec must be registered", spec);

        // new LuaTrap(spec) deep-copies spec.data → this instance owns charges=3.
        LuaTrap trap = new LuaTrap(spec);
        trap.pos = 5;
        assertEquals("fresh trap starts with 3 charges", 3, charges(trap));
        assertFalse("fresh trap is not depleted", depleted(trap));

        // onActivate mutates the instance-owned data table in place.
        trap.activate();
        assertEquals("first trigger decrements to 2", 2, charges(trap));

        // The mutation must survive save/load: restoreFromBundle rebuilds data
        // via LuaDataCodec, so re-read the field after restore (don't reuse the
        // pre-restore table reference).
        Bundle b = new Bundle();
        trap.storeInBundle(b);
        LuaTrap restored = new LuaTrap();
        restored.restoreFromBundle(b);
        restored.pos = 5;
        assertEquals("charges persist across Bundle round-trip", 2, charges(restored));

        restored.activate();
        assertEquals("second trigger decrements to 1", 1, charges(restored));
        assertFalse("not depleted while charges remain", depleted(restored));

        restored.activate();
        assertEquals("third trigger exhausts the magazine", 0, charges(restored));
        assertTrue("depleted flag flips when charges hit 0", depleted(restored));

        // Spent branch: further triggers must not drive charges negative.
        restored.activate();
        assertEquals("spent trap stays at 0", 0, charges(restored));
        assertTrue("once depleted, stays depleted", depleted(restored));
    }

    /** Reflection read of the private {@code data} field — re-fetched per call. */
    private static LuaValue dataField(LuaTrap trap) throws Exception {
        Field f = LuaTrap.class.getDeclaredField("data");
        f.setAccessible(true);
        return (LuaValue) f.get(trap);
    }

    private static int charges(LuaTrap trap) throws Exception {
        LuaValue d = dataField(trap);
        return d.istable() ? d.get("charges").toint() : -1;
    }

    private static boolean depleted(LuaTrap trap) throws Exception {
        LuaValue d = dataField(trap);
        return d.istable() && d.get("depleted").toboolean();
    }
}
