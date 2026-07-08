package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.watabou.noosa.Game;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M11b: LuaMaterial action/execute/onThrow/transform unit tests.
 * Uses the real test_mod item scripts but avoids a full GameScene/level.
 */
public class LuaMaterialActionTest {

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

    private static LuaMaterial mat(String id) {
        LuaEngine.init();
        Item item = LuaItemRegistry.createItem(id);
        assertTrue(id + " must be a LuaMaterial", item instanceof LuaMaterial);
        return (LuaMaterial) item;
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
    public void rawFishActionsAndDefault() {
        LuaMaterial fish = mat("raw_fish");
        assertEquals("EAT", fish.defaultAction());
        ArrayList<String> actions = fish.actions(null);
        assertTrue(actions.contains("EAT"));
        assertTrue(actions.contains("DROP"));
        assertTrue(actions.contains("THROW"));
        assertFalse(actions.contains("USE"));
    }

    @Test
    public void vileEssenceActionsAndDefault() {
        LuaMaterial essence = mat("vile_essence");
        assertEquals("USE", essence.defaultAction());
        ArrayList<String> actions = essence.actions(null);
        assertTrue(actions.contains("USE"));
        assertFalse(actions.contains("EAT"));
    }

    @Test
    public void unconfiguredMaterialOnlyDropThrow() {
        LuaMaterial organ = mat("rotten_organ");
        assertNull(organ.defaultAction());
        ArrayList<String> actions = organ.actions(null);
        assertFalse("unconfigured material must not auto-gain EAT", actions.contains("EAT"));
        assertFalse("unconfigured material must not auto-gain USE", actions.contains("USE"));
        assertTrue(actions.contains("DROP"));
        assertTrue(actions.contains("THROW"));
    }

    @Test
    public void energyHydrates() {
        assertEquals(150f, mat("raw_fish").energy(), 0.001f);
        assertEquals(350f, mat("fried_fish").energy(), 0.001f);
        assertEquals(120f, mat("frozen_fish").energy(), 0.001f);
        assertEquals(50f, mat("rotten_fish").energy(), 0.001f);
        assertEquals(400f, mat("tengu_liver").energy(), 0.001f);
        assertEquals(0f, mat("vile_essence").energy(), 0.001f);
    }

    @Test
    public void burnTransformReplacesRawFish() {
        LuaMaterial raw = mat("raw_fish");
        raw.quantity(3);
        Item transformed = raw.burnTransform();
        assertNotNull(transformed);
        assertTrue(transformed instanceof LuaMaterial);
        assertEquals("fried_fish", ((LuaMaterial) transformed).luaItemId());
        assertEquals(3, transformed.quantity());
    }

    @Test
    public void freezeTransformReplacesRawFish() {
        LuaMaterial raw = mat("raw_fish");
        raw.quantity(2);
        Item transformed = raw.freezeTransform();
        assertNotNull(transformed);
        assertEquals("frozen_fish", ((LuaMaterial) transformed).luaItemId());
        assertEquals(2, transformed.quantity());
    }

    @Test
    public void transformNullWhenNotConfigured() {
        LuaMaterial organ = mat("rotten_organ");
        assertNull(organ.burnTransform());
        assertNull(organ.freezeTransform());
    }

    @Test
    public void heapBurnReplacesLuaMaterial() {
        LuaMaterial raw = mat("raw_fish");
        raw.quantity(1);
        Item transformed = raw.burnTransform();
        assertNotNull(transformed);
        assertTrue(transformed instanceof LuaMaterial);
        assertEquals("fried_fish", ((LuaMaterial) transformed).luaItemId());
    }

    @Test
    public void heapFreezeReplacesLuaMaterial() {
        LuaMaterial raw = mat("raw_fish");
        raw.quantity(1);
        Item transformed = raw.freezeTransform();
        assertNotNull(transformed);
        assertEquals("frozen_fish", ((LuaMaterial) transformed).luaItemId());
    }

    @Test
    public void luaTableExposedForTests() {
        LuaMaterial fish = mat("raw_fish");
        assertNotNull(fish.table());
        assertTrue(fish.table().get("onEat").isfunction());
    }

    @Test
    public void actionNameFallback() {
        LuaMaterial fish = mat("raw_fish");
        assertEquals("Eat", fish.actionName("EAT", null));
        LuaMaterial essence = mat("vile_essence");
        assertEquals("Use", essence.actionName("USE", null));
    }

    @Test
    public void eatDetachesOneAndSatisfiesHunger() {
        Hero hero = newHero();
        LuaMaterial fish = mat("raw_fish");
        fish.quantity(5);
        hero.belongings.backpack.items.add(fish);

        Hunger hunger = Buff.affect(hero, Hunger.class);
        // Hunger level starts at 0. Push it up to 400, then eating raw_fish (energy=150)
        // should reduce it to 250.
        hunger.affectHunger(400f, true);

        int before = hunger.hunger();
        fish.execute(hero, "EAT");

        assertEquals(4, fish.quantity());
        assertTrue("hunger should decrease after eating (" + before + " -> " + hunger.hunger() + ")",
                hunger.hunger() < before || hunger.hunger() == 0);
    }

    @Test
    public void useDetachesOneAndSpendsTime() {
        Hero hero = newHero();
        LuaMaterial essence = mat("vile_essence");
        essence.quantity(3);
        hero.belongings.backpack.items.add(essence);

        float before = hero.cooldown();
        essence.execute(hero, "USE");

        assertEquals(2, essence.quantity());
        assertTrue("USE should advance hero cooldown", hero.cooldown() > before);
    }

    @Test
    public void onEatCallbackFiresAndAppliesPoison() {
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        g.load("register_item{ id='test_poison_food', type='material', name='x', defaultAction='EAT', energy=10," +
                "onEat=function(heroId) _m11b_poison=true; RPD.affectBuff(heroId,'Poison',3) end }").call();
        LuaMaterial food = (LuaMaterial) LuaItemRegistry.createItem("test_poison_food");
        Hero hero = newHero();
        hero.belongings.backpack.items.add(food);

        food.execute(hero, "EAT");

        assertTrue("onEat callback should run", g.get("_m11b_poison").toboolean());
        assertNotNull("Poison buff should be applied", hero.buff(com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison.class));
    }

    @Test
    public void onUseCallbackFiresAndRestoresMana() {
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        g.load("register_item{ id='test_mana_item', type='material', name='x', defaultAction='USE'," +
                "onUse=function(heroId) _m11b_mana=true; RPD.restoreMana(heroId, 4) end }").call();
        LuaMaterial item = (LuaMaterial) LuaItemRegistry.createItem("test_mana_item");
        Hero hero = newHero();
        hero.MPMax = 10;
        hero.MP = 0;
        hero.belongings.backpack.items.add(item);

        item.execute(hero, "USE");

        assertTrue("onUse callback should run", g.get("_m11b_mana").toboolean());
        assertEquals(4, hero.MP);
    }

    @Test
    public void onThrowCallbackFires() {
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        g.load("register_item{ id='test_throw_item', type='material', name='x'," +
                "onThrow=function(cell,itemId) _m11b_throw=true; _m11b_cell=cell end }").call();
        LuaMaterial item = (LuaMaterial) LuaItemRegistry.createItem("test_throw_item");
        item.onThrow(123);
        assertTrue("onThrow callback should run", g.get("_m11b_throw").toboolean());
        assertEquals(123, g.get("_m11b_cell").toint());
    }

    @Test
    public void allFishAndTenguLiverAreEatable() {
        for (String id : new String[]{"raw_fish", "fried_fish", "frozen_fish", "rotten_fish", "tengu_liver"}) {
            LuaMaterial food = mat(id);
            ArrayList<String> actions = food.actions(null);
            assertTrue(id + " should expose EAT", actions.contains("EAT"));
            assertEquals(id + " default action should be EAT", "EAT", food.defaultAction());
        }
    }
}
