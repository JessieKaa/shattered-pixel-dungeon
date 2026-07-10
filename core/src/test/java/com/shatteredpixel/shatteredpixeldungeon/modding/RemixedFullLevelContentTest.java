package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.watabou.noosa.Game;
import com.watabou.utils.GameSettings;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M20f {@code remixed_full_rf_arena}: a combat-focused showcase {@link DataDrivenLevel} whose
 * JSON spawns the mobs/items NOT already used by the tavern/chapel siblings
 * ({@code bandit}/{@code hedgehog}/{@code fetid_rat} via {@code lua_mob:} and
 * {@code kunai}/{@code vile_essence}/{@code rusty_coin} via {@code lua_item:}).
 *
 * <p>Two contracts:
 * <ol>
 *   <li><b>registered when the mod is enabled</b>: {@code entry.lua}'s appended
 *       {@code register_level} call lands the id in {@link LuaLevelRegistry} after
 *       {@link LuaEngine#init()}.</li>
 *   <li><b>JSON is structurally valid and positions are passable</b>: mirrors the m17c sibling
 *       check ({@link RemixedFullPackTest#m17c_levels_areStructurallyValidAndPositionsPassable}).
 *       {@link DataDrivenLevel#fromJsonValue} only enforces tiles-length/entrance-bounds — it
 *       does NOT reject a mob whose {@code pos} lands on a wall/statue (runtime {@code createMobs}
 *       silently skips it). So {@code build()} resolves every tile and each mob/item cell is
 *       checked against the authoritative {@link Terrain#flags} PASSABLE/SOLID mask, which catches
 *       a hand-authored 256-tile off-by-one that the loader would otherwise miss. Also asserts
 *       the level carries at least one {@code lua_mob:} and one {@code lua_item:} spec — the
 *       whole point of M20f is exercising those prefixes on a fresh level.</li>
 * </ol>
 */
public class RemixedFullLevelContentTest {

    private static final String RF_ARENA_ID = "remixed_full_rf_arena";
    private static final String RF_ARENA_ASSET = "mods/levels/remixed_full_rf_arena.json";

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
        Game.versionCode = 896;
        Game.version = "test";
    }

    @AfterClass
    public static void shutdown() {
        Game.versionCode = savedVersionCode;
        Game.version = savedVersion;
        try { if (application != null) application.exit(); } catch (Throwable ignored) { }
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
        // Keep other mods off so they cannot pollute the registry assertions.
        ModRegistry.setEnabled("remished_lite", false);
        ModRegistry.setEnabled("test_mod", false);
        ModRegistry.setEnabled("regression_demo", false);
    }

    @Test
    public void rfArenaLevelRegistered() throws Exception {
        enableRemixedFull();
        assertTrue("remixed_full must be enabled", ModRegistry.isEnabled("remixed_full"));

        LuaEngine.init();

        assertTrue("rf_arena level registered by entry.lua",
                LuaLevelRegistry.contains(RF_ARENA_ID));
        // The 3 pre-existing sibling levels must still be registered (append-only contract).
        assertTrue("alpha hub still registered", LuaLevelRegistry.contains("remixed_full_alpha_hub"));
        assertTrue("tavern still registered",    LuaLevelRegistry.contains("remixed_full_tavern"));
        assertTrue("chapel still registered",    LuaLevelRegistry.contains("remixed_full_chapel"));
    }

    @Test
    public void rfArenaJsonParsesAndStructurallyValid() {
        JsonValue root = new JsonReader().parse(Gdx.files.internal(RF_ARENA_ASSET).readString("UTF-8"));
        assertEquals(RF_ARENA_ID, root.getString("id"));
        assertEquals("16x16 width",  16, root.getInt("width"));
        assertEquals("16x16 height", 16, root.getInt("height"));
        assertEquals("256 tiles", 256, root.require("tiles").size);
        assertTrue("arena is safe/ephemeral", root.getBoolean("safe"));

        int entrance = root.getInt("entrance");
        int exit = root.getInt("exit");
        String[] tiles = tilesArray(root.require("tiles"));
        assertEquals("entrance cell carries an entrance tile", "entrance", tiles[entrance]);
        assertEquals("exit cell carries an exit tile",         "exit",     tiles[exit]);

        DataDrivenLevel built = DataDrivenLevel.fromAsset(RF_ARENA_ASSET, RF_ARENA_ID);
        assertNotNull("fromAsset must parse " + RF_ARENA_ID, built);
        // build() resolves every tile name via setSize + tileNameToId; reaching here means the
        // definition is sound (a length mismatch would have thrown in fromJsonValue).
        built.build();
        assertNotNull("map allocated", built.map);
        assertEquals("map length", 256, built.map.length);

        // Validate passability against the authoritative Terrain flags (buildFlagMaps needs
        // openSpace[]/PathFinder bookkeeping irrelevant to this data invariant).
        assertTrue("entrance must be passable",
                (Terrain.flags[built.map[entrance]] & Terrain.PASSABLE) != 0);
        assertTrue("exit must be passable",
                (Terrain.flags[built.map[exit]] & Terrain.PASSABLE) != 0);

        JsonValue mobsArr = root.require("mobs");
        assertTrue("arena should place at least one mob", mobsArr.size > 0);
        int luaMobSpecs = 0;
        for (JsonValue m = mobsArr.child; m != null; m = m.next) {
            int pos = m.getInt("pos");
            int t = built.map[pos];
            String type = m.getString("type");
            assertTrue("mob " + type + " @" + pos + " must be passable",
                    (Terrain.flags[t] & Terrain.PASSABLE) != 0);
            assertFalse("mob " + type + " @" + pos + " must not be solid",
                    (Terrain.flags[t] & Terrain.SOLID) != 0);
            if (type.startsWith("lua_mob:")) luaMobSpecs++;
        }
        assertTrue("arena must reference at least one lua_mob: spec", luaMobSpecs > 0);

        JsonValue itemsArr = root.get("items");
        int luaItemSpecs = 0;
        for (JsonValue it = itemsArr != null ? itemsArr.child : null; it != null; it = it.next) {
            int pos = it.getInt("pos");
            String type = it.getString("type");
            assertFalse("item " + type + " @" + pos + " must not be solid",
                    (Terrain.flags[built.map[pos]] & Terrain.SOLID) != 0);
            if (type.startsWith("lua_item:")) luaItemSpecs++;
        }
        assertTrue("arena must reference at least one lua_item: spec", luaItemSpecs > 0);
    }

    private static String[] tilesArray(JsonValue tilesArr) {
        String[] out = new String[tilesArr.size];
        int i = 0;
        for (JsonValue t = tilesArr.child; t != null; t = t.next) {
            out[i++] = t.asString();
        }
        return out;
    }
}
