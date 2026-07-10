package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.painters.Painter;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.LibraryRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.BurnedRoom;
import com.watabou.noosa.Game;
import com.watabou.utils.GameSettings;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M20e: remixed_full Lua painter content pack. Verifies the two shipped
 * {@code mods/remixed_full/scripts/painters/*.lua} scripts register through the
 * {@link LuaEngine} loader pipeline (loader gating + registration) AND that each
 * painter actually mutates the level map when driven through
 * {@link LuaPainterAdapter} (behavior — guards against the false-green of a
 * presence-only test where a dead guard would register but never fire).
 *
 * <p>Mirrors the headless harness of {@link RemixedFullPackTest} (enable-remixed_full
 * slate) and the StubLevel/StubRoom paint-execution pattern of
 * {@link LuaPainterAdapterTest}. Uses REAL {@link BurnedRoom}/{@link LibraryRoom}
 * instances as bounds+classname carriers: {@code LuaPainterAdapter} matches the
 * registry by {@code room.getClass().getSimpleName()} and never calls
 * {@code room.paint()} (only delegate + Lua overlay), so PatchRoom.patch[] stays
 * null and the special-room size-constant overrides are inert — safe to instantiate.
 */
public class RemixedFullPainterContentTest {

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
        ModRegistry.setEnabled("remished_lite", false);
        ModRegistry.setEnabled("test_mod", false);
        ModRegistry.setEnabled("regression_demo", false);
    }

    private void disableAllMods() throws Exception {
        ModRegistry.scanDir(ModTestSupport.realModsHandle());
        ModRegistry.setEnabled("remixed_full", false);
        ModRegistry.setEnabled("remished_lite", false);
        ModRegistry.setEnabled("test_mod", false);
        ModRegistry.setEnabled("regression_demo", false);
    }

    // ---------------- registration + loader gating ----------------

    @Test
    public void enabled_bothPaintersRegister() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        assertTrue("rf_ember_floor_painter must register 'BurnedRoom'",
                LuaPainterRegistry.contains("BurnedRoom"));
        assertTrue("rf_deco_scatter_painter must register 'LibraryRoom'",
                LuaPainterRegistry.contains("LibraryRoom"));
        assertTrue("at least the two remixed_full painters must be registered",
                LuaPainterRegistry.size() >= 2);
    }

    @Test
    public void disabled_paintersDoNotLoad() throws Exception {
        disableAllMods();
        LuaEngine.init();

        assertFalse("BurnedRoom painter must NOT load when remixed_full is disabled",
                LuaPainterRegistry.contains("BurnedRoom"));
        assertFalse("LibraryRoom painter must NOT load when remixed_full is disabled",
                LuaPainterRegistry.contains("LibraryRoom"));
    }

    // ---------------- behavior (paint execution, anti-false-green) ----------------

    @Test
    public void emberPainter_paintsEmbersOnBurnedRoomInterior() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        Level lvl = newStubLevel(8, 8, Terrain.EMPTY);
        BurnedRoom room = new BurnedRoom();
        room.left = 1; room.top = 1; room.right = 6; room.bottom = 6;
        ArrayList<Room> rooms = new ArrayList<>();
        rooms.add(room);

        boolean ok = new LuaPainterAdapter(noopPainter()).paint(lvl, rooms);

        assertTrue("delegate paint result must be preserved", ok);
        assertTrue("ember painter must convert >=1 interior EMPTY cell to EMBERS",
                countTerrain(lvl, Terrain.EMBERS) >= 1);
    }

    @Test
    public void decoPainter_scattersDecoOnLibraryRoomInterior() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        // LibraryRoom.paint fills its interior with EMPTY_SP (not EMPTY), so the
        // stub is pre-seeded with EMPTY_SP to mirror the real floor. This is the
        // case that the original (dead) EMPTY-only guard would have left at 0.
        Level lvl = newStubLevel(8, 8, Terrain.EMPTY_SP);
        LibraryRoom room = new LibraryRoom();
        room.left = 1; room.top = 1; room.right = 6; room.bottom = 6;
        ArrayList<Room> rooms = new ArrayList<>();
        rooms.add(room);

        boolean ok = new LuaPainterAdapter(noopPainter()).paint(lvl, rooms);

        assertTrue("delegate paint result must be preserved", ok);
        assertTrue("deco painter must scatter >=1 EMPTY_DECO onto the EMPTY_SP floor "
                        + "(proves the EMPTY_SP guard fix; the old EMPTY-only guard left this at 0)",
                countTerrain(lvl, Terrain.EMPTY_DECO) >= 1);
    }

    // ---- helpers (mirror LuaPainterAdapterTest) ----

    private static Painter noopPainter() {
        return new Painter() {
            @Override
            public boolean paint(Level level, ArrayList<Room> rooms) {
                return true;
            }
        };
    }

    private static int countTerrain(Level lvl, int terrain) {
        int c = 0;
        for (int t : lvl.map) if (t == terrain) c++;
        return c;
    }

    private static final class StubLevel extends Level {
        @Override protected boolean build() { return true; }
        @Override protected void createMobs() { }
        @Override protected void createItems() { }
        @Override public int entrance() { return 0; }
        @Override public int exit() { return length() - 1; }
        @Override public String tilesTex() { return null; }
        @Override public String waterTex() { return null; }
    }

    private static Level newStubLevel(int w, int h, int fillTerrain) {
        StubLevel lvl = new StubLevel();
        lvl.setSize(w, h);
        java.util.Arrays.fill(lvl.map, fillTerrain);
        lvl.mobs = new HashSet<>();
        lvl.heaps = new com.watabou.utils.SparseArray<>();
        lvl.blobs = new HashMap<>();
        lvl.plants = new com.watabou.utils.SparseArray<>();
        lvl.traps = new com.watabou.utils.SparseArray<>();
        lvl.customTiles = new ArrayList<>();
        lvl.customWalls = new ArrayList<>();
        lvl.visited = new boolean[lvl.length()];
        lvl.mapped = new boolean[lvl.length()];
        lvl.buildFlagMaps();
        return lvl;
    }
}
