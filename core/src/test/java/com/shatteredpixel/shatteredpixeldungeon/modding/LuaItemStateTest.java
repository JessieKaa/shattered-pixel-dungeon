package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
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
 * M11c: LuaItem per-instance state Bundle round-trip.
 */
public class LuaItemStateTest {

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

    private static LuaItem item() {
        LuaEngine.init();
        LuaItem item = LuaItemRegistry.create("remixed_pickaxe");
        assertNotNull(item);
        return item;
    }

    private static Hero newHero() {
        Hero hero = new Hero();
        hero.heroClass = HeroClass.WARRIOR;
        Talent.initClassTalents(hero);
        hero.belongings = new Belongings(hero);
        Actor.add(hero);
        return hero;
    }

    @Test
    public void bloodStainedStateRoundTripsThroughBundle() {
        // Inline weapon whose MARK_BLOOD action mutates per-instance state and
        // whose glowing callback reads it — exercises the full state-persist
        // path (serializeState/loadState) that the pickaxe's bloodStained relies on.
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        g.load("register_item{ id='test_blood_weapon', name='x', tier=1, " +
                "actions={'MARK_BLOOD'}, " +
                "execute=function(heroId, action, state) state.bloodStained = true end, " +
                "glowing=function(state) if state.bloodStained then return {color=0xAA0000} end end }").call();
        LuaItem wpn = (LuaItem) LuaItemRegistry.create("test_blood_weapon");
        Hero hero = newHero();

        assertNull("not blood-stained yet", wpn.glowing());
        wpn.execute(hero, "MARK_BLOOD");
        assertNotNull("glowing once blood-stained", wpn.glowing());

        Bundle bundle = new Bundle();
        wpn.storeInBundle(bundle);
        LuaItem restored = new LuaItem();
        assertNull("fresh instance must not glow", restored.glowing());
        restored.restoreFromBundle(bundle);
        assertNotNull("bloodStained must survive a bundle round-trip", restored.glowing());
    }

    @Test
    public void numericAndNestedKeysRoundTripThroughBundle() {
        // Numeric keys must round-trip as numbers (not collapse to string "1"),
        // otherwise Lua arrays (ipairs/#[...]) break. Probes are written from Lua
        // so the test exercises the real Lua-side access pattern.
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        g.load("register_item{ id='test_state_weapon', name='x', tier=1, " +
                "actions={'SEED','CHECK'}, " +
                "execute=function(heroId, action, state) " +
                "  if action=='SEED' then state.arr={'a','b'}; state.nested={x=1,y=true} " +
                "  elseif action=='CHECK' then " +
                "    _p1=(state.arr and state.arr[1]) or 'MISS'; " +
                "    _p2=(state.arr and state.arr[2]) or 'MISS'; " +
                "    _px=(state.nested and state.nested.x) or -999; " +
                "    _py=(state.nested and state.nested.y==true) " +
                "  end end }").call();
        LuaItem wpn = (LuaItem) LuaItemRegistry.create("test_state_weapon");
        Hero hero = newHero();

        wpn.execute(hero, "SEED");
        Bundle bundle = new Bundle();
        wpn.storeInBundle(bundle);
        LuaItem restored = new LuaItem();
        restored.restoreFromBundle(bundle);
        restored.execute(hero, "CHECK");

        assertEquals("numeric key arr[1] must survive round-trip", "a", g.get("_p1").tojstring());
        assertEquals("numeric key arr[2] must survive round-trip", "b", g.get("_p2").tojstring());
        assertEquals("nested numeric value must survive", 1, g.get("_px").toint());
        assertTrue("nested boolean value must survive", g.get("_py").toboolean());
    }

    @Test
    public void malformedStateBundleDoesNotCrash() {
        // A corrupt/corrupted lua_item_state must not take down save load.
        LuaEngine.init();
        Bundle bundle = new Bundle();
        bundle.put("lua_item_id", "remixed_pickaxe");
        bundle.put("lua_item_state", new String[]{
                "garbage_no_equals", "x:=notavalidkey", "=b:true", "z:nonsense", ""});
        LuaItem restored = new LuaItem();
        restored.restoreFromBundle(bundle); // must not throw
        assertNotNull(restored);
        // the item is still usable: default action resolves normally.
        assertNotNull(restored.defaultAction());
    }

    @Test
    public void pickaxeMineDigsAdjacentWall() {
        LuaItem pick = item();
        Hero hero = newHero();
        Dungeon.hero = hero;
        hero.pos = 17; // interior entrance cell

        // Minimal 16x16 level: wall border, floor interior, entrance at cell 17.
        com.badlogic.gdx.utils.JsonReader reader = new com.badlogic.gdx.utils.JsonReader();
        StringBuilder tiles = new StringBuilder();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (tiles.length() > 0) tiles.append(',');
                int pos = x + y * 16;
                if (pos == 17) tiles.append("\"entrance\"");
                else if (x == 0 || x == 15 || y == 0 || y == 15) tiles.append("\"wall\"");
                else tiles.append("\"floor\"");
            }
        }
        String json = "{\"width\":16,\"height\":16,\"tiles\":[" + tiles + "],\"entrance\":17}";
        DataDrivenLevel lvl = DataDrivenLevel.fromJsonValue(reader.parse(json), "m11c_pickaxe_e2e");
        lvl.build();
        lvl.mobs = new java.util.HashSet<>();
        lvl.heaps = new com.watabou.utils.SparseArray<>();
        lvl.blobs = new java.util.HashMap<>();
        lvl.plants = new com.watabou.utils.SparseArray<>();
        lvl.traps = new com.watabou.utils.SparseArray<>();
        lvl.customTiles = new java.util.ArrayList<>();
        lvl.customWalls = new java.util.ArrayList<>();
        lvl.visited = new boolean[lvl.length()];
        lvl.mapped = new boolean[lvl.length()];
        lvl.transitions = new java.util.ArrayList<>();
        lvl.buildFlagMaps();
        Dungeon.level = lvl;

        // place a diggable wall directly east of the hero (cell 18)
        int wallCell = hero.pos + 1;
        lvl.map[wallCell] = Terrain.WALL;
        lvl.updateCellFlags(wallCell);

        hero.belongings.backpack.items.add(pick);

        try {
            pick.execute(hero, "MINE");
            assertEquals("MINE should dig the adjacent wall to EMPTY",
                    Terrain.EMPTY, lvl.map[wallCell]);
        } finally {
            Dungeon.level = null;
            Dungeon.hero = null;
        }
    }

    @Test
    public void bundleRoundTripPreservesActionListAndDefault() {
        LuaItem pick = item();
        Bundle bundle = new Bundle();
        pick.storeInBundle(bundle);
        LuaItem restored = new LuaItem();
        restored.restoreFromBundle(bundle);
        assertTrue(restored.actions(newHero()).contains("MINE"));
        assertEquals("EQUIP", restored.defaultAction());
    }
}
