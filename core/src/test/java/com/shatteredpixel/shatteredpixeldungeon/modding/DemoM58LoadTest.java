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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Headless load test for the {@code demo_m58} mod (M5-M8 API demo). Enables only
 * demo_m58 (test_mod stays disabled for C3 isolation), runs {@link LuaEngine#init()},
 * and asserts every M5-M8 capability the mod registers actually landed in the
 * right registry — plus that the Lua callback fields are present and well-typed.
 *
 * <p>Why the field-type assertions: {@code register_buff}/{@code register_item}
 * accept arbitrary tables and never validate callback field names, so a typo like
 * {@code attaackProc} would silently register a buff whose combat hook never
 * fires. Asserting {@code getTable(id).get("attackProc").isfunction()} catches
 * that class of bug at load time. (The Java dispatch side is already covered by
 * {@code RpdApiBuffTest} / {@code LuaItemCallbackTest}; this test guards the Lua
 * spelling, which only this mod's own scripts exercise.)
 *
 * <p>Setup mirrors {@link LuaModEntryTest}: a libgdx {@link HeadlessApplication}
 * with {@code Game.versionCode=896} (so the version gate admits demo_m58) and a
 * fresh HashMap-backed Preferences per test for {@code mod_enabled_*} isolation.
 */
public class DemoM58LoadTest {

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
        // Game.version must be non-null: the on_upgrade giveItem path hits
        // Item.collect -> Catalog -> Document.<clinit> -> DeviceCompat.isDebug(),
        // which does Game.version.contains(...). Mirrors LuaTalentRegistryTest.
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
        // Fresh prefs + cleared registries every test so the disabled case
        // observes genuinely empty registries, not whatever a prior test left.
        GameSettings.set(new ModTestSupport.FakePreferences());
        ModRegistry.resetForTests();
        ModTestSupport.resetLuaState();
    }

    private void enableDemoM58() throws Exception {
        ModRegistry.scanDir(ModTestSupport.realModsHandle());
        ModRegistry.setEnabled("demo_m58", true);
    }

    // ---------------- enabled: everything registers ----------------

    @Test
    public void loadEnabled_registersAllScripts() throws Exception {
        enableDemoM58();

        LuaEngine.init();

        // ---- 5 buffs register ----
        assertTrue("combat_hook_demo", LuaBuffRegistry.contains("combat_hook_demo"));
        assertTrue("sleep_lock_demo", LuaBuffRegistry.contains("sleep_lock_demo"));
        assertTrue("shield_demo", LuaBuffRegistry.contains("shield_demo"));
        assertTrue("tint_demo", LuaBuffRegistry.contains("tint_demo"));
        assertTrue("mana_demo", LuaBuffRegistry.contains("mana_demo"));

        // ---- combat_hook_demo: all 6 combat hooks + charAct present as functions ----
        LuaTable combat = LuaBuffRegistry.getTable("combat_hook_demo");
        assertFunction(combat, "attackProc");
        assertFunction(combat, "defenseProc");
        assertFunction(combat, "attackSkill");
        assertFunction(combat, "defenseSkill");
        assertFunction(combat, "drRoll");
        assertFunction(combat, "speed");
        assertFunction(combat, "charAct");

        // ---- sleep_lock_demo: sleepLock callback ----
        assertFunction(LuaBuffRegistry.getTable("sleep_lock_demo"), "sleepLock");

        // ---- shield_demo: declarative shieldAmount int (NOT a function) + tintChar fn ----
        LuaTable shield = LuaBuffRegistry.getTable("shield_demo");
        assertEquals("shieldAmount is a declarative int == 20",
                20, shield.get("shieldAmount").optint(0));
        assertFunction(shield, "defenseProc");
        assertFunction(shield, "tintChar");

        // ---- tint_demo + mana_demo callbacks ----
        assertFunction(LuaBuffRegistry.getTable("tint_demo"), "tintChar");
        assertFunction(LuaBuffRegistry.getTable("mana_demo"), "attachTo");

        // ---- item registers with all 3 LuaItem callbacks ----
        assertTrue("m58_test_weapon registered", LuaItemRegistry.contains("m58_test_weapon"));
        LuaTable weapon = LuaItemRegistry.getTable("m58_test_weapon");
        assertFunction(weapon, "onEquip");
        assertFunction(weapon, "attackProc");
        assertFunction(weapon, "onDeactivate");
        assertEquals("weapon tier=2 (gameplay-relevant, catches drift)",
                2, weapon.get("tier").optint(0));

        // ---- new talent: MOD_SECOND_TALENT known + injected into MAGE tier 2 ----
        assertTrue("MOD_SECOND_TALENT known",
                LuaTalentRegistry.isKnownModTalent("MOD_SECOND_TALENT"));
        ArrayList<LinkedHashMap<Talent, Integer>> mageTalents = new ArrayList<>();
        Talent.initClassTalents(HeroClass.MAGE, mageTalents);
        assertTrue("MOD_SECOND_TALENT injected into MAGE tier 2",
                mageTalents.get(1).containsKey(Talent.MOD_SECOND_TALENT));

        // ---- override: HEARTY_MEAL lowered to maxPoints=1 ----
        assertNotNull("HEARTY_MEAL override present",
                LuaTalentOverride.get(Talent.HEARTY_MEAL));
        assertEquals("HEARTY_MEAL maxPoints lowered to 1",
                1, Talent.HEARTY_MEAL.maxPoints());

        // ---- C3: test_mod disabled → its content absent (no cross-mod leak) ----
        assertFalse("test_mod disabled → test_sword must NOT register",
                LuaItemRegistry.contains("test_sword"));
        assertFalse("test_mod disabled → MOD_EXAMPLE_TALENT must NOT be known",
                LuaTalentRegistry.isKnownModTalent("MOD_EXAMPLE_TALENT"));

        // ---- tier3 placeholder rejected by tier guard but did not crash ----
        // MOD_EXAMPLE_TALENT is both test_mod's slot (disabled) and the placeholder
        // id; since neither registers it, "not known" also covers the placeholder.
        assertFalse("tier3 placeholder must be rejected (tier guard)",
                LuaTalentRegistry.isKnownModTalent("MOD_EXAMPLE_TALENT"));
    }

    // ---------------- disabled: registries empty (C3) ----------------

    @Test
    public void loadDisabled_registriesEmpty() throws Exception {
        // Scan but do NOT enable demo_m58 (default_enabled=false).
        ModRegistry.scanDir(ModTestSupport.realModsHandle());

        LuaEngine.init();

        assertFalse("combat_hook_demo absent when disabled",
                LuaBuffRegistry.contains("combat_hook_demo"));
        assertFalse("m58_test_weapon absent when disabled",
                LuaItemRegistry.contains("m58_test_weapon"));
        assertFalse("MOD_SECOND_TALENT absent when disabled",
                LuaTalentRegistry.isKnownModTalent("MOD_SECOND_TALENT"));
        assertNull("HEARTY_MEAL has no override when disabled",
                LuaTalentOverride.get(Talent.HEARTY_MEAL));
        assertEquals("no buff registered when disabled",
                0, LuaBuffRegistry.size());
    }

    // ---------------- on_upgrade: behavioral (catches a typo'd callback name) ----------------

    /**
     * Upgrading MOD_SECOND_TALENT must fire the shipped on_upgrade, which
     * giveItem(m58_test_weapon) + affectBuff(combat_hook_demo). A typo'd
     * {@code on_upgarde} field would normalize to null in register_talent and the
     * dispatch would be a no-op — no item, no buff — so this test catches what a
     * field-presence check cannot (LuaTalentRegistry exposes no on_upgrade getter).
     * Mirrors LuaTalentRegistryTest.onUpgrade_giveItem / onUpgrade_affectBuff.
     */
    @Test
    public void onUpgrade_firesGiveItemAndAffectBuff() throws Exception {
        enableDemoM58();
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

            // on_upgrade's RPD.giveItem must have put m58_test_weapon (LuaItem) in
            // the backpack.
            boolean foundWeapon = false;
            for (Item it : hero.belongings.backpack.items) {
                if (it instanceof LuaItem) { foundWeapon = true; break; }
            }
            assertTrue("on_upgrade giveItem must put m58_test_weapon in backpack",
                    foundWeapon);

            // on_upgrade's RPD.affectBuff must have attached combat_hook_demo.
            boolean foundBuff = false;
            for (Buff b : hero.buffs(LuaBuff.class)) {
                if (((LuaBuff) b).sameLuaId("combat_hook_demo")) { foundBuff = true; break; }
            }
            assertTrue("on_upgrade affectBuff must attach combat_hook_demo",
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
