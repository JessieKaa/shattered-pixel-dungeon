package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.watabou.noosa.Game;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M10a: registration smoke test for the 15 Remished item ports. Drives the real
 * {@link LuaEngine#init()} script scan so every new {@code register_item} table
 * is exercised end-to-end (parse → hydrate → register). Asserts three things:
 * <ol>
 *   <li>all 15 ids land in {@link LuaItemRegistry};</li>
 *   <li>each hydrates a real name (not the {@code "???"} degraded fallback that
 *       signals a missing/failed Lua table);</li>
 *   <li>material vs weapon classification matches the PLAN (6 stackable
 *       materials vs 9 weapon/shield/mask items) so {@link LuaItemPool} keeps
 *       them in the correct drop pool.</li>
 * </ol>
 *
 * <p>Combat/eat/drBonus callbacks are degraded (M10c) and are not driven here —
 * a live Char + level is needed for those paths and is covered by the desktop
 * debug run, per the fork's testing convention.
 */
public class M10aItemsRegisteredTest {

    private static HeadlessApplication application;
    private static int savedVersionCode;

    private static final String[] MATERIAL_IDS = {
            "raw_fish", "fried_fish", "frozen_fish", "rotten_fish",
            "tengu_liver", "vile_essence",
    };

    private static final String[] WEAPON_IDS = {
            "bone_saw", "remixed_pickaxe", "tomahawk2",
            "wooden_shield", "tough_shield", "strong_shield", "royal_shield",
            "chaos_shield", "plague_doctor_mask",
    };

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

    @Test
    public void allM10aItemsRegisterAndHydrate() {
        LuaEngine.init();

        for (String id : MATERIAL_IDS) {
            assertTrue("material id should register: " + id, LuaItemRegistry.contains(id));
            assertNotNull(LuaItemRegistry.createItem(id));
        }
        for (String id : WEAPON_IDS) {
            assertTrue("weapon/shield/mask id should register: " + id, LuaItemRegistry.contains(id));
            assertNotNull(LuaItemRegistry.createItem(id));
        }
    }

    @Test
    public void namesAreNotDegraded() {
        LuaEngine.init();

        String[] all = new String[MATERIAL_IDS.length + WEAPON_IDS.length];
        System.arraycopy(MATERIAL_IDS, 0, all, 0, MATERIAL_IDS.length);
        System.arraycopy(WEAPON_IDS, 0, all, MATERIAL_IDS.length, WEAPON_IDS.length);

        for (String id : all) {
            String name = LuaItemRegistry.createItem(id).name();
            assertNotNull(name);
            assertFalse("name must hydrate from Lua, not stay degraded: " + id + " → " + name,
                    name.startsWith("???"));
        }
    }

    @Test
    public void materialClassificationMatchesPlan() {
        LuaEngine.init();

        for (String id : MATERIAL_IDS) {
            assertTrue("PLAN says this is a stackable material: " + id,
                    LuaItemRegistry.isMaterial(id));
        }
        for (String id : WEAPON_IDS) {
            assertFalse("PLAN says this is a non-material (weapon/shield/mask): " + id,
                    LuaItemRegistry.isMaterial(id));
        }
    }

    @Test
    public void boneSawAndMaskCallbacksPresent() {
        // The two items with real (non-degraded) callbacks must carry the function
        // fields the Java side dispatches, so a typo doesn't silently no-op them.
        // (Executing them needs a live Char/level — desktop debug run territory.)
        LuaEngine.init();

        org.luaj.vm2.LuaTable boneSaw = LuaItemRegistry.getTable("bone_saw");
        assertNotNull(boneSaw);
        assertTrue("bone_saw attackProc must be a function",
                boneSaw.get("attackProc").isfunction());

        org.luaj.vm2.LuaTable mask = LuaItemRegistry.getTable("plague_doctor_mask");
        assertNotNull(mask);
        assertTrue("mask onEquip must be a function", mask.get("onEquip").isfunction());
        assertTrue("mask onDeactivate must be a function",
                mask.get("onDeactivate").isfunction());
    }

    @Test
    public void shieldItemEquipCallbacksPresentAndDegradedFieldsRemoved() {
        // M11a: shields drive behavior through item onEquip -> permanent buff guards.
        LuaEngine.init();

        for (String id : new String[]{"wooden_shield", "tough_shield", "strong_shield",
                "royal_shield", "chaos_shield"}) {
            org.luaj.vm2.LuaTable t = LuaItemRegistry.getTable(id);
            assertNotNull(id + " should be registered", t);
            assertTrue(id + " onEquip must be a function", t.get("onEquip").isfunction());
            assertTrue(id + " onDeactivate must be a function", t.get("onDeactivate").isfunction());
            assertFalse(id + " item drBonus should be removed (now in guard buff)",
                    t.get("drBonus").isfunction());
        }

        org.luaj.vm2.LuaTable chaos = LuaItemRegistry.getTable("chaos_shield");
        assertFalse("chaos_shield ownerDoesDamage should be removed (now in guard buff)",
                chaos.get("ownerDoesDamage").isfunction());
        assertFalse("chaos_shield ownerTakesDamage should be removed (now in guard buff)",
                chaos.get("ownerTakesDamage").isfunction());
    }

    @Test
    public void shieldItemCallbacksAttachAndDetachGuardBuffs() throws Exception {
        // M11a glue test: directly invoke the Lua item callbacks (like LuaItem.activate would)
        // and assert the corresponding guard buff is attached/removed.
        LuaEngine.init();

        com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero hero =
                new com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero();
        hero.HT = 50; hero.HP = 50;
        hero.pos = 0;
        com.shatteredpixel.shatteredpixeldungeon.actors.Actor.add(hero);
        try {
            String[][] pairs = {
                    {"wooden_shield", "wooden_shield_guard"},
                    {"tough_shield", "tough_shield_guard"},
                    {"strong_shield", "strong_shield_guard"},
                    {"royal_shield", "royal_shield_guard"},
                    {"chaos_shield", "chaos_shield_guard"},
            };
            for (String[] pair : pairs) {
                String itemId = pair[0];
                String guardId = pair[1];
                org.luaj.vm2.LuaTable item = LuaItemRegistry.getTable(itemId);
                assertNotNull(item);

                LuaItemCallbacks.callOpt(item, "onEquip", LuaItemCallbacks.arg(hero.id()));
                assertNotNull(itemId + " onEquip should attach " + guardId, findGuard(hero, guardId));

                LuaItemCallbacks.callOpt(item, "onDeactivate", LuaItemCallbacks.arg(hero.id()));
                assertNull(itemId + " onDeactivate should detach " + guardId, findGuard(hero, guardId));
            }
        } finally {
            com.shatteredpixel.shatteredpixeldungeon.actors.Actor.remove(hero);
        }
    }

    private static LuaBuff findGuard(com.shatteredpixel.shatteredpixeldungeon.actors.Char c, String id) {
        for (com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff b : c.buffs(LuaBuff.class)) {
            if (((LuaBuff) b).sameLuaId(id)) return (LuaBuff) b;
        }
        return null;
    }
}
