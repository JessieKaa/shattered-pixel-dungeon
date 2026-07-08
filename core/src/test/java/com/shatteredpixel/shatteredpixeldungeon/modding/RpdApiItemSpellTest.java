package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.utils.JsonReader;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.watabou.noosa.Game;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M6d item/spell API coverage. Pins down the narrow id/index/cell surface added
 * to {@link RpdApi} for inventory + ScriptedThief + spell helpers, and the
 * sandbox boundary it must keep (no Java object/userdata crosses to Lua,
 * luajava stays stripped).
 *
 * <p>The live {@code execute}/{@code GameScene.add} paths need a real scene and
 * are verified by desktop run + code review; headless coverage targets the API
 * contract and bad-input guards.
 */
public class RpdApiItemSpellTest {

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

    @Before
    public void resetState() throws Exception {
        ModTestSupport.enableTestMod();
        ModTestSupport.resetLuaState();
        RpdApi.resetGiveQuota();
        Dungeon.level = null;
        Dungeon.hero = null;
    }

    @AfterClass
    public static void shutdown() {
        Game.versionCode = savedVersionCode;
        Game.version = savedVersion;
        try { if (application != null) application.exit(); } catch (Throwable ignored) { }
    }

    private Globals globals() {
        LuaEngine.init();
        return LuaEngine.instance().globals();
    }

    // ---- no Lua-side createItem, no object/userdata returns ----

    @Test
    public void noLuaSideCreateItemHandle() {
        Globals g = globals();
        // M6d plan issue #1: createItem must not exist on RPD (would leak an Item).
        LuaValue createItem = g.get("RPD").get("createItem");
        assertTrue("RPD.createItem must NOT be exposed (object-handle leak)",
                createItem.isnil());
    }

    @Test
    public void giveItemReturnsBoolNotUserdata() {
        Globals g = globals();
        Hero hero = newHero();
        Dungeon.hero = hero;
        try {
            LuaValue r = g.load("return RPD.giveItem(" + hero.id() + ", 'rotten_organ', 3)").call();
            assertTrue("giveItem returns a bool", r.isboolean());
            assertTrue("giveItem collected the material", r.toboolean());
            assertFalse("return is not userdata (no object handle)", r.isuserdata());
        } finally {
            Dungeon.hero = null;
        }
    }

    @Test
    public void giveItemRejectsBadInputWithoutThrowing() {
        Globals g = globals();
        Hero hero = newHero();
        Dungeon.hero = hero;
        try {
            assertTrue("non-hero char → nil", g.load("return RPD.giveItem(999999, 'rotten_organ', 1)").call().isnil());
            assertTrue("unknown item → nil", g.load("return RPD.giveItem(" + hero.id() + ", 'nope', 1)").call().isnil());
            assertTrue("bad qty → nil", g.load("return RPD.giveItem(" + hero.id() + ", 'rotten_organ', 0)").call().isnil());
            assertTrue("non-string id → nil", g.load("return RPD.giveItem(" + hero.id() + ", 5, 1)").call().isnil());
        } finally {
            Dungeon.hero = null;
        }
    }

    // ---- M6e balance #1: giveItem per-hero-per-depth quota ----

    @Test
    public void giveItemQuotaBlocksRunawaySpam() {
        Globals g = globals();
        Hero hero = newHero();
        Dungeon.hero = hero;
        try {
            // First call within the per-depth cap collects fine.
            LuaValue r1 = g.load("return RPD.giveItem(" + hero.id() + ", 'rotten_organ', 15)").call();
            assertTrue("within cap → collected", r1.isboolean() && r1.toboolean());

            // Second call would push the running total past the cap → refused with false
            // (distinct from bad-input nil). This is the guard that breaks an infinite giveItem loop.
            LuaValue r2 = g.load("return RPD.giveItem(" + hero.id() + ", 'rotten_organ', 10)").call();
            assertTrue("over cap → boolean false (not nil)", r2.isboolean());
            assertFalse("over cap → refused", r2.toboolean());

            // resetGiveQuota clears the running total so the same call succeeds again.
            RpdApi.resetGiveQuota();
            LuaValue r3 = g.load("return RPD.giveItem(" + hero.id() + ", 'rotten_organ', 10)").call();
            assertTrue("after reset → collected again", r3.isboolean() && r3.toboolean());
        } finally {
            Dungeon.hero = null;
        }
    }

    // ---- randomBackpackItem / itemName / removeBackpackItem (index API) ----

    @Test
    public void randomBackpackItemReturnsIndexAndName() {
        Globals g = globals();
        Hero hero = newHero();
        Dungeon.hero = hero;
        try {
            g.load("RPD.giveItem(" + hero.id() + ", 'rotten_organ', 2)").call();
            g.load("RPD.giveItem(" + hero.id() + ", 'bone_shard', 1)").call();

            LuaValue idx = g.load("return RPD.randomBackpackItem(" + hero.id() + ")").call();
            assertTrue("randomBackpackItem returns a 1-based index", idx.isint());
            int i = idx.toint();
            assertTrue("index in range", i >= 1 && i <= hero.belongings.backpack.items.size());

            LuaValue name = g.load("return RPD.itemName(" + hero.id() + ", " + i + ")").call();
            assertTrue("itemName returns a string", name.isstring());
            assertFalse("not userdata", name.isuserdata());

            assertTrue("out-of-range index → nil",
                    g.load("return RPD.itemName(" + hero.id() + ", 99999)").call().isnil());
        } finally {
            Dungeon.hero = null;
        }
    }

    @Test
    public void removeBackpackItemDetachesByQuantity() {
        Globals g = globals();
        Hero hero = newHero();
        Dungeon.hero = hero;
        try {
            g.load("RPD.giveItem(" + hero.id() + ", 'rotten_organ', 3)").call();
            int idx = g.load("return RPD.randomBackpackItem(" + hero.id() + ")").call().toint();
            Item before = hero.belongings.backpack.items.get(idx - 1);
            int qtyBefore = before.quantity();

            LuaValue r = g.load("return RPD.removeBackpackItem(" + hero.id() + ", " + idx + ", 2)").call();
            assertTrue(r.isboolean() && r.toboolean());

            assertEquals("detach decremented by 2 (not hand-written quantity--)",
                    qtyBefore - 2, before.quantity());
        } finally {
            Dungeon.hero = null;
        }
    }

    // ---- stealRandomItem + Mob.createLoot() closed loop ----

    @Test
    public void stealRandomItemDropsOnDeathViaCreateLoot() {
        Globals g = globals();
        Hero hero = newHero();
        Dungeon.hero = hero;
        try {
            g.load("RPD.giveItem(" + hero.id() + ", 'rotten_organ', 2)").call();
            LuaMobRegistry.clear();
            LuaMobRegistry.register("thief_mob", baseMobTable("thief_mob"));
            LuaMob mob = LuaMobRegistry.create("thief_mob");
            Actor.add(mob);
            try {
                LuaValue stolen = g.load("return RPD.stealRandomItem(" + mob.id() + ", " + hero.id() + ")").call();
                assertTrue("steal returns the stolen item name", stolen.isstring());
                assertFalse("not userdata", stolen.isuserdata());

                Item loot = mob.createLoot();
                assertNotNull("createLoot returns the stolen item", loot);
                assertEquals("腐烂器官", loot.name());

                LuaValue name = g.load("return RPD.stolenLootName(" + mob.id() + ")").call();
                assertTrue("stolenLootName returns the held item name", name.isstring());
            } finally {
                Actor.remove(mob);
            }
        } finally {
            Dungeon.hero = null;
        }
    }

    // ---- cell API guards ----

    @Test
    public void cellRayNilWhenLevelNull() {
        Globals g = globals();
        // Dungeon.level is null in headless @Before.
        assertTrue(g.load("return RPD.cellRay(0, 5)").call().isnil());
        assertTrue("teleportChar guards null level",
                g.load("return RPD.teleportChar(0, 0)").call().isnil());
    }

    @Test
    public void cellRayReturnsIntTableWithLevel() {
        Globals g = globals();
        Dungeon.level = buildTestLevel();
        try {
            LuaValue ray = g.load("return RPD.cellRay(17, 34)").call();
            assertTrue("cellRay returns a table", ray.istable());
            assertTrue("ray is non-empty", ray.checktable().len().toint() >= 1);
            assertFalse("ray is not userdata", ray.isuserdata());
            LuaValue zap = g.load("return RPD.zapEffect(17, 34)").call();
            assertTrue("zapEffect returns true for valid cells", zap.isboolean() && zap.toboolean());
        } finally {
            Dungeon.level = null;
        }
    }

    @Test
    public void spawnMobNearRejectsBadInput() {
        Globals g = globals();
        assertTrue("unknown mob → nil", g.load("return RPD.spawnMobNear('nope', 0)").call().isnil());
        assertTrue("non-string → nil", g.load("return RPD.spawnMobNear(5, 0)").call().isnil());
        assertTrue("non-int cell → nil", g.load("return RPD.spawnMobNear('test_mob', 'x')").call().isnil());
    }

    // ---- M11c terrain/dig/dropItem API ----

    @Test
    public void terrainApiNilWhenLevelNull() {
        Globals g = globals();
        assertTrue("terrain guards null level", g.load("return RPD.terrain(0)").call().isnil());
        assertTrue("setTerrain guards null level", g.load("return RPD.setTerrain(0, 1)").call().isnil());
        assertTrue("isWall guards null level", g.load("return RPD.isWall(0)").call().isnil());
        assertTrue("isSolid guards null level", g.load("return RPD.isSolid(0)").call().isnil());
        assertTrue("dig guards null level", g.load("return RPD.dig(0)").call().isnil());
        assertTrue("dropItem guards null level", g.load("return RPD.dropItem(0, 'dark_gold', 1)").call().isnil());
    }

    @Test
    public void terrainReadAndFlags() {
        Globals g = globals();
        Dungeon.level = buildTestLevel();
        try {
            // use an interior wall cell (not the outermost border)
            int wallCell = 16 + 1; // x=1,y=1 => still wall because y==1? No, y=1 not border. Let's use x=1,y=0? y=0 is border excluded.
            // Actually border cells are excluded by insideMap. Use a wall cell just inside: x=1,y=1 is floor. x=1,y=14 is floor.
            // DataDrivenLevel may not create interior walls. Place a wall manually at an interior cell.
            int interiorCell = 16 + 2; // x=2,y=1 (y=1 is second row, not border)
            Dungeon.level.map[interiorCell] = com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.WALL;
            Dungeon.level.updateCellFlags(interiorCell);

            LuaValue t = g.load("return RPD.terrain(" + interiorCell + ")").call();
            assertTrue("terrain returns int, got " + t.typename(), t.isint());
            assertEquals("interior wall terrain", com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.WALL, t.toint());
            assertTrue("wall cell is solid", g.load("return RPD.isSolid(" + interiorCell + ")").call().toboolean());
            assertTrue("wall cell is wall", g.load("return RPD.isWall(" + interiorCell + ")").call().toboolean());

            // cell 17 is floor/entrance
            assertFalse("floor is not solid", g.load("return RPD.isSolid(17)").call().toboolean());
            assertFalse("floor is not wall", g.load("return RPD.isWall(17)").call().toboolean());
        } finally {
            Dungeon.level = null;
        }
    }

    @Test
    public void setTerrainChangesTile() {
        Globals g = globals();
        Dungeon.level = buildTestLevel();
        try {
            // cell 18 is an interior floor (EMPTY) — a whitelisted source; cell 17
            // is ENTRANCE and is reserved for the protected-source test below.
            int floorCell = 18;
            assertEquals(com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.EMPTY, Dungeon.level.map[floorCell]);
            LuaValue ok = g.load("return RPD.setTerrain(" + floorCell + ", "
                    + com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.EMBERS + ")").call();
            assertTrue(ok.isboolean() && ok.toboolean());
            assertEquals(com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.EMBERS, Dungeon.level.map[floorCell]);
            assertFalse(Dungeon.level.solid[floorCell]);
        } finally {
            Dungeon.level = null;
        }
    }

    @Test
    public void setTerrainRejectsProtectedAndBadTarget() {
        Globals g = globals();
        Dungeon.level = buildTestLevel();
        try {
            int entrance = com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.ENTRANCE;
            int embers = com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.EMBERS;
            int empty = com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.EMPTY;
            int wall = com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.WALL;

            // cell 17 is ENTRANCE — a protected source. Repainting it to a safe
            // target must be rejected so a script cannot erase transitions/exits.
            LuaValue onEntrance = g.load("return RPD.setTerrain(17, " + embers + ")").call();
            assertFalse("entrance is a protected source", onEntrance.isboolean() && onEntrance.toboolean());
            assertEquals("entrance unchanged", entrance, Dungeon.level.map[17]);

            // a wall source is also protected (structural).
            int wallCell = 16 + 2;
            Dungeon.level.map[wallCell] = wall;
            Dungeon.level.updateCellFlags(wallCell);
            LuaValue onWall = g.load("return RPD.setTerrain(" + wallCell + ", " + empty + ")").call();
            assertFalse("wall is a protected source", onWall.isboolean() && onWall.toboolean());
            assertEquals("wall unchanged", wall, Dungeon.level.map[wallCell]);

            // non-whitelisted target on a safe source is rejected too.
            LuaValue badTarget = g.load("return RPD.setTerrain(18, " + wall + ")").call();
            assertFalse("wall is not a whitelisted target", badTarget.isboolean() && badTarget.toboolean());
        } finally {
            Dungeon.level = null;
        }
    }

    @Test
    public void levelWidthReturnsActualWidth() {
        Globals g = globals();
        assertTrue("levelWidth guards null level", g.load("return RPD.levelWidth()").call().isnil());
        Dungeon.level = buildTestLevel();
        try {
            LuaValue w = g.load("return RPD.levelWidth()").call();
            assertTrue("levelWidth returns int", w.isint());
            assertEquals(16, w.toint());
            assertFalse("not userdata", w.isuserdata());
        } finally {
            Dungeon.level = null;
        }
    }

    @Test
    public void digOnlyAffectsWhitelist() {
        Globals g = globals();
        Dungeon.level = buildTestLevel();
        try {
            int interiorWall = 16 + 2;
            Dungeon.level.map[interiorWall] = com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.WALL;
            Dungeon.level.updateCellFlags(interiorWall);

            LuaValue wall = g.load("return RPD.dig(" + interiorWall + ")").call();
            assertTrue("dig wall → true", wall.isboolean() && wall.toboolean());
            assertEquals(com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.EMPTY, Dungeon.level.map[interiorWall]);

            LuaValue floor = g.load("return RPD.dig(17)").call();
            assertTrue("dig floor → false (non-diggable)", floor.isboolean() && !floor.toboolean());
            assertEquals(com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.ENTRANCE, Dungeon.level.map[17]);
        } finally {
            Dungeon.level = null;
        }
    }

    @Test
    public void dropItemCreatesHeapOnFloor() {
        Globals g = globals();
        Dungeon.level = buildTestLevel();
        try {
            LuaValue ok = g.load("return RPD.dropItem(17, 'rotten_organ', 3)").call();
            assertTrue(ok.isboolean() && ok.toboolean());
            com.shatteredpixel.shatteredpixeldungeon.items.Heap heap = Dungeon.level.heaps.get(17);
            assertNotNull(heap);
            assertEquals("heap.pos must match the drop cell (else save/load misplaces it)", 17, heap.pos);
            assertEquals("腐烂器官", heap.peek().name());
            assertEquals(3, heap.peek().quantity());
        } finally {
            Dungeon.level = null;
        }
    }

    @Test
    public void dropItemRejectsBadInput() {
        Globals g = globals();
        Dungeon.level = buildTestLevel();
        try {
            assertTrue("unknown item → nil", g.load("return RPD.dropItem(17, 'nope', 1)").call().isnil());
            assertTrue("bad qty → nil", g.load("return RPD.dropItem(17, 'rotten_organ', 0)").call().isnil());
            assertTrue("non-string item → nil", g.load("return RPD.dropItem(17, 5, 1)").call().isnil());
        } finally {
            Dungeon.level = null;
        }
    }

    // ---- M1 sandbox regression ----

    @Test
    public void luajavaStillStrippedWithM6dApiPresent() {
        Globals g = globals();
        LuaValue ok = g.load(
                "return pcall(function() return luajava.bindClass('java.lang.Runtime') end)"
        ).call();
        assertFalse("luajava.bindClass must still fail with M6d API present", ok.toboolean());
        assertTrue("luajava global itself must remain stripped", g.get("luajava").isnil());
    }

    // ---- helpers ----

    private static Hero newHero() {
        Hero hero = new Hero();
        hero.HT = 30;
        hero.HP = 30;
        hero.belongings = new com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings(hero);
        Actor.add(hero);
        return hero;
    }

    private static LuaTable baseMobTable(String id) {
        LuaTable tbl = new LuaTable();
        tbl.set("id", LuaValue.valueOf(id));
        tbl.set("name", LuaValue.valueOf("thief"));
        tbl.set("hp", LuaValue.valueOf(20));
        tbl.set("attack", LuaValue.valueOf(8));
        tbl.set("defense", LuaValue.valueOf(3));
        return tbl;
    }

    private static com.shatteredpixel.shatteredpixeldungeon.levels.Level buildTestLevel() {
        StringBuilder tiles = new StringBuilder();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (tiles.length() > 0) tiles.append(',');
                int pos = x + y * 16;
                if (pos == 17) tiles.append("'entrance'");
                else if (x == 0 || x == 15 || y == 0 || y == 15) tiles.append("'wall'");
                else tiles.append("'floor'");
            }
        }
        String json = "{'width':16,'height':16,'tiles':[" + tiles + "],'entrance':17}";
        DataDrivenLevel lvl = DataDrivenLevel.fromJsonValue(
                new JsonReader().parse(json.replace('\'', '"')), "m6d_item_spell_test");
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
        return lvl;
    }
}
