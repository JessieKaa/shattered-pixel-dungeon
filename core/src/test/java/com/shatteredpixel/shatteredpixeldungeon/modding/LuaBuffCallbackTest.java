package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M10c coverage for the 8 new Remished callbacks (regenerationBonus, hasteLevel,
 * stealthBonus, charSpriteStatus, setGlowing, damage, drBonus, speedMultiplier)
 * and the 4 {@code *Bonus} bridges folded into the M7b methods. Mirrors the
 * RpdApiBuffTest harness: headless libGDX + a fresh {@code test_mod} Lua slate
 * per test. Asserts the M7b callbacks stay backward-compatible (a buff with no
 * new field passes values through unchanged).
 */
public class LuaBuffCallbackTest {

    private static HeadlessApplication application;
    private static int savedVersionCode;
    private static Hero savedHero;

    @BeforeClass
    public static void initHeadless() {
        HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
        config.updatesPerSecond = 1;
        application = new HeadlessApplication(new ApplicationAdapter() {}, config);
        savedVersionCode = com.watabou.noosa.Game.versionCode;
        com.watabou.noosa.Game.versionCode = 896;
        savedHero = Dungeon.hero;
    }

    @Before
    public void resetState() throws Exception {
        ModTestSupport.enableTestMod();
        ModTestSupport.resetLuaState();
        ShieldTracker.clearAll();
        Dungeon.level = null;
        Dungeon.hero = null;
    }

    @AfterClass
    public static void shutdown() {
        Dungeon.hero = savedHero;
        com.watabou.noosa.Game.versionCode = savedVersionCode;
        try { if (application != null) application.exit(); } catch (Throwable ignored) { }
    }

    private Globals globals() {
        Globals g = LuaSandbox.exposedGlobals();
        LuaEngine.installGlobalsForTests(g);
        return g;
    }

    private static Hero freshHero() {
        Hero h = new Hero();
        h.HT = 50; h.HP = 50;
        h.pos = 0;
        Actor.add(h);
        return h;
    }

    private static LuaBuff findLuaBuff(Char c, String id) {
        for (Buff b : c.buffs(LuaBuff.class)) {
            if (((LuaBuff) b).sameLuaId(id)) return (LuaBuff) b;
        }
        return null;
    }

    /** Register a buff via a one-shot Lua source on a throwaway globals, then return its table. */
    private static LuaTable tableFromLua(String lua, String id) {
        Globals g = LuaSandbox.exposedGlobals();
        LuaEngine.installGlobalsForTests(g);
        g.load(lua).call();
        return LuaBuffRegistry.getTable(id);
    }

    // ---- regenerationBonus ----

    @Test
    public void dispatchRegenBonusSumsAcrossBuffs() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("rb1", tableFromLua(
                "register_buff{ id='rb1', name='r', regenerationBonus=function(self) return 3 end }", "rb1"));
        LuaBuffRegistry.register("rb2", tableFromLua(
                "register_buff{ id='rb2', name='r', regenerationBonus=function(self) return 2 end }", "rb2"));
        Hero h = freshHero();
        try {
            LuaBuffRegistry.create("rb1").attachTo(h);
            assertEquals("single regen bonus", 3, LuaBuff.dispatchRegenBonus(h));
            LuaBuffRegistry.create("rb2").attachTo(h);
            assertEquals("regen bonuses compose additively", 5, LuaBuff.dispatchRegenBonus(h));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void dispatchRegenBonusNullSafeAndNoBuff() {
        assertEquals(0, LuaBuff.dispatchRegenBonus(null));
        Hero h = freshHero();
        try {
            assertEquals("no LuaBuff attached → 0 bonus", 0, LuaBuff.dispatchRegenBonus(h));
        } finally {
            Actor.remove(h);
        }
    }

    // ---- hasteLevel ----

    @Test
    public void dispatchHasteMultiplierAppliesExponentialClamp() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("haste4", tableFromLua(
                "register_buff{ id='haste4', name='h', hasteLevel=function(self) return 4 end }", "haste4"));
        Hero h = freshHero();
        try {
            LuaBuffRegistry.create("haste4").attachTo(h);
            // sum=4 → 1.1^4 = 1.4641, within [0.25, 4.0]
            assertEquals(1.4641f, LuaBuff.dispatchHasteMultiplier(h), 0.001f);
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void dispatchHasteMultiplierClampsHighAndLow() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("mega", tableFromLua(
                "register_buff{ id='mega', name='m', hasteLevel=function(self) return 100 end }", "mega"));
        LuaBuffRegistry.register("slow", tableFromLua(
                "register_buff{ id='slow', name='s', hasteLevel=function(self) return -100 end }", "slow"));
        Hero h = freshHero();
        try {
            LuaBuffRegistry.create("mega").attachTo(h);
            assertEquals("huge positive clamps to 4.0", 4.0f, LuaBuff.dispatchHasteMultiplier(h), 0.0f);
        } finally {
            Actor.remove(h);
        }
        Hero h2 = freshHero();
        try {
            LuaBuffRegistry.create("slow").attachTo(h2);
            assertEquals("huge negative clamps to 0.25", 0.25f, LuaBuff.dispatchHasteMultiplier(h2), 0.0f);
        } finally {
            Actor.remove(h2);
        }
    }

    // Char.speed() integration is not headless-testable: Hero.speed() touches
    // ((HeroSprite)sprite).sprint(), and the hero has no sprite in headless mode.
    // dispatchHasteMultiplier (above) covers the formula + clamp directly; the
    // Char.speed() hook is a single `speed *= dispatchHasteMultiplier(this)` line.

    // ---- stealthBonus ----

    @Test
    public void dispatchStealthBonusSumsAndFeedsCharStealth() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("st", tableFromLua(
                "register_buff{ id='st', name='s', stealthBonus=function(self) return 5 end }", "st"));
        Hero h = freshHero();
        try {
            float base = h.stealth();
            LuaBuffRegistry.create("st").attachTo(h);
            assertEquals("stealthBonus summed into dispatch", 5f,
                    LuaBuff.dispatchStealthBonus(h), 0.001f);
            assertEquals("Char.stealth() includes the bonus", base + 5f, h.stealth(), 0.001f);
        } finally {
            Actor.remove(h);
        }
    }

    // ---- charSpriteStatus ----

    @Test
    public void computeSpriteStateReturnsEnumFromString() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("invis", tableFromLua(
                "register_buff{ id='invis', name='i', charSpriteStatus=function(self,s) return 'INVISIBLE' end }",
                "invis"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("invis");
            assertTrue(lb.attachTo(h));
            assertEquals(CharSprite.State.INVISIBLE, lb.computeSpriteState());
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void computeSpriteStateNilForAbsentAndUnknown() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("nostate", tableFromLua(
                "register_buff{ id='nostate', name='n' }", "nostate"));
        LuaBuffRegistry.register("badstate", tableFromLua(
                "register_buff{ id='badstate', name='b', charSpriteStatus=function(self,s) return 'NOPE' end }",
                "badstate"));
        Hero h = freshHero();
        try {
            assertNull(LuaBuffRegistry.create("nostate").computeSpriteState());
            assertNull("unknown state name → null (no throw)",
                    LuaBuffRegistry.create("badstate").computeSpriteState());
        } finally {
            Actor.remove(h);
        }
    }

    // ---- setGlowing (reuses M8c aura via computeTint) ----

    @Test
    public void setGlowingNumberReturnsAuraSpec() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("glow_num", tableFromLua(
                "register_buff{ id='glow_num', name='g', setGlowing=function(self,s) return 255 end }",
                "glow_num"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("glow_num");
            assertTrue(lb.attachTo(h));
            LuaBuff.TintSpec spec = lb.computeTint();
            assertNotNull(spec);
            assertTrue("setGlowing number → aura", spec.aura);
            assertEquals(255, spec.color);
            assertEquals(6, spec.rays);
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void setGlowingTableReturnsAuraWithRays() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("glow_tbl", tableFromLua(
                "register_buff{ id='glow_tbl', name='g', setGlowing=function(self,s) return {color=10, rays=3} end }",
                "glow_tbl"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("glow_tbl");
            assertTrue(lb.attachTo(h));
            LuaBuff.TintSpec spec = lb.computeTint();
            assertNotNull(spec);
            assertTrue(spec.aura);
            assertEquals(10, spec.color);
            assertEquals(3, spec.rays);
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void setGlowingTakesPrecedenceOverTintChar() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("both", tableFromLua(
                "register_buff{ id='both', name='b', " +
                "setGlowing=function(self,s) return 111 end, " +
                "tintChar=function(self,s) return 222 end }", "both"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("both");
            assertTrue(lb.attachTo(h));
            assertEquals("setGlowing wins over tintChar", 111, lb.computeTint().color);
        } finally {
            Actor.remove(h);
        }
    }

    // ---- damage (canon return-consuming) ----

    @Test
    public void dispatchDamageComposesReturnValue() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("halve", tableFromLua(
                "register_buff{ id='halve', name='h', damage=function(self,src,dmg) return math.floor(dmg/2) end }",
                "halve"));
        Hero h = freshHero();
        Hero src = freshHero();
        try {
            LuaBuffRegistry.create("halve").attachTo(h);
            assertEquals("damage callback halves incoming dmg", 5,
                    LuaBuff.dispatchDamage(h, 10, src));
        } finally {
            Actor.remove(h);
            Actor.remove(src);
        }
    }

    @Test
    public void damageCallbackCanSelfDetachMidDispatch() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("selfdrop", tableFromLua(
                "register_buff{ id='selfdrop', name='d', " +
                "damage=function(self,src,dmg) RPD.detachBuff(self,'selfdrop') return dmg end }",
                "selfdrop"));
        Hero h = freshHero();
        Hero src = freshHero();
        try {
            LuaBuffRegistry.create("selfdrop").attachTo(h);
            assertNotNull(findLuaBuff(h, "selfdrop"));
            assertEquals("passthrough dmg unchanged", 10, LuaBuff.dispatchDamage(h, 10, src));
            assertNull("self-detach mid-iteration (no CME)", findLuaBuff(h, "selfdrop"));
        } finally {
            Actor.remove(h);
            Actor.remove(src);
        }
    }

    @Test
    public void charDamageDispatchesToLuaBuff() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("soak2", tableFromLua(
                "register_buff{ id='soak2', name='s', damage=function(self,src,dmg) return dmg-2 end }",
                "soak2"));
        Hero h = freshHero();
        Hero src = freshHero();
        try {
            h.damageInterrupt = false; // skip Hero.damage interrupt path (null GameScene)
            LuaBuffRegistry.create("soak2").attachTo(h);
            h.damage(10, src);
            // 10 dmg, damage callback subtracts 2 pre-shield → HP drops by 8 (50→42)
            assertEquals("Char.damage wired to dispatchDamage (pre-shield)", 42, h.HP);
        } finally {
            Actor.remove(h);
            Actor.remove(src);
        }
    }

    @Test
    public void charDamageClampsNegativeLuaDamage() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("neg", tableFromLua(
                "register_buff{ id='neg', name='n', damage=function(self,src,dmg) return -100 end }",
                "neg"));
        Hero h = freshHero();
        Hero src = freshHero();
        try {
            h.damageInterrupt = false;
            LuaBuffRegistry.create("neg").attachTo(h);
            h.damage(10, src);
            // negative Lua return clamped to 0 → neither loses nor heals HP
            assertEquals("negative Lua damage clamped to 0 (no heal)", 50, h.HP);
        } finally {
            Actor.remove(h);
            Actor.remove(src);
        }
    }

    // ---- *Bonus bridges folded into M7b methods ----

    @Test
    public void attackSkillBonusBridgesAdditively() throws Exception {
        LuaBuffRegistry.clear();
        // Only attackSkillBonus (no attackSkill) → passthrough base + bonus
        LuaBuffRegistry.register("ab_only", tableFromLua(
                "register_buff{ id='ab_only', name='a', attackSkillBonus=function(self) return 7 end }",
                "ab_only"));
        // Both → M7b transform + bonus
        LuaBuffRegistry.register("ab_both", tableFromLua(
                "register_buff{ id='ab_both', name='a', attackSkill=function(self,atk) return atk+3 end, " +
                "attackSkillBonus=function(self) return 7 end }", "ab_both"));
        Hero h = freshHero();
        try {
            LuaBuff lb1 = LuaBuffRegistry.create("ab_only");
            lb1.attachTo(h);
            assertEquals("bonus-only → base + 7", 17, lb1.attackSkill(h.id(), 10));
            LuaBuff lb2 = LuaBuffRegistry.create("ab_both");
            lb2.attachTo(h);
            assertEquals("M7b + bonus stack → (10+3)+7", 20, lb2.attackSkill(h.id(), 10));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void defenceSkillBonusBridgesAdditively() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("db", tableFromLua(
                "register_buff{ id='db', name='d', defenceSkillBonus=function(self) return 4 end }", "db"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("db");
            lb.attachTo(h);
            assertEquals("defenceSkillBonus → base + 4", 14, lb.defenseSkill(h.id(), 10));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void drBonusBridgesAdditively() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("drb", tableFromLua(
                "register_buff{ id='drb', name='d', drBonus=function(self) return 5 end }", "drb"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("drb");
            lb.attachTo(h);
            assertEquals("drBonus → base + 5", 15, lb.drRoll(h.id(), 10));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void speedMultiplierBridgesMultiplicatively() throws Exception {
        LuaBuffRegistry.clear();
        // multiplier-only → passthrough * 0.9
        LuaBuffRegistry.register("sm_only", tableFromLua(
                "register_buff{ id='sm_only', name='s', speedMultiplier=function(self) return 0.9 end }",
                "sm_only"));
        // both → M7b speed * multiplier
        LuaBuffRegistry.register("sm_both", tableFromLua(
                "register_buff{ id='sm_both', name='s', speed=function(self,spd) return spd*2 end, " +
                "speedMultiplier=function(self) return 0.5 end }", "sm_both"));
        Hero h = freshHero();
        try {
            LuaBuff lb1 = LuaBuffRegistry.create("sm_only");
            lb1.attachTo(h);
            assertEquals("multiplier-only → 10*0.9", 9.0f, lb1.speed(h.id(), 10.0f), 0.001f);
            LuaBuff lb2 = LuaBuffRegistry.create("sm_both");
            lb2.attachTo(h);
            assertEquals("M7b speed * multiplier → (10*2)*0.5", 10.0f, lb2.speed(h.id(), 10.0f), 0.001f);
        } finally {
            Actor.remove(h);
        }
    }

    // ---- backward compatibility (no new fields → passthrough) ----

    @Test
    public void buffWithoutNewCallbacksPassesThrough() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("plain", tableFromLua(
                "register_buff{ id='plain', name='p' }", "plain"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("plain");
            lb.attachTo(h);
            assertEquals("attackSkill passthrough", 10, lb.attackSkill(h.id(), 10));
            assertEquals("defenseSkill passthrough", 10, lb.defenseSkill(h.id(), 10));
            assertEquals("drRoll passthrough", 4, lb.drRoll(h.id(), 4));
            assertEquals("speed passthrough", 7.0f, lb.speed(h.id(), 7.0f), 0.001f);
            assertEquals("no haste → multiplier 1.0", 1.0f, LuaBuff.dispatchHasteMultiplier(h), 0.0f);
            assertEquals("no stealth bonus", 0f, LuaBuff.dispatchStealthBonus(h), 0.0f);
            assertEquals("no regen bonus", 0, LuaBuff.dispatchRegenBonus(h));
            assertNull("no sprite state", lb.computeSpriteState());
            assertNull("no tint/glow", lb.computeTint());
        } finally {
            Actor.remove(h);
        }
    }

    // ---- test_mod scripts use canonical callbacks ----

    @Test
    public void nineScriptsExposeCanonicalCallbacks() {
        LuaEngine.init();
        // Champion of Air: hasteLevel + setGlowing
        LuaTable air = LuaBuffRegistry.getTable("champion_of_air");
        assertNotNull(air);
        assertTrue("champion_of_air.hasteLevel", air.get("hasteLevel").isfunction());
        assertTrue("champion_of_air.setGlowing", air.get("setGlowing").isfunction());

        // Champion of Earth: regenerationBonus + drBonus + setGlowing
        LuaTable earth = LuaBuffRegistry.getTable("champion_of_earth");
        assertTrue("champion_of_earth.regenerationBonus", earth.get("regenerationBonus").isfunction());
        assertTrue("champion_of_earth.drBonus", earth.get("drBonus").isfunction());
        assertTrue("champion_of_earth.setGlowing", earth.get("setGlowing").isfunction());

        // Champion of Fire: attackSkillBonus + setGlowing
        LuaTable fire = LuaBuffRegistry.getTable("champion_of_fire");
        assertTrue("champion_of_fire.attackSkillBonus", fire.get("attackSkillBonus").isfunction());
        assertTrue("champion_of_fire.setGlowing", fire.get("setGlowing").isfunction());

        // Champion of Water: defenceSkillBonus + setGlowing (British spelling)
        LuaTable water = LuaBuffRegistry.getTable("champion_of_water");
        assertTrue("champion_of_water.defenceSkillBonus", water.get("defenceSkillBonus").isfunction());
        assertTrue("champion_of_water.setGlowing", water.get("setGlowing").isfunction());
        assertFalse("champion_of_water no longer uses defenseSkill workaround",
                water.get("defenseSkill").isfunction());

        // DieHard: damage + regenerationBonus
        LuaTable diehard = LuaBuffRegistry.getTable("die_hard");
        assertTrue("die_hard.damage", diehard.get("damage").isfunction());
        assertTrue("die_hard.regenerationBonus", diehard.get("regenerationBonus").isfunction());
        assertFalse("die_hard no longer uses defenseProc workaround",
                diehard.get("defenseProc").isfunction());

        // Cloak: stealthBonus + charSpriteStatus
        LuaTable cloak = LuaBuffRegistry.getTable("cloak");
        assertTrue("cloak.stealthBonus", cloak.get("stealthBonus").isfunction());
        assertTrue("cloak.charSpriteStatus", cloak.get("charSpriteStatus").isfunction());

        // BodyArmor: drBonus + speedMultiplier
        LuaTable armor = LuaBuffRegistry.getTable("body_armor");
        assertTrue("body_armor.drBonus", armor.get("drBonus").isfunction());
        assertTrue("body_armor.speedMultiplier", armor.get("speedMultiplier").isfunction());

        // None of the upgraded scripts carry a `degraded` marker.
        for (String id : new String[]{"champion_of_air", "champion_of_earth", "champion_of_fire",
                "champion_of_water", "die_hard", "cloak", "body_armor"}) {
            assertFalse(id + " not degraded", LuaBuffRegistry.getTable(id).get("degraded").isboolean());
        }
    }

    @Test
    public void championOfWaterStillGlowingViaSetGlowing() throws Exception {
        LuaEngine.init();
        Hero h = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            g.load("RPD.affectBuff(" + h.id() + ", 'champion_of_water', 4)").call();
            LuaBuff lb = findLuaBuff(h, "champion_of_water");
            assertNotNull(lb);
            LuaBuff.TintSpec spec = lb.computeTint();
            assertNotNull("champion_of_water still has a glow", spec);
            assertTrue("glow is an aura", spec.aura);
            assertEquals("water-blue (0x3399FF)", 0x3399FF, spec.color);
            assertEquals(5, spec.rays);
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void championOfWaterDefenceSkillBonusStillApplies() throws Exception {
        LuaEngine.init();
        Hero h = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            g.load("RPD.affectBuff(" + h.id() + ", 'champion_of_water', 4)").call();
            int base = h.defenseSkill(h);
            // lvl=4 → floor(4*1.25) = 5
            assertEquals("defenceSkillBonus bridges into dispatchDefenseSkill",
                    base + 5, Math.round(LuaBuff.dispatchDefenseSkill(h, base)));
        } finally {
            Actor.remove(h);
        }
    }
}
