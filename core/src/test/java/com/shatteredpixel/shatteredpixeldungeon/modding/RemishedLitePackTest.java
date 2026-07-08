package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
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
 * M13c {@code remished_lite} builtin pack: a {@code default_enabled=true} mod that ships a
 * curated showcase hub level. This test pins the three contracts that justify shipping a mod
 * enabled-by-default without polluting the vanilla playthrough (C3):
 *
 * <ol>
 *   <li><b>default-enabled loads</b>: with fresh prefs the mod is enabled by manifest default
 *       ({@link ModRegistry#isEnabled} falls back to {@code default_enabled}), its scripts
 *       auto-register one item / one spell / one mob / two NPCs / one shop, and
 *       {@code register_level} in {@code entry.lua} files the hub in {@link LuaLevelRegistry}.</li>
 *   <li><b>C3 — enabled does not pollute main game</b>: the registered Lua item never enters the
 *       drop deck ({@link Generator.Category#LUA_ITEM} keeps firstProb/secondProb 0), no trap is
 *       registered (no {@code scripts/traps/} dir → {@link LuaTrapRegistry} empty →
 *       {@code injectLevelTraps} no-ops), and the registered mob is inert (vanilla spawn never
 *       reads {@link LuaMobRegistry} — {@code Level.createMob}/{@code MobSpawner} untouched).</li>
 *   <li><b>hub level json is structurally valid</b>: {@link DataDrivenLevel#fromAsset} parses it
 *       without throwing (non-null ⇒ width/height/tiles/entrance all validated), with the
 *       32×32 padded hub shape mirroring {@code test_safezone.json}.</li>
 *   <li><b>disabled loads nothing</b>: toggling the mod off drops every remished_lite id from
 *       every registry (the toggle fully controls all Lua content — mirrors
 *       {@code RegressionDemoModTest.loadDisabled_registriesEmpty}).</li>
 * </ol>
 *
 * <p>Setup mirrors {@link RegressionDemoModTest}: a libgdx {@link HeadlessApplication} with
 * {@code Game.versionCode=896} (the mod's {@code spd_version} version gate) and fresh
 * HashMap-backed Preferences per test. Unlike most Lua* tests, this one does NOT route through
 * {@code ModTestSupport.enableTestMod()} (which disables remished_lite) — it scans real mods
 * directly so the default-enabled behavior is the thing under test.
 */
public class RemishedLitePackTest {

    private static final int TEST_VERSION_CODE = 896;
    private static final String HUB_ASSET = "mods/levels/remished_lite_hub.json";
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
        // Fresh prefs + cleared registries every test. Do NOT call enableTestMod() — it
        // disables remished_lite, but this test asserts the default-enabled path. scanRealMods()
        // in each test leaves remished_lite on (manifest default) and every other mod off.
        GameSettings.set(new ModTestSupport.FakePreferences());
        ModRegistry.resetForTests();
        ModTestSupport.resetLuaState();
    }

    private void scanRealMods() throws Exception {
        ModRegistry.scanDir(ModTestSupport.realModsHandle());
    }

    // ---------------- default-enabled: showcase content registers ----------------

    @Test
    public void defaultEnabled_loadsShowcaseContent() throws Exception {
        scanRealMods();

        // Manifest default_enabled=true ⇒ enabled with no explicit toggle (fresh prefs).
        assertTrue("remished_lite must be enabled by default (default_enabled=true)",
                ModRegistry.isEnabled("remished_lite"));
        // Other builtin mods are default_enabled=false ⇒ stay off, so only remished_lite loads.
        assertFalse("test_mod must stay disabled (default_enabled=false)",
                ModRegistry.isEnabled("test_mod"));

        LuaEngine.init();

        // Every shipped script registers (directory auto-scan + entry-script register_level).
        assertTrue("showcase weapon registered", LuaItemRegistry.contains("remished_lite_lantern_blade"));
        assertTrue("showcase spell registered (inert — no acquisition path)",
                LuaSpellRegistry.contains("remished_lite_spark"));
        assertTrue("showcase mob registered (inert — no lua_mob: placement / no spawn)",
                LuaMobRegistry.contains("remished_lite_marauder"));
        assertTrue("guide NPC registered", LuaNpcRegistry.contains("remished_lite_guide"));
        assertTrue("sage NPC registered", LuaNpcRegistry.contains("remished_lite_sage"));
        assertTrue("showcase shop registered", LuaShopRegistry.contains("remished_lite_shop"));
        assertTrue("hub level registered via entry-script register_level",
                LuaLevelRegistry.contains("remished_lite_hub"));

        // Exact sizes: with only remished_lite enabled, each registry holds exactly this pack's
        // content. Guards against a stray script or a double-register.
        assertEquals("1 item", 1, LuaItemRegistry.size());
        assertEquals("1 spell", 1, LuaSpellRegistry.size());
        assertEquals("1 mob", 1, LuaMobRegistry.size());
        assertEquals("2 NPCs (guide + sage)", 2, LuaNpcRegistry.size());
        assertEquals("1 shop", 1, LuaShopRegistry.size());
        assertEquals("0 traps (no register_trap → C3)", 0, LuaTrapRegistry.size());
    }

    // ---------------- C3: enabled yet main game stays vanilla ----------------

    @Test
    public void c3_enabledDoesNotPolluteMainGame() throws Exception {
        scanRealMods();
        LuaEngine.init();

        // The LUA_ITEM category carries firstProb/secondProb 0, so generalReset() assigns it a
        // deck weight of 0 and Random.chances(categoryProbs) never selects it — registered Lua
        // items never drop from the standard monster/level drop deck (original balance intact).
        assertEquals("LUA_ITEM firstProb must stay 0 (C3: no Lua item drops)",
                0f, Generator.Category.LUA_ITEM.firstProb, 0f);
        assertEquals("LUA_ITEM secondProb must stay 0 (C3: no Lua item drops)",
                0f, Generator.Category.LUA_ITEM.secondProb, 0f);

        // remished_lite ships NO scripts/traps/, so LuaTrapRegistry stays empty and the
        // non-debug-gated injectLevelTraps short-circuits on hasAny()==false — no Lua trap is
        // auto-placed into vanilla levels (the one auto-manifest category is kept out of main).
        assertEquals("no trap registered → injectLevelTraps cannot auto-manifest (C3)",
                0, LuaTrapRegistry.size());

        // The hostile mob is registered but inert: DataDrivenLevel has no "lua_mob:" prefix, and
        // the vanilla spawn path (Level.createMob / MobSpawner) never consults LuaMobRegistry
        // (verified: zero LuaMobRegistry references under levels/ + actors/). Registered-yet-
        // never-spawned is exactly the C3 contract for mobs.
        assertTrue("mob is registered (for coverage) yet never spawns in main game (C3)",
                LuaMobRegistry.contains("remished_lite_marauder"));
    }

    // ---------------- hub level json: structurally valid + loadable ----------------

    @Test
    public void hubLevelAsset_isStructurallyValid() {
        // JsonReader structural asserts mirror SafeZoneEnterTest (headless-safe, no Dungeon).
        JsonValue root = new JsonReader().parse(Gdx.files.internal(HUB_ASSET).readString("UTF-8"));
        assertEquals("id", "remished_lite_hub", root.getString("id"));
        assertEquals("32x32 width", 32, root.getInt("width"));
        assertEquals("32x32 height", 32, root.getInt("height"));
        assertEquals("1024 tiles", 1024, root.require("tiles").size);
        assertEquals("entrance at (9,9)", 297, root.getInt("entrance"));
        assertEquals("exit at (22,22)", 726, root.getInt("exit"));
        assertTrue("safe hub (no respawner)", root.getBoolean("safe"));

        String[] tiles = tilesArray(root.require("tiles"));
        assertEquals("entrance tile", "entrance", tiles[297]);
        assertEquals("exit tile", "exit", tiles[726]);
        assertEquals("corner padding is wall", "wall", tiles[0]);
        assertEquals("corner padding is wall", "wall", tiles[1023]);
        assertEquals("interior is floor", "floor", tiles[363]);      // guide NPC cell
        assertEquals("interior is floor", "floor", tiles[587]);      // shop cell

        // mob/item specs parsed: 2 lua_npc + 1 lua_shop + 1 rat_king ; 2 gold pickups.
        assertEquals("4 mob specs (guide + sage + shop + rat_king)", 4, root.require("mobs").size);
        assertEquals("2 item specs (gold x2)", 2, root.require("items").size);

        // fromAsset runs the full structural validator (throws on bad width/tiles/entrance) —
        // non-null return proves the hub is loadable by LuaLevelService.enterLevel's path.
        DataDrivenLevel lvl = DataDrivenLevel.fromAsset(HUB_ASSET, "remished_lite_hub");
        assertNotNull("fromAsset must parse the hub without throwing", lvl);
    }

    // ---------------- disabled: registries empty (toggle fully controls content) ----------------

    @Test
    public void disabled_loadsNothing() throws Exception {
        scanRealMods();
        ModRegistry.setEnabled("remished_lite", false);

        LuaEngine.init();

        assertFalse("weapon absent when disabled", LuaItemRegistry.contains("remished_lite_lantern_blade"));
        assertFalse("spell absent when disabled", LuaSpellRegistry.contains("remished_lite_spark"));
        assertFalse("mob absent when disabled", LuaMobRegistry.contains("remished_lite_marauder"));
        assertFalse("guide absent when disabled", LuaNpcRegistry.contains("remished_lite_guide"));
        assertFalse("sage absent when disabled", LuaNpcRegistry.contains("remished_lite_sage"));
        assertFalse("shop absent when disabled", LuaShopRegistry.contains("remished_lite_shop"));
        assertFalse("level absent when disabled", LuaLevelRegistry.contains("remished_lite_hub"));
        assertEquals("no items when disabled", 0, LuaItemRegistry.size());
        assertEquals("no spells when disabled", 0, LuaSpellRegistry.size());
        assertEquals("no mobs when disabled", 0, LuaMobRegistry.size());
        assertEquals("no npcs when disabled", 0, LuaNpcRegistry.size());
        assertEquals("no shops when disabled", 0, LuaShopRegistry.size());
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
