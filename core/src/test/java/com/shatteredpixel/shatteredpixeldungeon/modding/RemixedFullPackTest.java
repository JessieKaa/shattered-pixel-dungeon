package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.MobSpawner;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.watabou.noosa.Game;
import com.watabou.utils.GameSettings;
import com.watabou.utils.PathFinder;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M16b {@code remixed_full} builtin pack: a {@code default_enabled=false} alpha content pack that
 * exercises the M15 drop / spawn / shop / level APIs. When enabled, players should encounter a
 * small number of remixed-style Lua mobs, find Lua items/spells in the dungeon, and buy them from
 * a Lua shop. This test pins the four contracts that justify shipping it as a minimal playable
 * alpha:
 *
 * <ol>
 *   <li><b>enabled loads the full alpha manifest</b>: 10 items, 5 spells, 6 mobs, 1 shop, and the
 *       alpha hub level all register; balance overrides push Lua drops/spawns into the main game.</li>
 *   <li><b>balance overrides reach the runtime</b>: {@link BalanceConfig#LUA_ITEM_DROP_PROB},
 *       {@link BalanceConfig#LUA_SPELL_DROP_FIRST} and {@link BalanceConfig#LUA_MOB_SPAWN_PROB}
 *       reflect the values declared in {@code mod.json}.</li>
 *   <li><b>enabled content actually shows up in-game</b>: {@link Generator#random()} can emit Lua
 *       items/spells, and {@link MobSpawner#getMobRotation(int)} includes {@link LuaMobFactory}
 *       at some depths.</li>
 *   <li><b>disabled loads nothing and does not pollute vanilla</b>: toggling the mod off drops every
 *       remixed_full id and leaves balance constants at their defaults.</li>
 * </ol>
 */
public class RemixedFullPackTest {

    private static final int TEST_VERSION_CODE = 896;
    private static final String HUB_ASSET = "mods/levels/remixed_full_alpha_hub.json";
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
        // Make sure other mods are not polluting the exact-size assertions.
        ModRegistry.setEnabled("remished_lite", false);
        ModRegistry.setEnabled("test_mod", false);
        ModRegistry.setEnabled("regression_demo", false);
    }

    // ---------------- enabled: full alpha manifest registers ----------------

    @Test
    public void enabled_loadsFullAlphaManifest() throws Exception {
        enableRemixedFull();

        assertTrue("remixed_full must be explicitly enabled",
                ModRegistry.isEnabled("remixed_full"));
        assertFalse("remished_lite must be disabled for this test",
                ModRegistry.isEnabled("remished_lite"));

        LuaEngine.init();

        // 10 items: 5 weapons + 5 materials.
        assertTrue("hooked_dagger registered",
                LuaItemRegistry.contains("remixed_full_hooked_dagger"));
        assertTrue("battle_axe registered",
                LuaItemRegistry.contains("remixed_full_battle_axe"));
        assertTrue("lantern_blade registered",
                LuaItemRegistry.contains("remixed_full_lantern_blade"));
        assertTrue("mace registered",
                LuaItemRegistry.contains("remixed_full_mace"));
        assertTrue("kunai registered",
                LuaItemRegistry.contains("remixed_full_kunai"));
        assertTrue("remixed_ration registered",
                LuaItemRegistry.contains("remixed_full_remixed_ration"));
        assertTrue("rotten_organ registered",
                LuaItemRegistry.contains("remixed_full_rotten_organ"));
        assertTrue("dark_gold registered",
                LuaItemRegistry.contains("remixed_full_dark_gold"));
        assertTrue("toxic_gland registered",
                LuaItemRegistry.contains("remixed_full_toxic_gland"));
        assertTrue("rusty_coin registered",
                LuaItemRegistry.contains("remixed_full_rusty_coin"));

        // 5 spells.
        assertTrue("magic_arrow registered",
                LuaSpellRegistry.contains("remixed_full_magic_arrow"));
        assertTrue("heal registered",
                LuaSpellRegistry.contains("remixed_full_heal"));
        assertTrue("blink registered",
                LuaSpellRegistry.contains("remixed_full_blink"));
        assertTrue("iron_skin registered",
                LuaSpellRegistry.contains("remixed_full_iron_skin"));
        assertTrue("ignite registered",
                LuaSpellRegistry.contains("remixed_full_ignite"));

        // 6 mobs.
        assertTrue("kobold registered",
                LuaMobRegistry.contains("remixed_full_kobold"));
        assertTrue("black_rat registered",
                LuaMobRegistry.contains("remixed_full_black_rat"));
        assertTrue("hedgehog registered",
                LuaMobRegistry.contains("remixed_full_hedgehog"));
        assertTrue("cold_spirit registered",
                LuaMobRegistry.contains("remixed_full_cold_spirit"));
        assertTrue("fetid_rat registered",
                LuaMobRegistry.contains("remixed_full_fetid_rat"));
        assertTrue("bandit registered",
                LuaMobRegistry.contains("remixed_full_bandit"));

        // 1 shop + hub level.
        assertTrue("alpha shop registered",
                LuaShopRegistry.contains("remixed_full_alpha_shop"));
        assertTrue("alpha hub level registered",
                LuaLevelRegistry.contains("remixed_full_alpha_hub"));

        // M17c: tavern + chapel showcase levels register alongside the hub.
        assertTrue("tavern level registered",
                LuaLevelRegistry.contains("remixed_full_tavern"));
        assertTrue("chapel level registered",
                LuaLevelRegistry.contains("remixed_full_chapel"));

        // M17a: 6 town NPCs (degraded ports of remished town NPCs — dialog/yell only).
        assertTrue("drunkard NPC registered",
                LuaNpcRegistry.contains("remixed_full_drunkard"));
        assertTrue("bard NPC registered",
                LuaNpcRegistry.contains("remixed_full_bard"));
        assertTrue("black_cat NPC registered",
                LuaNpcRegistry.contains("remixed_full_black_cat"));
        assertTrue("barman NPC registered",
                LuaNpcRegistry.contains("remixed_full_barman"));
        assertTrue("bishop NPC registered",
                LuaNpcRegistry.contains("remixed_full_bishop"));
        assertTrue("inquirer NPC registered",
                LuaNpcRegistry.contains("remixed_full_inquirer"));

        assertEquals("10 items", 10, LuaItemRegistry.size());
        assertEquals("5 spells", 5, LuaSpellRegistry.size());
        assertEquals("6 mobs", 6, LuaMobRegistry.size());
        assertEquals("6 town NPCs", 6, LuaNpcRegistry.size());
        assertEquals("1 shop", 1, LuaShopRegistry.size());
    }

    // ---------------- M17a: town NPCs use only fork-supported APIs ----------------

    /**
     * M17a forbidden-token lint: the 6 town-NPC scripts must not reference any API the fork
     * does not expose (chooseOption / trade/quest/story windows / gold economy / Sfx-particle /
     * luajava). This pins the Acceptance clause "仅用 {showDialog,npcYell,giveItem,leaveTown} 子集,
     * 不依赖 fork 缺失 API" at the source-text level — a regression where a future edit reintroduces
     * a remished-only call (e.g. {@code chooseOption} on barman) is caught here even if the NPC
     * still registers. Mirrors the spirit of {@code LuaNpcTest#luajavaBindClassStillUnreachableWithNpcGlobalsInjected}.
     *
     * <p>Each script is read as raw text from the mod asset path and asserted free of the
     * forbidden tokens. Reading text (not executing) keeps the lint independent of whether the
     * engine init wired a given global — the point is to forbid the token from ever appearing.
     */
    @Test
    public void townNpcs_useOnlyForkSupportedApis() {
        String[] npcScripts = {
                "mods/remixed_full/scripts/npcs/drunkard.lua",
                "mods/remixed_full/scripts/npcs/bard.lua",
                "mods/remixed_full/scripts/npcs/black_cat.lua",
                "mods/remixed_full/scripts/npcs/barman.lua",
                "mods/remixed_full/scripts/npcs/bishop.lua",
                "mods/remixed_full/scripts/npcs/inquirer.lua",
        };
        // Fork LuaNpc has no dispatch for these (act/die/spawn/actionsList/execute are not routed),
        // and RPD.* lacks the rest (chooseOption / *Window / economy / Sfx-particle / luajava).
        // "Sfx" is matched as the dotted substring ".Sfx": remished only references it as
        // RPD.Sfx.*, so the dotted form catches the call without false-positive on unrelated
        // identifiers that merely contain the letters "Sfx". Other tokens are bare substrings.
        String[] forbidden = {
                "chooseOption", "showTradeWindow", "showQuestWindow", "showStoryWindow",
                "spendGold", "uncurse", "pourSpeck", "speckEffectFactory", "playExtra",
                "textById", "luajava", "playSound", ".Sfx", "setState", "setAi",
        };
        for (String path : npcScripts) {
            String code = stripLuaLineComments(Gdx.files.internal(path).readString("UTF-8"));
            for (String tok : forbidden) {
                assertFalse(
                        "town NPC " + path + " must not reference fork-missing API '" + tok
                                + "' (Acceptance: dialog/npcYell/giveItem/leaveTown subset only)",
                        code.contains(tok));
            }
        }
    }

    // Line-comment-only stripper. The 6 NPC files use "--" line comments exclusively (no
    // "--[[ ]]" blocks); a future block comment naming a forbidden token would not be
    // hidden — acceptable since the lint is best-effort and the sandbox is the stricter guard.
    private static String stripLuaLineComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        for (String line : src.split("\n", -1)) {
            int dash = line.indexOf("--");
            out.append(dash >= 0 ? line.substring(0, dash) : line).append('\n');
        }
        return out.toString();
    }

    // ---------------- balance overrides reach runtime ----------------

    @Test
    public void enabled_balanceOverridesReachRuntime() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        assertEquals("lua_item_drop_prob override applied",
                8f, BalanceConfig.LUA_ITEM_DROP_PROB, 0f);
        assertEquals("lua_spell_drop_prob applied to first draw",
                6f, BalanceConfig.LUA_SPELL_DROP_FIRST, 0f);
        assertEquals("lua_spell_drop_prob applied to second draw",
                6f, BalanceConfig.LUA_SPELL_DROP_SECOND, 0f);
        assertEquals("lua_mob_spawn_prob override applied",
                0.05f, BalanceConfig.LUA_MOB_SPAWN_PROB, 0f);
    }

    // ---------------- enabled content surfaces in main game ----------------

    @Test
    public void enabled_luaItemsDropFromDeck() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        int luaHits = 0;
        for (int i = 0; i < 80; i++) {
            Item item = Generator.random();
            assertNotNull(item);
            if (item instanceof LuaItem || item instanceof LuaMaterial || item instanceof LuaSpell) {
                luaHits++;
            }
        }
        assertTrue("Generator.random() must emit Lua content with remixed_full enabled (got "
                + luaHits + "/80)", luaHits >= 10);
    }

    @Test
    public void enabled_luaMobsEnterSpawnRotation() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        // Balance override is 0.05 (probabilistic). To keep the test deterministic, raise the
        // probability to 1.0 for the rotation check — this asserts that the Lua mob factory is
        // eligible for injection once balance opts in, without depending on RNG.
        BalanceConfig.LUA_MOB_SPAWN_PROB = 1f;

        boolean factorySeen = false;
        for (int depth = 1; depth <= 10; depth++) {
            if (MobSpawner.getMobRotation(depth).contains(LuaMobFactory.class)) {
                factorySeen = true;
                break;
            }
        }
        assertTrue("MobSpawner rotation must include LuaMobFactory with remixed_full enabled",
                factorySeen);
    }

    @Test
    public void enabled_luaSpellsDropFromDeck() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        int spellHits = 0;
        for (int i = 0; i < 80; i++) {
            Item item = Generator.random();
            assertNotNull(item);
            if (item instanceof LuaSpell) spellHits++;
        }
        assertTrue("Generator.random() must emit Lua spells (got " + spellHits + "/80)",
                spellHits >= 5);
    }

    // ---------------- hub level json: structurally valid + loadable ----------------

    @Test
    public void hubLevelAsset_isStructurallyValid() {
        JsonValue root = new JsonReader().parse(Gdx.files.internal(HUB_ASSET).readString("UTF-8"));
        assertEquals("remixed_full_alpha_hub", root.getString("id"));
        assertEquals(12, root.getInt("width"));
        assertEquals(12, root.getInt("height"));
        assertEquals(144, root.require("tiles").size);
        assertEquals(13, root.getInt("entrance"));
        assertEquals(130, root.getInt("exit"));
        assertTrue("safe hub", root.getBoolean("safe"));

        String[] tiles = tilesArray(root.require("tiles"));
        assertEquals("entrance tile", "entrance", tiles[13]);
        assertEquals("exit tile", "exit", tiles[130]);
        assertEquals("corner padding is wall", "wall", tiles[0]);
        assertEquals("corner padding is wall", "wall", tiles[143]);

        assertEquals("3 mob specs (shop + 2 mobs)", 3, root.require("mobs").size);
        assertEquals("3 item specs (gold + 2 items)", 3, root.require("items").size);

        DataDrivenLevel lvl = DataDrivenLevel.fromAsset(HUB_ASSET, "remixed_full_alpha_hub");
        assertNotNull("fromAsset must parse the hub without throwing", lvl);
    }

    // ---------------- M17c: tavern + chapel levels build cleanly + positions valid ----------------

    /**
     * M17c the two new showcase levels (tavern, chapel) are structurally valid JSON AND every
     * mob/item position lands on a passable, non-solid cell once the map is built. This is
     * stronger than the registry-level asserts in {@link #enabled_loadsFullAlphaManifest}: a mob
     * spec whose {@code pos} sits on a wall/bookshelf/statue is silently skipped at runtime
     * ({@code DataDrivenLevel.createMobs} logs and continues), so a pure registration test would
     * not catch it. Building the map via {@code build()} resolves every tile name, then each
     * mob/item cell is checked against the authoritative {@link Terrain#flags} for PASSABLE/SOLID.
     *
     * <p>Independent of Lua engine state: {@code build()} only needs the parsed JSON fields (it
     * does not call {@code createMobs}/{@code createItems}), so this runs without
     * {@code enableRemixedFull()}/{@code LuaEngine.init()}.
     */
    @Test
    public void m17c_levels_areStructurallyValidAndPositionsPassable() {
        String[][] levels = {
                {"mods/levels/remixed_full_tavern.json", "remixed_full_tavern"},
                {"mods/levels/remixed_full_chapel.json", "remixed_full_chapel"},
        };
        for (String[] meta : levels) {
            String asset = meta[0];
            String id = meta[1];

            JsonValue root = new JsonReader().parse(Gdx.files.internal(asset).readString("UTF-8"));
            assertEquals(id, root.getString("id"));
            assertEquals("16x16 width", 16, root.getInt("width"));
            assertEquals("16x16 height", 16, root.getInt("height"));
            assertEquals("256 tiles", 256, root.require("tiles").size);
            assertTrue("custom levels are safe/ephemeral", root.getBoolean("safe"));

            int entrance = root.getInt("entrance");
            int exit = root.getInt("exit");
            String[] tiles = tilesArray(root.require("tiles"));
            assertEquals("entrance cell carries an entrance tile", "entrance", tiles[entrance]);
            assertEquals("exit cell carries an exit tile", "exit", tiles[exit]);

            DataDrivenLevel built = DataDrivenLevel.fromAsset(asset, id);
            assertNotNull("fromAsset must parse " + id, built);
            // build() allocates map[] via setSize and resolves every tile name; if a tile name
            // were unknown it would have logged + defaulted to wall, and a length mismatch would
            // have thrown in fromJsonValue — so reaching here means the definition is sound.
            built.build();
            assertNotNull("map allocated for " + id, built.map);
            assertEquals("map length for " + id, 256, built.map.length);

            // Validate passability directly against the authoritative Terrain flags rather than
            // buildFlagMaps(), which also needs openSpace[] (not allocated by setSize) and a sized
            // PathFinder — engine bookkeeping irrelevant to this data-level invariant.
            assertTrue("entrance must be passable in " + id,
                    (Terrain.flags[built.map[entrance]] & Terrain.PASSABLE) != 0);
            assertTrue("exit must be passable in " + id,
                    (Terrain.flags[built.map[exit]] & Terrain.PASSABLE) != 0);

            JsonValue mobsArr = root.require("mobs");
            assertTrue(id + " should place at least one mob", mobsArr.size > 0);
            for (JsonValue m = mobsArr.child; m != null; m = m.next) {
                int pos = m.getInt("pos");
                int t = built.map[pos];
                assertTrue(id + " mob " + m.getString("type") + " @" + pos + " must be passable",
                        (Terrain.flags[t] & Terrain.PASSABLE) != 0);
                assertFalse(id + " mob " + m.getString("type") + " @" + pos + " must not be solid",
                        (Terrain.flags[t] & Terrain.SOLID) != 0);
            }
            JsonValue itemsArr = root.get("items");
            for (JsonValue it = itemsArr != null ? itemsArr.child : null; it != null; it = it.next) {
                int pos = it.getInt("pos");
                assertFalse(id + " item " + it.getString("type") + " @" + pos + " must not be solid",
                        (Terrain.flags[built.map[pos]] & Terrain.SOLID) != 0);
            }
        }
    }

    /**
     * M17c every npc/shop id referenced by the tavern + chapel JSONs exists in its registry once
     * remixed_full is enabled, so the levels have no dangling references at runtime.
     */
    @Test
    public void m17c_levels_referenceOnlyRegisteredNpcsAndShops() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        // Tavern: drunkard / bard / barman + the shared alpha shop.
        assertTrue("tavern drunkard NPC exists", LuaNpcRegistry.contains("remixed_full_drunkard"));
        assertTrue("tavern bard NPC exists",     LuaNpcRegistry.contains("remixed_full_bard"));
        assertTrue("tavern barman NPC exists",   LuaNpcRegistry.contains("remixed_full_barman"));
        assertTrue("tavern shop exists",         LuaShopRegistry.contains("remixed_full_alpha_shop"));

        // Chapel: bishop / inquirer / black_cat.
        assertTrue("chapel bishop NPC exists",    LuaNpcRegistry.contains("remixed_full_bishop"));
        assertTrue("chapel inquirer NPC exists",  LuaNpcRegistry.contains("remixed_full_inquirer"));
        assertTrue("chapel black_cat NPC exists", LuaNpcRegistry.contains("remixed_full_black_cat"));
    }

    // ---------------- disabled: registries empty + no vanilla pollution ----------------

    @Test
    public void disabled_loadsNothingAndStaysVanilla() throws Exception {
        ModRegistry.scanDir(ModTestSupport.realModsHandle());
        // remixed_full is default_enabled=false; leave it off.
        ModRegistry.setEnabled("remished_lite", false);
        ModRegistry.setEnabled("test_mod", false);
        ModRegistry.setEnabled("regression_demo", false);

        assertFalse("remixed_full must be disabled by default",
                ModRegistry.isEnabled("remixed_full"));

        LuaEngine.init();

        assertFalse("hooked_dagger absent when disabled",
                LuaItemRegistry.contains("remixed_full_hooked_dagger"));
        assertFalse("magic_arrow absent when disabled",
                LuaSpellRegistry.contains("remixed_full_magic_arrow"));
        assertFalse("kobold absent when disabled",
                LuaMobRegistry.contains("remixed_full_kobold"));
        assertFalse("alpha shop absent when disabled",
                LuaShopRegistry.contains("remixed_full_alpha_shop"));
        assertFalse("alpha hub absent when disabled",
                LuaLevelRegistry.contains("remixed_full_alpha_hub"));

        assertEquals("no items", 0, LuaItemRegistry.size());
        assertEquals("no spells", 0, LuaSpellRegistry.size());
        assertEquals("no mobs", 0, LuaMobRegistry.size());
        assertEquals("no npcs", 0, LuaNpcRegistry.size());
        assertEquals("no shops", 0, LuaShopRegistry.size());

        assertEquals("lua_item_drop_prob stays default 0",
                0f, BalanceConfig.LUA_ITEM_DROP_PROB, 0f);
        assertEquals("lua_spell_drop_first stays default 0",
                0f, BalanceConfig.LUA_SPELL_DROP_FIRST, 0f);
        assertEquals("lua_mob_spawn_prob stays default 0",
                0f, BalanceConfig.LUA_MOB_SPAWN_PROB, 0f);

        for (int depth = 1; depth <= 10; depth++) {
            assertFalse("spawn rotation must not include LuaMobFactory when disabled at depth " + depth,
                    MobSpawner.getMobRotation(depth).contains(LuaMobFactory.class));
        }

        for (int i = 0; i < 20; i++) {
            Item item = Generator.random();
            assertNotNull(item);
            assertFalse("vanilla deck must not emit Lua items/spells when disabled",
                    item instanceof LuaItem || item instanceof LuaMaterial || item instanceof LuaSpell);
        }
    }

    // ---------------- callbacks execute and use RPD APIs correctly ----------------

    @Test
    public void enabled_weaponAttackProcFiresAndAppliesBuff() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        LuaTable dagger = LuaItemRegistry.getTable("remixed_full_hooked_dagger");
        assertNotNull(dagger);

        Hero attacker = newHero();
        Hero defender = newHero();
        try {
            int baseDamage = 10;
            int result = LuaItemCallbacks.callOptInt(dagger, "attackProc", baseDamage,
                    LuaValue.valueOf(attacker.id()),
                    LuaValue.valueOf(defender.id()),
                    LuaValue.valueOf(baseDamage),
                    new LuaTable());

            assertEquals("attackProc must return base damage when present", baseDamage, result);
            assertNotNull("hooked_dagger attackProc must apply Bleeding via RPD.affectBuff",
                    defender.buff(Bleeding.class));
        } finally {
            Actor.remove(attacker);
            Actor.remove(defender);
        }
    }

    @Test
    public void enabled_selfSpellOnUseHealsHero() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        LuaTable heal = LuaSpellRegistry.getTable("remixed_full_heal");
        assertNotNull(heal);

        Hero hero = newHero();
        hero.HP = 10;
        try {
            LuaItemCallbacks.callOpt(heal, "onUse", LuaValue.valueOf(hero.id()));
            assertEquals("heal spell must restore 20 HP", 30, hero.HP);
        } finally {
            Actor.remove(hero);
        }
    }

    @Test
    public void enabled_blinkSpellPrefersTeleportOverHasteFallback() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        bindMinimalDungeon();

        LuaTable blink = LuaSpellRegistry.getTable("remixed_full_blink");
        assertNotNull(blink);

        // Branch A — a free adjacent cell exists: blink must take the teleport
        // branch and NOT fall back to Haste. The teleport itself routes through
        // ScrollOfTeleportation.appear's GL VFX (null sprite is headless-hostile),
        // so we assert the branch decision the Lua controls: no Haste => the
        // displacement path was selected over the fallback.
        Hero hero = newHero();
        hero.pos = 5 * 10 + 5; // cell 55, all 8 neighbours open in TestLevel
        try {
            LuaItemCallbacks.callOpt(blink, "onUse", LuaValue.valueOf(hero.id()));
            assertNull("blink must not apply Haste when an adjacent free cell exists",
                    hero.buff(Haste.class));
        } finally {
            Actor.remove(hero);
        }

        // Branch B — every adjacent cell blocked: blink must fall back to Haste,
        // proving the spell degrades gracefully instead of no-op'ing.
        Hero boxed = newHero();
        boxed.pos = 5 * 10 + 5;
        try {
            blockAllNeighbours(boxed.pos);
            LuaItemCallbacks.callOpt(blink, "onUse", LuaValue.valueOf(boxed.id()));
            assertNotNull("blink must apply Haste when no adjacent free cell exists",
                    boxed.buff(Haste.class));
        } finally {
            Actor.remove(boxed);
            unbindDungeon();
        }
    }

    @Test
    public void enabled_cellSpellMagicArrowDamagesTargetOnRay() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        bindMinimalDungeon();

        LuaTable magicArrow = LuaSpellRegistry.getTable("remixed_full_magic_arrow");
        assertNotNull(magicArrow);

        Hero hero = newHero();
        hero.pos = 5 * 10 + 5; // cell 55

        Hero target = newHero();
        target.HP = 50;
        target.pos = 5 * 10 + 8; // cell 58, same row, reachable by cellRay
        try {
            LuaItemCallbacks.callOpt(magicArrow, "onUseAt",
                    LuaValue.valueOf(hero.id()),
                    LuaValue.valueOf(target.pos));
            assertEquals("magic arrow must deal 8 damage to target on ray", 42, target.HP);
        } finally {
            Actor.remove(hero);
            Actor.remove(target);
            unbindDungeon();
        }
    }

    @Test
    public void enabled_mobAttackProcFiresAndReturnsBaseDamage() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        // RPD.damageChar routes through Char.damage, which reads Dungeon.hero
        // for AuraOfProtection and hero alignment checks. Provide a minimal
        // environment so the callback can deduct HP without NPE.
        bindMinimalDungeon();

        LuaTable bandit = LuaMobRegistry.getTable("remixed_full_bandit");
        assertNotNull(bandit);

        Hero self = newHero();
        Hero enemy = newHero();
        enemy.HP = 50;
        try {
            int baseDamage = 7;
            int result = LuaItemCallbacks.callOptInt(bandit, "attackProc", baseDamage,
                    LuaValue.valueOf(self.id()),
                    LuaValue.valueOf(enemy.id()),
                    LuaValue.valueOf(baseDamage));

            assertEquals("bandit attackProc must return base damage", baseDamage, result);
            assertEquals("bandit attackProc must deal 2 extra damage via RPD.damageChar",
                    48, enemy.HP);
        } finally {
            Actor.remove(self);
            Actor.remove(enemy);
            unbindDungeon();
        }
    }

    @Test
    public void enabled_mobDefenseProcFiresAndReturnsBaseDamage() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        bindMinimalDungeon();

        LuaTable hedgehog = LuaMobRegistry.getTable("remixed_full_hedgehog");
        assertNotNull(hedgehog);

        Hero self = newHero();
        Hero enemy = newHero();
        enemy.HP = 50;
        try {
            int baseDamage = 5;
            int result = LuaItemCallbacks.callOptInt(hedgehog, "defenseProc", baseDamage,
                    LuaValue.valueOf(self.id()),
                    LuaValue.valueOf(enemy.id()),
                    LuaValue.valueOf(baseDamage));

            assertEquals("hedgehog defenseProc must return base damage", baseDamage, result);
            assertEquals("hedgehog defenseProc must reflect 1 damage via RPD.damageChar",
                    49, enemy.HP);
        } finally {
            Actor.remove(self);
            Actor.remove(enemy);
            unbindDungeon();
        }
    }

    private static Hero newHero() {
        Hero hero = new Hero();
        hero.heroClass = HeroClass.WARRIOR;
        hero.HT = 50;
        hero.HP = hero.HT;
        hero.damageInterrupt = false;
        Actor.add(hero);
        return hero;
    }

    private static Level savedLevel;
    private static Hero savedHero;

    private static void bindMinimalDungeon() {
        savedHero = Dungeon.hero;
        savedLevel = Dungeon.level;
        if (Dungeon.hero == null) {
            Dungeon.hero = new Hero();
            Dungeon.hero.heroClass = HeroClass.WARRIOR;
            Dungeon.hero.HT = 50;
            Dungeon.hero.HP = 50;
        }
        if (Dungeon.level == null || !(Dungeon.level instanceof TestLevel)) {
            Dungeon.level = new TestLevel();
        }
        // LuaMob.findEmptyNextTo reads PathFinder.NEIGHBOURS8, which is only sized
        // by setMapSize; size it to the TestLevel grid so the blink spell resolves
        // adjacent cells instead of silently falling back.
        PathFinder.setMapSize(Dungeon.level.width(), Dungeon.level.height());
    }

    private static void unbindDungeon() {
        Dungeon.hero = savedHero;
        Dungeon.level = savedLevel;
    }

    /** Mark every NEIGHBOURS8 cell of {@code pos} non-passable so the blink
     *  spell finds no empty adjacent cell and must fall back to Haste. */
    private static void blockAllNeighbours(int pos) {
        Level lvl = Dungeon.level;
        for (int offset : PathFinder.NEIGHBOURS8) {
            int cell = pos + offset;
            if (lvl.insideMap(cell)) {
                lvl.passable[cell] = false;
            }
        }
    }

    /**
     * Minimal 10x10 level for headless callback tests: all interior cells are
     * passable EMPTY, border is solid. Enough for RPD.teleportChar, cellRay,
     * charAtCell and damageChar to operate without triggering null Dungeon.level.
     * Flag arrays are populated by hand instead of via buildFlagMaps(), which
     * pulls in PathFinder/openSpace bookkeeping that has no value here and trips
     * over headless static-init state.
     */
    private static final class TestLevel extends Level {
        TestLevel() {
            width = 10;
            height = 10;
            length = width * height;
            map = new int[length];
            passable = new boolean[length];
            losBlocking = new boolean[length];
            flamable = new boolean[length];
            secret = new boolean[length];
            solid = new boolean[length];
            avoid = new boolean[length];
            water = new boolean[length];
            pit = new boolean[length];
            heroFOV = new boolean[length];
            visited = new boolean[length];
            mapped = new boolean[length];
            discoverable = new boolean[length];
            openSpace = new boolean[length];
            blobs = new java.util.HashMap<>();
            mobs = new java.util.HashSet<>();
            heaps = new com.watabou.utils.SparseArray<>();
            plants = new com.watabou.utils.SparseArray<>();
            traps = new com.watabou.utils.SparseArray<>();
            transitions = new java.util.ArrayList<>();
            customTiles = new java.util.ArrayList<>();
            customWalls = new java.util.ArrayList<>();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = y * width + x;
                    boolean border = x == 0 || y == 0 || x == width - 1 || y == height - 1;
                    map[i] = Terrain.EMPTY;
                    passable[i] = !border;
                    solid[i] = border;
                    losBlocking[i] = border;
                    openSpace[i] = !border;
                    heroFOV[i] = true;
                }
            }
        }

        @Override
        protected boolean build() {
            return true;
        }

        @Override
        protected void createMobs() { }

        @Override
        protected void createItems() { }
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
