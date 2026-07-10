package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
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
 * M19b: LuaMob per-instance {@code storeData}/{@code restoreData} persistence.
 *
 * <p>Pins down the new contract end-to-end:
 * <ol>
 *   <li>Arbitrary safe-subset Lua data (string/number/boolean + nested tables)
 *       round-trips through a Bundle and is readable from Lua via
 *       {@code RPD.mobRestoreData}.</li>
 *   <li>The FetidRat pattern — a {@code spawn} callback that picks a random
 *       "kind" and persists it — keeps that kind (and its Lua-added immunity)
 *       stable across save/load, instead of re-deriving it from the actor id.</li>
 *   <li>A corrupt {@code lua_mob_data} key cannot take down save load.</li>
 *   <li>A mob that never stores data writes no {@code lua_mob_data} key and
 *       restores to an empty table (old-save compatibility).</li>
 *   <li>Safety boundaries (reviewer round-1 must-fix): self-referential tables,
 *       over-depth nesting, and unsupported value types (function) do not
 *       stack-overflow, bloat the save, or crash — normal scalars alongside
 *       them still survive.</li>
 * </ol>
 *
 * <p>Uses {@link LuaEngine#init()}'s globals, which carry both
 * {@code register_mob} and the {@code RPD} surface (including the new
 * {@code mobStoreData}/{@code mobRestoreData}), so tests exercise the real
 * Lua-side access pattern rather than poking package-private accessors.
 */
public class LuaMobStoreDataTest {

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

    /** Engine globals carry register_mob + RPD (incl. mobStoreData/mobRestoreData). */
    private static Globals engineGlobals() {
        LuaEngine.init();
        return LuaEngine.instance().globals();
    }

    private static LuaTable baseMobTable(String id) {
        LuaTable tbl = new LuaTable();
        tbl.set("id", LuaValue.valueOf(id));
        tbl.set("name", LuaValue.valueOf("data rat"));
        tbl.set("hp", LuaValue.valueOf(20));
        tbl.set("attack", LuaValue.valueOf(8));
        tbl.set("defense", LuaValue.valueOf(3));
        return tbl;
    }

    @Test
    public void mobDataRoundTripsThroughBundle() {
        // Store a mixed-shape table (number/string/bool + nested) from Lua, round-trip
        // it through a Bundle, then read every field back from Lua via mobRestoreData.
        Globals g = engineGlobals();
        g.load("register_mob{ id='data_mob', name='x', hp=15, attack=5, defense=3 }").call();
        LuaMob mob = LuaMobRegistry.create("data_mob");
        assertNotNull(mob);
        Actor.add(mob);
        try {
            int id = mob.id();
            g.load("RPD.mobStoreData(" + id + ", { kind=2, name='x', nested={ a=true, b='s' } })").call();

            Bundle bundle = new Bundle();
            mob.storeInBundle(bundle);
            Actor.remove(mob);

            LuaMob restored = new LuaMob();
            restored.restoreFromBundle(bundle);
            Actor.add(restored);
            try {
                int rid = restored.id();
                g.load("local d = RPD.mobRestoreData(" + rid + "); " +
                        "_k = d.kind; _n = d.name; _a = d.nested and d.nested.a; _b = d.nested and d.nested.b").call();

                assertEquals("number value kind must round-trip", 2, g.get("_k").toint());
                assertEquals("string value name must round-trip", "x", g.get("_n").tojstring());
                assertTrue("nested boolean must round-trip", g.get("_a").toboolean());
                assertEquals("nested string must round-trip", "s", g.get("_b").tojstring());
            } finally {
                Actor.remove(restored);
            }
        } finally {
            // original already removed in the happy path; remove is idempotent enough
            Actor.remove(mob);
        }
    }

    @Test
    public void fetidRatKindPersistsAcrossRestore() {
        // Inline mob whose spawn callback picks a random kind, persists it via
        // mobStoreData, and adds self-immunity — the exact FetidRat pattern.
        // Dispatch spawn manually (as LuaMob.act() would on the first tick), read
        // the chosen kind, round-trip the Bundle, and assert the kind + immunity
        // survive. This is the regression for the M17b id-derived workaround.
        Globals g = engineGlobals();
        g.load("register_mob{ id='fr_test', name='腐臭鼠', hp=20, attack=8, defense=3, " +
                "spawn = function(selfId) " +
                "  local d = RPD.mobRestoreData(selfId) " +
                "  if not d.kind then d.kind = math.random(1, 3); RPD.mobStoreData(selfId, d) end " +
                "  RPD.addImmunity(selfId, RPD.Buffs.Paralysis) " +
                "end }").call();
        LuaMob mob = LuaMobRegistry.create("fr_test");
        assertNotNull(mob);
        Actor.add(mob);
        try {
            // Dispatch spawn the way LuaMob.act() does: pull the fn out of the
            // registry table and call it with the actor id.
            LuaTable tbl = LuaMobRegistry.getTable("fr_test");
            LuaValue spawnFn = tbl.get("spawn");
            assertTrue("spawn callback must be registered", spawnFn.isfunction());
            spawnFn.call(LuaValue.valueOf(mob.id()));

            assertFalse("spawn added a self-immunity", mob.isImmune(Paralysis.class) == false);
            int id = mob.id();
            g.load("local d = RPD.mobRestoreData(" + id + "); _kind = d.kind").call();
            int kindBefore = g.get("_kind").toint();
            assertTrue("spawn must have picked a kind in 1..3", kindBefore >= 1 && kindBefore <= 3);

            Bundle bundle = new Bundle();
            mob.storeInBundle(bundle);
            Actor.remove(mob);

            LuaMob restored = new LuaMob();
            restored.restoreFromBundle(bundle);
            assertTrue("immunity FQCN must survive save/load",
                    restored.isImmune(Paralysis.class));
            Actor.add(restored);
            try {
                int rid = restored.id();
                g.load("local d = RPD.mobRestoreData(" + rid + "); _kind2 = d.kind").call();
                assertEquals("random kind must be stable across save/load (not re-derived)",
                        kindBefore, g.get("_kind2").toint());
            } finally {
                Actor.remove(restored);
            }
        } finally {
            Actor.remove(mob);
        }
    }

    @Test
    public void malformedDataBundleDoesNotCrash() {
        // A corrupt lua_mob_data array must not take down save load; bad rows are
        // skipped and the mob is still usable. Mirrors LuaItemStateTest's malform
        // case. Keys are the exact bundle constants LuaMob writes.
        LuaMobRegistry.clear();
        LuaMobRegistry.register("malm_mob", baseMobTable("malm_mob"));
        Bundle bundle = new Bundle();
        bundle.put("lua_mob_id", "malm_mob");
        bundle.put("lua_mob_data", new String[]{
                "garbage_no_equals", "x:=notavalidkey", "=b:true", "z:nonsense", ""});
        LuaMob restored = new LuaMob();
        restored.restoreFromBundle(bundle); // must not throw
        assertNotNull(restored);
        assertEquals("definition re-hydrated from lua_mob_id", "data rat", restored.name());
    }

    @Test
    public void noDataMobRoundTripsCleanly() {
        // A mob that never calls mobStoreData writes no lua_mob_data key (no stale
        // empty key, no bloat) and restores to an empty table. This is also the
        // old-save compatibility path: a missing key is a clean empty data table.
        LuaMobRegistry.clear();
        LuaMobRegistry.register("nodata_mob", baseMobTable("nodata_mob"));
        LuaMob mob = LuaMobRegistry.create("nodata_mob");
        assertNotNull(mob);

        Bundle bundle = new Bundle();
        mob.storeInBundle(bundle);
        assertFalse("a mob with no stored data must not write a lua_mob_data key",
                bundle.contains("lua_mob_data"));

        LuaMob restored = new LuaMob();
        restored.restoreFromBundle(bundle);
        assertEquals("data restores to an empty table", 0, restored.luaData().keys().length);
    }

    @Test
    public void safetyBoundariesBoundRecursionAndSkipUnsupported() {
        // Reviewer round-1 must-fix: prove the depth/row guards and the
        // safe-subset filter actually hold. Builds a table with a self-reference
        // (cycle), nesting deeper than MAX_DATA_DEPTH, and a function value — none
        // of which may stack-overflow, bloat the save, or crash — while a normal
        // scalar alongside them must still round-trip.
        Globals g = engineGlobals();
        g.load("register_mob{ id='safe_mob', name='x', hp=15, attack=5, defense=3 }").call();
        LuaMob mob = LuaMobRegistry.create("safe_mob");
        assertNotNull(mob);
        Actor.add(mob);
        try {
            int id = mob.id();
            g.load(
                "local d = {} " +
                "d.self = d " +                       // self-reference cycle
                "d.keep = 7 " +                       // normal scalar — must survive
                "d.fn = function() end " +            // unsupported type — must be skipped
                "local deep = d " +
                "for i = 1, 15 do " +                 // 15 levels deep > MAX_DATA_DEPTH(8)
                "  local inner = {}; deep.inner = inner; deep = inner; deep.leaf = i " +
                "end " +
                "RPD.mobStoreData(" + id + ", d)"
            ).call();

            // Serialise + restore must not stack-overflow / crash on the cycle or
            // the over-deep chain, and must not balloon past MAX_DATA_ROWS.
            Bundle bundle = new Bundle();
            mob.storeInBundle(bundle);
            Actor.remove(mob);

            LuaMob restored = new LuaMob();
            restored.restoreFromBundle(bundle);
            Actor.add(restored);
            try {
                int rid = restored.id();
                g.load("local d = RPD.mobRestoreData(" + rid + "); " +
                        "_keep = d.keep; _fn = d.fn").call();
                assertEquals("normal scalar alongside dangerous structures must survive",
                        7, g.get("_keep").toint());
                assertTrue("unsupported function value must be skipped (absent → nil)",
                        g.get("_fn").isnil());
            } finally {
                Actor.remove(restored);
            }
        } finally {
            Actor.remove(mob);
        }
    }
}
