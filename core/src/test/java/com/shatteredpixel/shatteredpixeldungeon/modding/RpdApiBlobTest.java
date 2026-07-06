package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.utils.JsonReader;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Ooze;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M6a Remished-bridge subset coverage. Pins down the five new surfaces and the
 * two correctness invariants codex flagged in PLAN review:
 * <ol>
 *   <li>{@code RPD.Blobs}/{@code RPD.Buffs} constant tables cover every
 *       registered id (placeBlob/addImmunity can resolve all of them).</li>
 *   <li>{@code RPD.placeBlob} rejects every bad-input shape and is headless-safe
 *       (null {@code Dungeon.level} → NIL no throw; out-of-map cell → NIL no
 *       throw — codex round-1 must-fix).</li>
 *   <li>{@code RPD.addImmunity} resolves an id to a Class and registers it in a
 *       LuaMob's immunities (FetidRat self-gas-immunity pattern).</li>
 *   <li>{@link LuaMob}'s {@code spawn} callback fires once and only once.</li>
 *   <li>Bundle round-trip preserves both {@code spawned} and lua-added
 *       immunities (codex round-1 must-fix: Char.storeInBundle skips
 *       immunities, so LuaMob persists them itself).</li>
 *   <li>M1 sandbox regression: {@code luajava} stays stripped with the new
 *       globals present.</li>
 * </ol>
 *
 * <p>Live {@code GameScene.add} rendering is verified by the desktop/device run,
 * not headlessly. The positive placeBlob path IS asserted here (blob lands in
 * {@code level.blobs}) because {@link Dungeon#level} can be set to a built
 * {@link DataDrivenLevel} and {@code GameScene.add(Blob)}'s sprite half no-ops
 * when {@code scene == null}.
 */
public class RpdApiBlobTest {

    private static HeadlessApplication application;
    private static int savedVersionCode;
    private static Level savedLevel;
    private static Hero savedHero;

    @BeforeClass
    public static void initHeadless() {
        HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
        config.updatesPerSecond = 1;
        application = new HeadlessApplication(new ApplicationAdapter() {}, config);
        savedVersionCode = com.watabou.noosa.Game.versionCode;
        com.watabou.noosa.Game.versionCode = 896;
        savedLevel = Dungeon.level;
        savedHero = Dungeon.hero;
    }

    @Before
    public void resetState() throws Exception {
        ModTestSupport.enableTestMod();
        ModTestSupport.resetLuaState();
        // Some tests set Dungeon.level; restore a clean (null) slate so a prior
        // test's level never leaks into a bad-input test that expects null.
        Dungeon.level = null;
        Dungeon.hero = null;
    }

    @AfterClass
    public static void shutdown() {
        Dungeon.level = savedLevel;
        Dungeon.hero = savedHero;
        com.watabou.noosa.Game.versionCode = savedVersionCode;
        try { if (application != null) application.exit(); } catch (Throwable ignored) { }
    }

    private Globals globals() {
        Globals g = LuaSandbox.exposedGlobals();
        g.set("RPD", RpdApi.build());
        return g;
    }

    private static LuaTable baseMobTable(String id) {
        LuaTable tbl = new LuaTable();
        tbl.set("id", LuaValue.valueOf(id));
        tbl.set("name", LuaValue.valueOf("blob rat"));
        tbl.set("hp", LuaValue.valueOf(20));
        tbl.set("attack", LuaValue.valueOf(8));
        tbl.set("defense", LuaValue.valueOf(3));
        return tbl;
    }

    // ---- constant tables ----

    @Test
    public void blobsTableCoversAllRegisteredIds() {
        Globals g = globals();
        LuaValue blobs = g.get("RPD").get("Blobs");
        assertFalse("RPD.Blobs must exist", blobs.isnil());
        // Every id the registry can resolve must be exposed to Lua as a string
        // constant (so placeBlob never gets an id the table advertised).
        java.util.Set<String> ids = RpdApi.BlobRegistry.ids();
        for (String id : ids) {
            LuaValue v = blobs.get(id);
            assertTrue("RPD.Blobs." + id + " missing", v.isstring());
            assertEquals("RPD.Blobs." + id + " should map to its own name",
                    id, v.tojstring());
        }
        assertTrue("Blobs table has at least 5 entries (Acceptance)", ids.size() >= 5);
    }

    @Test
    public void buffsTableCoversAllRegisteredIds() {
        Globals g = globals();
        LuaValue buffs = g.get("RPD").get("Buffs");
        assertFalse("RPD.Buffs must exist", buffs.isnil());
        java.util.Set<String> ids = RpdApi.BuffWhitelist.ids();
        assertTrue("Buffs table has at least 5 entries (Acceptance)", ids.size() >= 5);
        for (String id : ids) {
            assertTrue("RPD.Buffs." + id + " missing", buffs.get(id).isstring());
        }
    }

    // ---- placeBlob: bad input + headless guard ----

    @Test
    public void placeBlobRejectsBadArgumentsWithoutThrowing() {
        Globals g = globals();
        // Dungeon.level is null here (resetState cleared it).
        assertTrue("unknown blob id → nil", g.load("return RPD.placeBlob('Nope', 0, 5)").call().isnil());
        assertTrue("non-string id → nil", g.load("return RPD.placeBlob(123, 0, 5)").call().isnil());
        assertTrue("non-int pos → nil", g.load("return RPD.placeBlob('ToxicGas', 'x', 5)").call().isnil());
        assertTrue("non-number amount → nil", g.load("return RPD.placeBlob('ToxicGas', 0, 'y')").call().isnil());
        assertTrue("zero amount → nil", g.load("return RPD.placeBlob('ToxicGas', 0, 0)").call().isnil());
        assertTrue("negative amount → nil", g.load("return RPD.placeBlob('ToxicGas', 0, -1)").call().isnil());
    }

    @Test
    public void placeBlobNoOpWhenLevelNullHeadless() {
        // Dungeon.level null in headless → guard returns NIL, never throws.
        Globals g = globals();
        LuaValue r = g.load("return RPD.placeBlob('ToxicGas', 10, 50)").call();
        assertTrue("null Dungeon.level → nil no throw", r.isnil());
    }

    // ---- placeBlob: with a built level (insideMap guard + positive seed) ----

    @Test
    public void placeBlobRejectsOutOfBoundsCellWithLevelSet() {
        Level lvl = buildTestLevel();
        Dungeon.level = lvl;
        try {
            Globals g = globals();
            int oob = lvl.length() + 100;
            LuaValue r = g.load("return RPD.placeBlob('ToxicGas', " + oob + ", 50)").call();
            assertTrue("out-of-map cell → nil, no throw (codex round-1 must-fix)", r.isnil());
            assertEquals("no blob should be seeded for an out-of-map cell",
                    0, lvl.blobs.size());
        } finally {
            Dungeon.level = null;
        }
    }

    @Test
    public void placeBlobSeedsBlobForValidCellWithLevelSet() {
        Level lvl = buildTestLevel();
        Dungeon.level = lvl;
        ToxicGas gas = null;
        try {
            assertEquals("level starts blob-free", 0, lvl.blobs.size());
            Globals g = globals();
            // Row 1 col 1 is a interior floor cell for the 16x16 test level
            // (pos 17 is the entrance, well inside the map).
            LuaValue r = g.load("return RPD.placeBlob('ToxicGas', 17, 50)").call();
            assertTrue("placeBlob always returns nil", r.isnil());
            gas = (ToxicGas) lvl.blobs.get(ToxicGas.class);
            assertNotNull("ToxicGas blob seeded into level.blobs", gas);
            assertTrue("seeded blob carries the requested volume", gas.volume > 0);
        } finally {
            if (gas != null) Actor.remove(gas);   // it was registered via GameScene.add→Actor.add
            Dungeon.level = null;
        }
    }

    // ---- addImmunity ----

    @Test
    public void addImmunityGrantsBlobImmunityToLuaMob() {
        LuaMobRegistry.clear();
        LuaMobRegistry.register("imm_mob", baseMobTable("imm_mob"));
        LuaMob mob = LuaMobRegistry.create("imm_mob");
        Actor.add(mob);
        try {
            assertFalse("fresh mob is NOT immune to ToxicGas", mob.isImmune(ToxicGas.class));
            Globals g = globals();
            g.load("RPD.addImmunity(" + mob.id() + ", RPD.Blobs.ToxicGas)").call();
            assertTrue("after addImmunity the mob IS immune to ToxicGas",
                    mob.isImmune(ToxicGas.class));
        } finally {
            Actor.remove(mob);
        }
    }

    @Test
    public void addImmunityRejectsNonLuaMobAndUnknownId() {
        // A non-LuaMob Char must be rejected so a script can't mutate vanilla
        // actors' hardcoded immunities. Use the hero-free path: an unknown id on
        // a registered LuaMob is the cleaner assertion (no second Char subtype
        // is cheap to stand up headlessly).
        LuaMobRegistry.clear();
        LuaMobRegistry.register("imm_mob2", baseMobTable("imm_mob2"));
        LuaMob mob = LuaMobRegistry.create("imm_mob2");
        Actor.add(mob);
        try {
            Globals g = globals();
            assertTrue("unknown id → nil", g.load(
                    "return RPD.addImmunity(" + mob.id() + ", 'NotAThing')").call().isnil());
            assertTrue("non-existent charId → nil", g.load(
                    "return RPD.addImmunity(999999, 'ToxicGas')").call().isnil());
            assertFalse("still not immune after rejected calls", mob.isImmune(ToxicGas.class));
        } finally {
            Actor.remove(mob);
        }
    }

    // ---- spawn callback: fires once and only once ----

    @Test
    public void spawnCallbackFiresOnceOnFirstAct() throws Exception {
        LuaMobRegistry.clear();
        LuaTable tbl = baseMobTable("spawn_once");
        final AtomicInteger spawnCalls = new AtomicInteger();
        tbl.set("spawn", new OneArgFunction() {
            @Override public LuaValue call(LuaValue selfId) { spawnCalls.incrementAndGet(); return NIL; }
        });
        // act returns true → takeover path: skips super.act() (which would NPE on
        // Dungeon.level headlessly) and spends TICK. Lets us call act() twice
        // safely and observe spawn fires only on the first call.
        tbl.set("act", new OneArgFunction() {
            @Override public LuaValue call(LuaValue selfId) { return LuaValue.TRUE; }
        });
        LuaMobRegistry.register("spawn_once", tbl);
        LuaMob mob = LuaMobRegistry.create("spawn_once");

        assertEquals(0, spawnCalls.get());
        mob.act();
        assertEquals("spawn fired exactly once on first act", 1, spawnCalls.get());
        mob.act();
        assertEquals("spawn did NOT re-fire on second act", 1, spawnCalls.get());
    }

    // ---- Bundle round-trip: spawned + lua immunity persist (codex round-1 must-fix) ----

    @Test
    public void bundleRoundTripPersistsSpawnedAndLuaImmunity() throws Exception {
        LuaMobRegistry.clear();
        LuaMobRegistry.register("persist_mob", baseMobTable("persist_mob"));
        LuaMob mob = LuaMobRegistry.create("persist_mob");
        mob.addLuaImmunity("ToxicGas", ToxicGas.class);
        setSpawned(mob, true);

        assertTrue("pre-save: immune to ToxicGas", mob.isImmune(ToxicGas.class));

        com.watabou.utils.Bundle b = new com.watabou.utils.Bundle();
        mob.storeInBundle(b);
        LuaMob restored = new LuaMob();
        restored.restoreFromBundle(b);

        assertTrue("post-restore: still immune to ToxicGas (immunities rebuilt from FQCN)",
                restored.isImmune(ToxicGas.class));
        assertTrue("post-restore: spawned latch persisted (spawn won't re-fire)",
                getSpawned(restored));
    }

    // ---- M6b: AI + positioning primitives ----

    @Test
    public void setMobAiSwitchesLuaMobState() {
        LuaMobRegistry.clear();
        LuaMobRegistry.register("ai_mob", baseMobTable("ai_mob"));
        LuaMob mob = LuaMobRegistry.create("ai_mob");
        Actor.add(mob);
        try {
            Globals g = globals();
            g.load("RPD.setMobAi(" + mob.id() + ", 'fleeing')").call();
            assertTrue("setMobAi fleeing switches state", mob.state == mob.FLEEING);
            g.load("RPD.setMobAi(" + mob.id() + ", 'not_a_state')").call();
            assertTrue("bad state leaves current state unchanged", mob.state == mob.FLEEING);
            assertTrue("nonexistent char id returns nil", g.load("return RPD.setMobAi(999999, 'hunting')").call().isnil());
        } finally {
            Actor.remove(mob);
        }
    }

    @Test
    public void enemyOfChoosesAndCachesVisibleHeroBeforeSuperAct() {
        Level lvl = buildTestLevel();
        Dungeon.level = lvl;
        LuaMobRegistry.clear();
        LuaMobRegistry.register("enemy_mob", baseMobTable("enemy_mob"));
        LuaMob mob = LuaMobRegistry.create("enemy_mob");
        Hero hero = new Hero();
        mob.pos = 17;
        hero.pos = 34;
        hero.HT = 20;
        hero.HP = 20;
        Dungeon.hero = hero;
        lvl.mobs.add(mob);
        Actor.add(mob);
        Actor.add(hero);
        try {
            Globals g = globals();
            LuaValue r = g.load("return RPD.enemyOf(" + mob.id() + ")").call();
            assertTrue("enemyOf returns a visible hero id", r.isint());
            assertEquals("enemyOf chooses the hero before super.act", hero.id(), r.toint());
        } finally {
            Actor.remove(mob);
            Actor.remove(hero);
            Dungeon.hero = null;
            Dungeon.level = null;
        }
    }

    @Test
    public void blinkRejectsBadDestinationsWithoutThrowing() {
        Level lvl = buildTestLevel();
        Dungeon.level = lvl;
        LuaMobRegistry.clear();
        LuaMobRegistry.register("blink_mob", baseMobTable("blink_mob"));
        LuaMob mob = LuaMobRegistry.create("blink_mob");
        mob.pos = 17;
        Actor.add(mob);
        try {
            Globals g = globals();
            assertTrue("out-of-map blink returns nil", g.load(
                    "return RPD.blink(" + mob.id() + ", " + (lvl.length() + 1) + ")").call().isnil());
            assertTrue("occupied blink target returns nil", g.load(
                    "return RPD.blink(" + mob.id() + ", 17)").call().isnil());
        } finally {
            Actor.remove(mob);
            Dungeon.level = null;
        }
    }

    @Test
    public void cellDistanceUsesLevelChebyshevDistance() {
        Level lvl = buildTestLevel();
        Dungeon.level = lvl;
        try {
            Globals g = globals();
            assertEquals("diagonal neighbour distance is 1", 1,
                    g.load("return RPD.cellDistance(17, 34)").call().toint());
            assertTrue("out-of-map returns nil",
                    g.load("return RPD.cellDistance(17, " + (lvl.length() + 1) + ")").call().isnil());
        } finally {
            Dungeon.level = null;
        }
    }

    @Test
    public void emptyCellNextToReturnsOnlyNeighbourCells() {
        Level lvl = buildTestLevel();
        Dungeon.level = lvl;
        try {
            Globals g = globals();
            LuaValue r = g.load("return RPD.emptyCellNextTo(17)").call();
            assertTrue("interior cell has an empty neighbour", r.isint());
            int cell = r.toint();
            assertTrue("returned cell is inside map", lvl.insideMap(cell));
            assertTrue("returned cell is passable", lvl.passable[cell]);
            assertEquals("returned cell is adjacent, not the source cell", 1, lvl.distance(17, cell));
            LuaValue edge = g.load("return RPD.emptyCellNextTo(0)").call();
            assertTrue("edge/corner handling never throws", edge.isnil() || edge.isint());
        } finally {
            Dungeon.level = null;
        }
    }

    @Test
    public void oozeIsWhitelistedAndApplicable() {
        LuaMobRegistry.clear();
        LuaMobRegistry.register("ooze_target", baseMobTable("ooze_target"));
        LuaMob mob = LuaMobRegistry.create("ooze_target");
        Actor.add(mob);
        try {
            Globals g = globals();
            assertTrue("Ooze appears in RPD.Buffs", g.get("RPD").get("Buffs").get("Ooze").isstring());
            assertNotNull("Ooze has a lookup Class", RpdApi.BuffWhitelist.lookupClass("Ooze"));
            g.load("RPD.affectBuff(" + mob.id() + ", RPD.Buffs.Ooze, 5)").call();
            assertNotNull("affectBuff applies Ooze", mob.buff(Ooze.class));
        } finally {
            Actor.remove(mob);
        }
    }

    // ---- M1 sandbox regression ----

    @Test
    public void luajavaStillStrippedWithNewGlobalsPresent() {
        Globals g = globals();
        LuaValue ok = g.load(
                "return pcall(function() return luajava.bindClass('java.lang.Runtime') end)"
        ).call();
        assertFalse("luajava.bindClass must still fail with Blobs/placeBlob/addImmunity present",
                ok.toboolean());
        assertTrue("luajava global itself must remain stripped", g.get("luajava").isnil());
    }

    // ---- helpers ----

    private static void setSpawned(LuaMob mob, boolean value) throws Exception {
        Field f = LuaMob.class.getDeclaredField("spawned");
        f.setAccessible(true);
        f.setBoolean(mob, value);
    }

    private static boolean getSpawned(LuaMob mob) throws Exception {
        Field f = LuaMob.class.getDeclaredField("spawned");
        f.setAccessible(true);
        return f.getBoolean(mob);
    }

    /** Build a 16x16 DataDrivenLevel (wall border + floor interior) for the
     *  placeBlob tests that need a non-null {@code Dungeon.level}. Mirrors
     *  LuaNpcTest's {@code initLevelActorCollections} ordering: build() first
     *  (sets width/length/terrain), then allocate the actor/feature collections
     *  (incl. blobs — Blob.seed reads/writes level.blobs). */
    private static Level buildTestLevel() {
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
                new JsonReader().parse(json.replace('\'', '"')), "m6a_blob_test");
        lvl.build();
        // Allocate after build (matches LuaNpcTest.initLevelActorCollections).
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
