package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.watabou.noosa.Game;
import com.watabou.utils.GameSettings;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M11e regression baseline: the {@code regression_demo} builtin mod exercises the
 * full M6-M11 Lua modding API surface in one place — every {@code register_*} global
 * (item/material/mob/buff/spell/talent/level/painter/trap) plus the M11c RPD API
 * (dig/dropItem/terrain/ray/effect/damage/shield/mana). This test enables ONLY
 * regression_demo, runs {@link LuaEngine#init()}, and asserts every coverage-matrix
 * element landed in the right registry with well-typed callback fields — the
 * "一键回归" contract: green here means the M6-M11 platform API has not regressed.
 *
 * <p>Why the field-type assertions: {@code register_buff}/{@code register_item}
 * accept arbitrary tables and never validate callback field names, so a typo like
 * {@code attaackProc} would silently register a buff whose combat hook never fires.
 * Asserting {@code getTable(id).get("attackProc").isfunction()} catches that class
 * of bug at load time. (Java dispatch is covered by {@code RpdApiBuffTest} /
 * {@code LuaItemCallbackTest}; this test guards the Lua spelling, which only this
 * mod's own scripts exercise end-to-end across every subsystem.)
 *
 * <p>Setup mirrors {@link DemoM58LoadTest}: a libgdx {@link HeadlessApplication}
 * with {@code Game.versionCode=896} (version gate admits the mod) and
 * {@code Game.version="test"} (the on_upgrade giveItem path hits Document.<clinit>
 * -> DeviceCompat.isDebug which needs a non-null Game.version), with a fresh
 * HashMap-backed Preferences per test for {@code mod_enabled_*} isolation.
 */
public class RegressionDemoModTest {

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
        // Fresh prefs + cleared registries every test so the disabled case observes
        // genuinely empty registries, not whatever a prior test left behind.
        GameSettings.set(new ModTestSupport.FakePreferences());
        ModRegistry.resetForTests();
        ModTestSupport.resetLuaState();
    }

    private void enableRegressionDemo() throws Exception {
        ModRegistry.scanDir(ModTestSupport.realModsHandle());
        ModRegistry.setEnabled("regression_demo", true);
    }

    // ---------------- enabled: full coverage matrix registers ----------------

    @Test
    public void loadEnabled_registersFullCoverageMatrix() throws Exception {
        enableRegressionDemo();

        LuaEngine.init();

        // ---- item (M6d weapon): LuaItem with M11c action/state/glowing layer ----
        assertTrue("pickaxe registered", LuaItemRegistry.contains("regression_demo_pickaxe"));
        LuaTable pickaxe = LuaItemRegistry.getTable("regression_demo_pickaxe");
        assertFunction(pickaxe, "execute");
        assertFunction(pickaxe, "glowing");
        assertFunction(pickaxe, "defaultAction");
        assertFunction(pickaxe, "onEquip");
        assertFunction(pickaxe, "onDeactivate");
        assertFunction(pickaxe, "attackProc");
        assertEquals("pickaxe tier=3 (drift guard)", 3, pickaxe.get("tier").optint(0));

        // ---- material (M11b): LuaMaterial EAT/onThrow/burnTransform ----
        assertTrue("ration registered", LuaItemRegistry.contains("regression_demo_ration"));
        LuaTable ration = LuaItemRegistry.getTable("regression_demo_ration");
        assertEquals("ration routed as material", "material", ration.get("type").tojstring());
        assertFunction(ration, "onEat");
        assertFunction(ration, "onThrow");
        assertEquals("burnTransform target is the ash item",
                "regression_demo_ash", ration.get("burnTransform").tojstring());
        assertTrue("ash (burn target) registered", LuaItemRegistry.contains("regression_demo_ash"));
        assertEquals("3 items (pickaxe + ration + ash)", 3, LuaItemRegistry.size());

        // ---- mob (M6b) ----
        assertTrue("rat registered", LuaMobRegistry.contains("regression_demo_rat"));
        assertEquals("1 mob", 1, LuaMobRegistry.size());

        // ---- buff (M6c/M7a-c/M8a-c): 3 buffs across combat/shield+tint/sleep-lock ----
        assertTrue("combat buff", LuaBuffRegistry.contains("regression_demo_combat"));
        assertTrue("shield buff", LuaBuffRegistry.contains("regression_demo_shield"));
        assertTrue("sleep_lock buff", LuaBuffRegistry.contains("regression_demo_sleep_lock"));
        assertEquals("3 buffs", 3, LuaBuffRegistry.size());

        // combat: all 7 combat-hook callbacks present as functions (typo guard)
        LuaTable combat = LuaBuffRegistry.getTable("regression_demo_combat");
        assertFunction(combat, "attackProc");
        assertFunction(combat, "defenseProc");
        assertFunction(combat, "attackSkill");
        assertFunction(combat, "defenseSkill");
        assertFunction(combat, "drRoll");
        assertFunction(combat, "speed");
        assertFunction(combat, "charAct");

        // shield: declarative shieldAmount is an INT (not a fn) + tintChar + absorb path
        LuaTable shield = LuaBuffRegistry.getTable("regression_demo_shield");
        assertEquals("shieldAmount declarative int == 20",
                20, shield.get("shieldAmount").optint(0));
        assertFunction(shield, "tintChar");
        assertFunction(shield, "defenseProc");

        // sleep_lock: sleepLock callback (M8a)
        assertFunction(LuaBuffRegistry.getTable("regression_demo_sleep_lock"), "sleepLock");

        // ---- spell (M6d/M7c-d/M11d): 3 spells — cell/self/enemy targeting, mana mode ----
        assertTrue("bolt spell", LuaSpellRegistry.contains("regression_demo_bolt"));
        assertTrue("curse spell", LuaSpellRegistry.contains("regression_demo_curse"));
        assertTrue("possess spell", LuaSpellRegistry.contains("regression_demo_possess"));
        assertEquals("3 spells", 3, LuaSpellRegistry.size());
        assertFunction(LuaSpellRegistry.getTable("regression_demo_bolt"), "onUseAt");
        assertFunction(LuaSpellRegistry.getTable("regression_demo_curse"), "onUse");
        assertFunction(LuaSpellRegistry.getTable("regression_demo_possess"), "onUseAt");
        // mana mode (M7d) on all three
        assertEquals("bolt mana mode", "mana",
                LuaSpellRegistry.getTable("regression_demo_bolt").get("useMode").tojstring());

        // ---- talent (M8d1-3): tier2 class + tier3 subclass + tier4 armor_ability ----
        assertTrue("MOD_SECOND_TALENT known", LuaTalentRegistry.isKnownModTalent("MOD_SECOND_TALENT"));
        assertTrue("MOD_TIER3_TALENT known", LuaTalentRegistry.isKnownModTalent("MOD_TIER3_TALENT"));
        assertTrue("MOD_TIER4_TALENT known", LuaTalentRegistry.isKnownModTalent("MOD_TIER4_TALENT"));

        // MOD_SECOND_TALENT injected into MAGE tier 2 (index 1)
        ArrayList<LinkedHashMap<Talent, Integer>> mageTalents = new ArrayList<>();
        Talent.initClassTalents(HeroClass.MAGE, mageTalents);
        assertTrue("MOD_SECOND_TALENT injected into MAGE tier 2",
                mageTalents.get(1).containsKey(Talent.MOD_SECOND_TALENT));

        // ---- trap + painter (M10b) ----
        assertTrue("trap registered", LuaTrapRegistry.contains("regression_demo_trap"));
        assertTrue("painter registered", LuaPainterRegistry.contains("ShopRoom"));

        // ---- level (M4a): register_level from entry.lua populates LuaLevelRegistry ----
        assertTrue("level registered via entry-script register_level",
                LuaLevelRegistry.contains("regression_demo_level"));

        // ---- C3: other mods disabled → their content absent (no cross-mod leak) ----
        assertFalse("test_mod disabled → test_sword must NOT register",
                LuaItemRegistry.contains("test_sword"));
        assertFalse("demo_m58 disabled → m58_test_weapon must NOT register",
                LuaItemRegistry.contains("m58_test_weapon"));
        assertFalse("demo_m58 disabled → combat_hook_demo must NOT register",
                LuaBuffRegistry.contains("combat_hook_demo"));
    }

    // ---------------- disabled: registries empty (C3 vanilla baseline) ----------------

    @Test
    public void loadDisabled_registriesEmpty() throws Exception {
        // Scan but do NOT enable regression_demo (default_enabled=false).
        ModRegistry.scanDir(ModTestSupport.realModsHandle());

        LuaEngine.init();

        assertFalse("pickaxe absent when disabled", LuaItemRegistry.contains("regression_demo_pickaxe"));
        assertFalse("ration absent when disabled", LuaItemRegistry.contains("regression_demo_ration"));
        assertFalse("rat absent when disabled", LuaMobRegistry.contains("regression_demo_rat"));
        assertFalse("combat buff absent when disabled", LuaBuffRegistry.contains("regression_demo_combat"));
        assertFalse("bolt spell absent when disabled", LuaSpellRegistry.contains("regression_demo_bolt"));
        assertFalse("trap absent when disabled", LuaTrapRegistry.contains("regression_demo_trap"));
        assertFalse("painter absent when disabled", LuaPainterRegistry.contains("ShopRoom"));
        assertFalse("level absent when disabled", LuaLevelRegistry.contains("regression_demo_level"));
        assertFalse("MOD_SECOND_TALENT absent when disabled",
                LuaTalentRegistry.isKnownModTalent("MOD_SECOND_TALENT"));
        assertEquals("no item registered when disabled", 0, LuaItemRegistry.size());
        assertEquals("no buff registered when disabled", 0, LuaBuffRegistry.size());
    }

    // ---------------- on_upgrade smoke: catches a typo'd callback name ----------------

    /**
     * Upgrading MOD_SECOND_TALENT must fire the shipped on_upgrade, which
     * giveItem(regression_demo_pickaxe) + affectBuff(regression_demo_combat). A typo'd
     * {@code on_upgarde} field would normalize to null and the dispatch would be a
     * no-op — no item, no buff — so this test catches what a field-presence check
     * cannot. Mirrors DemoM58LoadTest.onUpgrade_firesGiveItemAndAffectBuff.
     */
    @Test
    public void onUpgrade_firesGiveItemAndAffectBuff() throws Exception {
        enableRegressionDemo();
        LuaEngine.init();

        Hero hero = new Hero();
        hero.HT = 30;
        hero.HP = 30;
        hero.belongings = new Belongings(hero);
        Actor.add(hero);
        hero.heroClass = HeroClass.MAGE;
        Dungeon.hero = hero;
        try {
            Talent.initClassTalents(HeroClass.MAGE, hero.talents);
            assertTrue("backpack empty before upgrade",
                    hero.belongings.backpack.items.isEmpty());

            hero.upgradeTalent(Talent.MOD_SECOND_TALENT);

            // on_upgrade's RPD.giveItem must have put the pickaxe (LuaItem) in backpack.
            boolean foundWeapon = false;
            for (Item it : hero.belongings.backpack.items) {
                if (it instanceof LuaItem) { foundWeapon = true; break; }
            }
            assertTrue("on_upgrade giveItem must put regression_demo_pickaxe in backpack",
                    foundWeapon);

            // on_upgrade's RPD.affectBuff must have attached regression_demo_combat.
            boolean foundBuff = false;
            for (Buff b : hero.buffs(LuaBuff.class)) {
                if (((LuaBuff) b).sameLuaId("regression_demo_combat")) { foundBuff = true; break; }
            }
            assertTrue("on_upgrade affectBuff must attach regression_demo_combat",
                    foundBuff);
        } finally {
            Dungeon.hero = null;
            Actor.remove(hero);
        }
    }

    private static void assertFunction(LuaTable tbl, String field) {
        assertNotNull("table must be present (missing registry entry)", tbl);
        LuaValue v = tbl.get(field);
        assertTrue(field + " must be a function (typo? field=" + v.typename() + ")",
                v.isfunction());
    }
}
