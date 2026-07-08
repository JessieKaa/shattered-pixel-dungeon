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
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.watabou.noosa.Game;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M11c: LuaItem action/execute/defaultAction/state persistence tests.
 */
public class LuaItemActionTest {

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

    private static LuaItem item(String id) {
        LuaEngine.init();
        Item item = LuaItemRegistry.createItem(id);
        assertTrue(id + " must be a LuaItem", item instanceof LuaItem);
        return (LuaItem) item;
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
    public void pickaxeActionsContainMineAndDefaults() {
        LuaItem pick = item("remixed_pickaxe");
        ArrayList<String> actions = pick.actions(newHero());
        assertTrue(actions.contains("MINE"));
        assertTrue(actions.contains("EQUIP"));
        assertTrue(actions.contains("DROP"));
        assertTrue(actions.contains("THROW"));
        assertEquals("EQUIP", pick.defaultAction());
    }

    @Test
    public void actionNameUsesLuaTable() {
        LuaItem pick = item("remixed_pickaxe");
        assertEquals("挖矿", pick.actionName("MINE", newHero()));
        assertEquals("装备", pick.actionName("EQUIP", newHero()));
    }

    @Test
    public void executeDispatchesToLua() {
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        g.load("register_item{ id='test_action_weapon', name='x', tier=1," +
                "actions={'EQUIP','MINE'}," +
                "execute=function(heroId,action,state) _m11c_action=action; _m11c_hero=heroId end }").call();
        LuaItem item = (LuaItem) LuaItemRegistry.createItem("test_action_weapon");
        Hero hero = newHero();
        hero.belongings.backpack.items.add(item);

        item.execute(hero, "MINE");

        assertEquals("MINE", g.get("_m11c_action").tojstring());
        assertEquals(hero.id(), g.get("_m11c_hero").toint());
    }

    @Test
    public void executeFallsBackToOnUse() {
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        g.load("register_item{ id='test_onuse_weapon', name='x', tier=1," +
                "actions={'EQUIP','MINE'}," +
                "onUse=function(heroId,action,state) _m11c_onuse=action end }").call();
        LuaItem item = (LuaItem) LuaItemRegistry.createItem("test_onuse_weapon");
        Hero hero = newHero();
        hero.belongings.backpack.items.add(item);

        item.execute(hero, "MINE");

        assertEquals("MINE", g.get("_m11c_onuse").tojstring());
    }

    @Test
    public void statePassedToAttackProc() {
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        g.load("register_item{ id='test_state_weapon', name='x', tier=1," +
                "actions={'EQUIP','HIT'}," +
                "execute=function(heroId,action,state) if action=='HIT' then state.hits=(state.hits or 0)+1; _G.test_state_weapon_hits=state.hits end end," +
                "attackProc=function(attacker,defender,base,state) return base end }").call();
        LuaItem item = (LuaItem) LuaItemRegistry.createItem("test_state_weapon");
        Hero hero = newHero();
        Dungeon.hero = hero;
        hero.belongings.backpack.items.add(item);
        try {
            item.execute(hero, "HIT");
            item.execute(hero, "HIT");
            LuaValue hits = g.load("return _G.test_state_weapon_hits").call();
            assertEquals(2, hits.toint());
        } finally {
            Dungeon.hero = null;
        }
    }

    @Test
    public void glowingCallbackReadsState() {
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        g.load("register_item{ id='test_glow_weapon', name='x', tier=1," +
                "actions={'EQUIP','GLOW_ON'}," +
                "execute=function(heroId,action,state) if action=='GLOW_ON' then state.glow=true end end," +
                "glowing=function(state) if state.glow then return {color=0xFF0000, period=0.5} end; return nil end }").call();
        LuaItem item = (LuaItem) LuaItemRegistry.createItem("test_glow_weapon");
        Hero hero = newHero();
        hero.belongings.backpack.items.add(item);
        assertNull(item.glowing());
        item.execute(hero, "GLOW_ON");
        assertNotNull(item.glowing());
        assertEquals(0xFF0000, item.glowing().color);
    }

    @Test
    public void defaultActionCanBeLuaFunction() {
        LuaEngine.init();
        Globals g = LuaEngine.instance().globals();
        g.load("register_item{ id='test_default_weapon', name='x', tier=1," +
                "actions={'EQUIP','MINE','SET_READY'}," +
                "defaultAction=function(state) return state.ready and 'MINE' or 'EQUIP' end," +
                "execute=function(heroId,action,state) if action=='SET_READY' then state.ready=true end end }").call();
        LuaItem item = (LuaItem) LuaItemRegistry.createItem("test_default_weapon");
        Hero hero = newHero();
        hero.belongings.backpack.items.add(item);
        assertEquals("EQUIP", item.defaultAction());
        item.execute(hero, "SET_READY");
        assertEquals("MINE", item.defaultAction());
    }
}
