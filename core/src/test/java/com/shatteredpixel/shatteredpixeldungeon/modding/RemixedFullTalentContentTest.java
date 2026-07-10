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
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.watabou.noosa.Game;
import com.watabou.utils.GameSettings;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M20c {@code remixed_full} talent content: the pack ships two remixed-style talents under
 * {@code scripts/talents/} (rf_initiative = ROGUE tier 1, rf_thick_skin = WARRIOR tier 2). They
 * activate the two pre-declared {@code MOD_} enum slots — Lua cannot mint new ids, so each script
 * binds {@code MOD_EXAMPLE_TALENT}/{@code MOD_SECOND_TALENT} into a class+tier and declares an
 * {@code on_upgrade} callback that delivers a remixed_full material. This test pins the four
 * acceptance contracts:
 *
 * <ol>
 *   <li><b>both talents register</b> when remixed_full is enabled.</li>
 *   <li><b>each injects into the right class+tier</b> (and does not leak into the wrong one).</li>
 *   <li><b>tier values are correct</b> (1 and 2) — read from the registry def, not just the slot.</li>
 *   <li><b>on_upgrade fires end-to-end</b> and delivers the declared remixed_full material into the
 *       backpack with quantity == points.</li>
 * </ol>
 *
 * <p>Mirrors {@link RemixedFullPackTest}'s enable/reset scaffold (disable test_mod /
 * regression_demo so the global MOD_ upsert is owned solely by remixed_full) and
 * {@link LuaTalentRegistryTest}'s {@code newHero} (Belongings is mandatory for giveItem).
 */
public class RemixedFullTalentContentTest {

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
        // Game.version must be non-null: on_upgrade giveItem → Item.collect → Catalog →
        // Document.<clinit> → DeviceCompat.isDebug() reads Game.version. Mirrors LuaTalentRegistryTest.
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
        ModTestSupport.resetLuaState();  // clears LuaTalentRegistry + LuaTalentOverride too
        BalanceConfig.resetToDefaults();
        Generator.setLuaItemProbability(0f, 0f);
    }

    private void enableRemixedFull() throws Exception {
        ModRegistry.scanDir(ModTestSupport.realModsHandle());
        ModRegistry.setEnabled("remixed_full", true);
        // Other mods also bind the global MOD_ slots (test_mod→MOD_EXAMPLE_TALENT,
        // regression_demo→MOD_SECOND_TALENT) as a last-call-wins upsert; disable them so
        // remixed_full owns both slots and the assertions are exact.
        ModRegistry.setEnabled("remished_lite", false);
        ModRegistry.setEnabled("test_mod", false);
        ModRegistry.setEnabled("regression_demo", false);
    }

    private ArrayList<LinkedHashMap<Talent, Integer>> freshTalentList() {
        return new ArrayList<>();
    }

    // ---------------- both talents register ----------------

    @Test
    public void enabled_registersBothModTalents() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        assertTrue("rf_initiative (MOD_EXAMPLE_TALENT) should be a known mod talent",
                LuaTalentRegistry.isKnownModTalent("MOD_EXAMPLE_TALENT"));
        assertTrue("rf_thick_skin (MOD_SECOND_TALENT) should be a known mod talent",
                LuaTalentRegistry.isKnownModTalent("MOD_SECOND_TALENT"));
        assertTrue("registry should hold both remixed_full talents", LuaTalentRegistry.size() >= 2);
    }

    // ---------------- inject into the right class + tier (no leak) ----------------

    @Test
    public void enabled_injectsIntoCorrectClassAndTier() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        // rf_initiative: ROGUE tier 1 (index 0). Each class gets a fresh list so a prior inject
        // cannot mask a leak in the other class.
        ArrayList<LinkedHashMap<Talent, Integer>> rogue = freshTalentList();
        Talent.initClassTalents(HeroClass.ROGUE, rogue);
        assertTrue("rf_initiative must inject into ROGUE tier 1",
                rogue.get(0).containsKey(Talent.MOD_EXAMPLE_TALENT));
        assertFalse("rf_thick_skin must not leak into ROGUE tier 1",
                rogue.get(0).containsKey(Talent.MOD_SECOND_TALENT));

        // rf_thick_skin: WARRIOR tier 2 (index 1).
        ArrayList<LinkedHashMap<Talent, Integer>> warrior = freshTalentList();
        Talent.initClassTalents(HeroClass.WARRIOR, warrior);
        assertTrue("rf_thick_skin must inject into WARRIOR tier 2",
                warrior.get(1).containsKey(Talent.MOD_SECOND_TALENT));
        assertFalse("rf_initiative must not leak into WARRIOR tier 2",
                warrior.get(1).containsKey(Talent.MOD_EXAMPLE_TALENT));
    }

    // ---------------- tier values declared correctly ----------------

    @Test
    public void enabled_talentsDeclareCorrectTier() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        int exampleTier = -1;
        int secondTier = -1;
        for (LuaTalentRegistry.ModTalentDef def : LuaTalentRegistry.defs()) {
            if (def.talent == Talent.MOD_EXAMPLE_TALENT) exampleTier = def.tier;
            if (def.talent == Talent.MOD_SECOND_TALENT) secondTier = def.tier;
        }
        assertEquals("rf_initiative (MOD_EXAMPLE_TALENT) must declare tier 1", 1, exampleTier);
        assertEquals("rf_thick_skin (MOD_SECOND_TALENT) must declare tier 2", 2, secondTier);
    }

    // ---------------- on_upgrade delivers a remixed_full material ----------------

    @Test
    public void enabled_onUpgrade_deliversRemixedMaterial() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        // rf_initiative (ROGUE tier 1) → remixed_full_rusty_coin
        Hero rogue = newHero();
        rogue.heroClass = HeroClass.ROGUE;
        Dungeon.hero = rogue;
        try {
            Talent.initClassTalents(HeroClass.ROGUE, rogue.talents);
            assertTrue("backpack empty before upgrade", rogue.belongings.backpack.items.isEmpty());
            rogue.upgradeTalent(Talent.MOD_EXAMPLE_TALENT);

            LuaMaterial coin = findLuaMaterial(rogue);
            assertNotNull("rf_initiative on_upgrade must deliver remixed_full_rusty_coin", coin);
            assertEquals("quantity must match the post-upgrade points (1)", 1, coin.quantity());
        } finally {
            Dungeon.hero = null;
            Actor.remove(rogue);
        }

        // rf_thick_skin (WARRIOR tier 2) → remixed_full_dark_gold
        Hero warrior = newHero();
        warrior.heroClass = HeroClass.WARRIOR;
        Dungeon.hero = warrior;
        try {
            Talent.initClassTalents(HeroClass.WARRIOR, warrior.talents);
            warrior.upgradeTalent(Talent.MOD_SECOND_TALENT);

            LuaMaterial gold = findLuaMaterial(warrior);
            assertNotNull("rf_thick_skin on_upgrade must deliver remixed_full_dark_gold", gold);
            assertEquals("quantity must match the post-upgrade points (1)", 1, gold.quantity());
        } finally {
            Dungeon.hero = null;
            Actor.remove(warrior);
        }
    }

    /** Build a live Hero registered with Actor (so hero.id() resolves via Actor.findById, which
     *  RPD.giveItem uses) with a Belongings (mandatory for Item.collect into the backpack). */
    private static Hero newHero() {
        Hero hero = new Hero();
        hero.HT = 30;
        hero.HP = 30;
        hero.belongings = new Belongings(hero);
        Actor.add(hero);
        return hero;
    }

    private static LuaMaterial findLuaMaterial(Hero hero) {
        for (Item it : hero.belongings.backpack.items) {
            if (it instanceof LuaMaterial) return (LuaMaterial) it;
        }
        return null;
    }
}
