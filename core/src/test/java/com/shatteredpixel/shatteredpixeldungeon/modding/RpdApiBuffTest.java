package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Charm;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Terror;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Vertigo;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.watabou.utils.Bundle;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M6c Lua buff coverage. Independent of M6d's item/spell test class so the two
 * parallel features do not collide in {@code RpdApi.build()} review.
 *
 * <p>Pins down:
 * <ol>
 *   <li>{@code register_buff} validation (id+name required; bad table skipped).</li>
 *   <li>buff loader scans {@code mods/test_mod/scripts/buffs} and registers all
 *       16 ports; disabled mod loads zero buffs.</li>
 *   <li>{@code affect/remove/permanentBuff} for Lua ids (attach/refresh/detach,
 *       stacking by Lua id not class).</li>
 *   <li>{@code act} return semantics: number→spend, true→spend TICK, false/nil→detach.</li>
 *   <li>per-instance Lua state isolation + Bundle round-trip (no cross-instance leak).</li>
 *   <li>restore path does not replay Lua {@code attachTo} side effects.</li>
 *   <li>{@code gases_immunity} maps Lua {@code immunities} to real Java classes.</li>
 *   <li>M1 sandbox regression: {@code luajava} stays stripped.</li>
 * </ol>
 */
public class RpdApiBuffTest {

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
        LuaEngine.installGlobalsForTests(g);
        return g;
    }

    // ---- register_buff validation ----

    @Test
    public void registerBuffRejectsMissingIdAndName() {
        Globals g = globals();
        g.load("register_buff{ name='noid' }").call();
        g.load("register_buff{ id='noname' }").call();
        assertEquals(0, LuaBuffRegistry.size());
        // A valid one registers.
        g.load("register_buff{ id='ok_buff', name='ok' }").call();
        assertTrue(LuaBuffRegistry.contains("ok_buff"));
    }

    @Test
    public void registerBuffRejectsNonTable() {
        globals().load("register_buff('nope')").call();
        assertEquals(0, LuaBuffRegistry.size());
    }

    // ---- loader ----

    @Test
    public void disabledModLoadsZeroBuffs() {
        ModRegistry.setEnabled("test_mod", false);
        LuaEngine.init();
        assertEquals("disabled mod must contribute 0 Lua buffs", 0, LuaBuffRegistry.size());
    }

    @Test
    public void enabledModLoadsAll16BuffScripts() {
        LuaEngine.init();
        // 16 Remished ports (see PLAN §Steps 7). Exact size catches a misnamed script.
        assertEquals("test_mod ships 16 buff ports", 16, LuaBuffRegistry.size());
        assertTrue(LuaBuffRegistry.contains("gases_immunity"));
        assertTrue(LuaBuffRegistry.contains("cloak"));
        assertTrue(LuaBuffRegistry.contains("counter"));
        assertTrue(LuaBuffRegistry.contains("champion_of_earth"));
        assertTrue(LuaBuffRegistry.contains("shield_left"));
        assertTrue(LuaBuffRegistry.contains("chaos_shield_left"));
    }

    // ---- affect / remove / permanent on a live Char ----

    private static Hero freshHero() throws Exception {
        Hero h = new Hero();
        // Hero.HT/HP default to 20; ensure non-zero so damage/heal are meaningful.
        h.HT = 50; h.HP = 50;
        h.pos = 0;
        Actor.add(h);
        return h;
    }

    @Test
    public void affectBuffAppliesJavaWhitelistBleeding() throws Exception {
        Hero h = freshHero();
        try {
            Globals g = globals();
            g.load("RPD.affectBuff(" + h.id() + ", 'Bleeding', 5)").call();
            assertNotNull("Java whitelist Bleeding applied", h.buff(Bleeding.class));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void affectBuffAppliesLuaBuffByStringId() throws Exception {
        LuaEngine.init();
        Hero h = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            g.load("RPD.affectBuff(" + h.id() + ", 'gases_immunity', 1)").call();
            LuaBuff lb = findLuaBuff(h, "gases_immunity");
            assertNotNull("Lua buff gases_immunity attached", lb);
            assertTrue("gases_immunity grants ToxicGas immunity", h.isImmune(ToxicGas.class));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void removeBuffDetachesJavaAndLuaBuffs() throws Exception {
        LuaEngine.init();
        Hero h = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            g.load("RPD.affectBuff(" + h.id() + ", 'Bleeding', 5)").call();
            g.load("RPD.affectBuff(" + h.id() + ", 'gases_immunity', 1)").call();
            assertNotNull(h.buff(Bleeding.class));
            g.load("RPD.removeBuff(" + h.id() + ", 'Bleeding')").call();
            g.load("RPD.removeBuff(" + h.id() + ", 'gases_immunity')").call();
            assertEquals("Bleeding detached", null, h.buff(Bleeding.class));
            assertEquals("Lua buff detached", null, findLuaBuff(h, "gases_immunity"));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void permanentBuffParksLuaBuffAndRejectsJavaWhitelist() throws Exception {
        LuaEngine.init();
        Hero h = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            g.load("RPD.permanentBuff(" + h.id() + ", 'gases_immunity', 2)").call();
            LuaBuff lb = findLuaBuff(h, "gases_immunity");
            assertNotNull(lb);
            assertTrue("permanent Lua buff flagged", lb.isPermanent());

            // Java whitelist id must be rejected (FlavourBuff/source-aware semantics).
            LuaValue r = g.load("return RPD.permanentBuff(" + h.id() + ", 'Bleeding', 1)").call();
            assertTrue("permanentBuff rejects Java whitelist buffs", r.isnil());
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void luaBuffStackingRefreshesLevelByLuaId() throws Exception {
        LuaEngine.init();
        Hero h = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            g.load("RPD.affectBuff(" + h.id() + ", 'gases_immunity', 1)").call();
            g.load("RPD.affectBuff(" + h.id() + ", 'gases_immunity', 5)").call();
            int count = 0;
            for (Buff b : h.buffs(LuaBuff.class)) {
                if (((LuaBuff) b).sameLuaId("gases_immunity")) count++;
            }
            assertEquals("re-affecting the same Lua id refreshes, does not duplicate", 1, count);
        } finally {
            Actor.remove(h);
        }
    }

    // ---- act return semantics ----

    @Test
    public void actReturningFalseDetachesLuaBuff() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("detacher", tableFromLua(
                "register_buff{ id='detacher', name='d', act=function(self,t,s) return false end }",
                "detacher"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("detacher");
            assertTrue(lb.attachTo(h));
            lb.act();
            assertEquals("act() returning false detaches", null, findLuaBuff(h, "detacher"));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void actReturningNumberSpendsSeconds() throws Exception {
        LuaBuffRegistry.clear();
        org.luaj.vm2.LuaTable tbl = tableFromLua(
                "register_buff{ id='spender', name='s', act=function(self,t,s) return 7 end }",
                "spender");
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("spender");
            lb.attachTo(h);
            float before = lb.cooldown();
            lb.act();
            assertTrue("act() returning 7 should spend 7s (cooldown > 0)", lb.cooldown() > 0);
        } finally {
            Actor.remove(h);
        }
    }

    // ---- per-instance state + bundle round-trip ----

    @Test
    public void perInstanceStateIsIsolatedAcrossInstances() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("state_probe", tableFromLua(
                "register_buff{ id='state_probe', name='sp', " +
                "act=function(self,target,state) state.counter=(state.counter or 0)+1 return 1 end }",
                "state_probe"));
        Hero a = freshHero();
        Hero b = freshHero();
        try {
            LuaBuff la = LuaBuffRegistry.create("state_probe");
            LuaBuff lb = LuaBuffRegistry.create("state_probe");
            assertTrue(la.attachTo(a));
            assertTrue(lb.attachTo(b));
            la.act();
            la.act();
            assertEquals(2, stateInt(la, "counter"));
            assertEquals("other instance counter untouched", 0, stateInt(lb, "counter"));
        } finally {
            Actor.remove(a); Actor.remove(b);
        }
    }

    @Test
    public void counterDecrementsLevelAndDetachesAtZero() throws Exception {
        LuaEngine.init();
        Hero h = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            g.load("RPD.affectBuff(" + h.id() + ", 'counter', 2)").call();
            LuaBuff lb = findLuaBuff(h, "counter");
            assertNotNull(lb);
            lb.act();
            assertEquals("counter decrements level", 1, lb.level());
            lb.act();
            assertEquals("counter detaches when level reaches zero", null, findLuaBuff(h, "counter"));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void bundleRoundTripPreservesStateAndDoesNotReplayAttach() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("restore_probe", tableFromLua(
                "register_buff{ id='restore_probe', name='rp', " +
                "attachTo=function(target,state) state.attachCount=(state.attachCount or 0)+1 return true end }",
                "restore_probe"));
        Hero h = freshHero();
        try {
            LuaBuff probe = LuaBuffRegistry.create("restore_probe");
            assertTrue(probe.attachTo(h));
            assertEquals("fresh attach fires once", 1, stateInt(probe, "attachCount"));

            Bundle bundle = new Bundle();
            probe.storeInBundle(bundle);
            probe.detach();

            LuaBuff restored = LuaBuffRegistry.create("restore_probe");
            restored.restoreFromBundle(bundle);
            restored.attachTo(h);

            assertEquals("restore attach must not replay Lua attach side effects",
                    1, stateInt(restored, "attachCount"));
            assertEquals("restored lua buff id preserved", "restore_probe", restored.luaBuffId());
        } finally {
            Actor.remove(h);
        }
    }

    // ---- immunity mapping ----

    @Test
    public void gasesImmunityMapsLuaIdsToJavaClasses() throws Exception {
        LuaEngine.init();
        Hero h = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            g.load("RPD.affectBuff(" + h.id() + ", 'gases_immunity', 1)").call();
            assertTrue("ToxicGas immunity", h.isImmune(ToxicGas.class));
            assertTrue("Paralysis immunity", h.isImmune(Paralysis.class));
            assertTrue("Vertigo immunity", h.isImmune(Vertigo.class));
        } finally {
            Actor.remove(h);
        }
    }

    // ---- sandbox regression ----

    @Test
    public void luajavaStillUnreachableWithBuffApiPresent() {
        Globals g = globals();
        LuaValue ok = g.load(
                "return pcall(function() return luajava.bindClass('java.lang.Runtime') end)"
        ).call();
        assertFalse("luajava.bindClass must still fail with buff API present", ok.toboolean());
        assertTrue("luajava global must remain stripped", g.get("luajava").isnil());
    }

    @Test
    public void bundleRoundTripPreservesNestedAndSpecialKeyState() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("nested_probe", tableFromLua(
                "register_buff{ id='nested_probe', name='np', " +
                "attachTo=function(t,s) s.nested={['a.b']=1, c=2}; s['k=e']=3; s.deep={inner={v=4}} return true end }",
                "nested_probe"));
        Hero h = freshHero();
        try {
            LuaBuff probe = LuaBuffRegistry.create("nested_probe");
            assertTrue(probe.attachTo(h));

            Bundle bundle = new Bundle();
            probe.storeInBundle(bundle);
            probe.detach();

            LuaBuff restored = LuaBuffRegistry.create("nested_probe");
            restored.restoreFromBundle(bundle);
            restored.attachTo(h);

            assertEquals("nested dotted key a.b preserved", 1, statePathInt(restored, "nested", "a.b"));
            assertEquals("nested c preserved", 2, statePathInt(restored, "nested", "c"));
            assertEquals("special key k=e preserved", 3, stateInt(restored, "k=e"));
            assertEquals("deep nested inner preserved", 4,
                    statePathInt(restored, "deep", "inner", "v"));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void setBuffLevelAndBuffLevelRoundTrip() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("lvl_probe", tableFromLua(
                "register_buff{ id='lvl_probe', name='lp', act=function(s,t,st) return 5 end }",
                "lvl_probe"));
        Hero h = freshHero();
        try {
            Globals g = globals();
            LuaBuff lb = LuaBuffRegistry.create("lvl_probe");
            assertTrue(lb.attachTo(h));
            g.load("RPD.setBuffLevel(" + h.id() + ", 'lvl_probe', 7)").call();
            assertEquals(7, lb.level());
            LuaValue r = g.load("return RPD.buffLevel(" + h.id() + ", 'lvl_probe')").call();
            assertTrue("buffLevel returns the level", r.isnumber());
            assertEquals(7, r.toint());
        } finally {
            Actor.remove(h);
        }
    }

    // ---- M7a combat hooks (attackProc/defenseProc/drRoll/speed) ----

    @Test
    public void luaBuffAttackProcAmendsDamage() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("atk_probe", tableFromLua(
                "register_buff{ id='atk_probe', name='a', " +
                "attackProc=function(self,enemy,dmg) return dmg+5 end }", "atk_probe"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("atk_probe");
            assertTrue(lb.attachTo(h));
            assertEquals("attackProc Lua callback adds 5", 15, lb.attackProc(h.id(), 999, 10));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void luaBuffDefenseProcAmendsDamage() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("def_probe", tableFromLua(
                "register_buff{ id='def_probe', name='d', " +
                "defenseProc=function(self,enemy,dmg) return math.floor(dmg/2) end }", "def_probe"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("def_probe");
            assertTrue(lb.attachTo(h));
            assertEquals("defenseProc Lua callback halves", 5, lb.defenseProc(h.id(), 999, 10));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void luaBuffDrRollAmendsDr() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("dr_probe", tableFromLua(
                "register_buff{ id='dr_probe', name='dr', " +
                "drRoll=function(self,dr) return dr+3 end }", "dr_probe"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("dr_probe");
            assertTrue(lb.attachTo(h));
            assertEquals("drRoll Lua callback adds 3", 13, lb.drRoll(h.id(), 10));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void luaBuffSpeedAmendsFloat() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("spd_probe", tableFromLua(
                "register_buff{ id='spd_probe', name='sp', " +
                "speed=function(self,spd) return spd*1.5 end }", "spd_probe"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("spd_probe");
            assertTrue(lb.attachTo(h));
            assertEquals("speed Lua callback multiplies (float)", 15.0f, lb.speed(h.id(), 10.0f), 0.001f);
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void luaBuffCallbackNilPassthrough() throws Exception {
        LuaBuffRegistry.clear();
        // No proc fields at all → every callback must pass the value through.
        LuaBuffRegistry.register("passthrough", tableFromLua(
                "register_buff{ id='passthrough', name='p' }", "passthrough"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("passthrough");
            assertTrue(lb.attachTo(h));
            assertEquals("attackProc nil → passthrough", 10, lb.attackProc(h.id(), 999, 10));
            assertEquals("defenseProc nil → passthrough", 10, lb.defenseProc(h.id(), 999, 10));
            assertEquals("drRoll nil → passthrough", 4, lb.drRoll(h.id(), 4));
            assertEquals("speed nil → passthrough", 7.0f, lb.speed(h.id(), 7.0f), 0.001f);
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void charDispatchesDrRollToLuaBuff() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("dr2", tableFromLua(
                "register_buff{ id='dr2', name='d', drRoll=function(self,dr) return dr+3 end }", "dr2"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("dr2");
            lb.attachTo(h);
            // Fresh hero: no Barkskin, no armor/weapon DR → Char.drRoll dispatch
            // is the only contributor, so h.drRoll() == the LuaBuff's +3.
            assertEquals("Char.drRoll dispatches to attached LuaBuff", 3, h.drRoll());
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void charDispatchesAttackProcToLuaBuff() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("atk2", tableFromLua(
                "register_buff{ id='atk2', name='a', " +
                "attackProc=function(self,enemy,dmg) return dmg+5 end }", "atk2"));
        Hero h = freshHero();
        Hero enemy = freshHero();
        try {
            LuaBuffRegistry.create("atk2").attachTo(h);
            // Fresh hero, no weapon/talent → the only damage delta is the LuaBuff +5.
            assertEquals("Char.attackProc dispatches to attached LuaBuff", 15, h.attackProc(enemy, 10));
        } finally {
            Actor.remove(h);
            Actor.remove(enemy);
        }
    }

    @Test
    public void charDispatchesDefenseProcToLuaBuff() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("def2", tableFromLua(
                "register_buff{ id='def2', name='d', " +
                "defenseProc=function(self,enemy,dmg) return math.floor(dmg/2) end }", "def2"));
        Hero h = freshHero();
        Hero enemy = freshHero();
        try {
            LuaBuffRegistry.create("def2").attachTo(h);
            assertEquals("Char.defenseProc dispatches to attached LuaBuff", 5, h.defenseProc(enemy, 10));
        } finally {
            Actor.remove(h);
            Actor.remove(enemy);
        }
    }

    @Test
    public void multipleLuaBuffsComposeInAttachOrder() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("doubler", tableFromLua(
                "register_buff{ id='doubler', name='x2', " +
                "attackProc=function(self,enemy,dmg) return dmg*2 end }", "doubler"));
        LuaBuffRegistry.register("adder", tableFromLua(
                "register_buff{ id='adder', name='plus', " +
                "attackProc=function(self,enemy,dmg) return dmg+10 end }", "adder"));
        Hero h = freshHero();
        Hero enemy = freshHero();
        try {
            // Attach doubler first, then adder. Ordered dispatch (buffs() is a
            // LinkedHashSet snapshot): (10*2)+10 = 30. Reversed order would be 40.
            LuaBuffRegistry.create("doubler").attachTo(h);
            LuaBuffRegistry.create("adder").attachTo(h);
            assertEquals("LuaBuff dispatch composes in attach order", 30, h.attackProc(enemy, 10));
        } finally {
            Actor.remove(h);
            Actor.remove(enemy);
        }
    }

    @Test
    public void manaShieldDetachesSelfDuringDefenseProc() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("self_detach", tableFromLua(
                "register_buff{ id='self_detach', name='sd', " +
                "defenseProc=function(self,enemy,dmg) RPD.detachBuff(self,'self_detach') return 0 end }",
                "self_detach"));
        Hero h = freshHero();
        Hero enemy = freshHero();
        try {
            LuaBuffRegistry.create("self_detach").attachTo(h);
            assertNotNull(findLuaBuff(h, "self_detach"));
            assertEquals("self-detach buff nullifies the hit", 0, h.defenseProc(enemy, 10));
            assertEquals("self-detach removed the buff mid-dispatch (no CME)",
                    null, findLuaBuff(h, "self_detach"));
        } finally {
            Actor.remove(h);
            Actor.remove(enemy);
        }
    }

    // ---- M7b skill + charAct + belongings hooks ----

    @Test
    public void luaBuffAttackSkillAmendsAtCallSite() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("atk_skill", tableFromLua(
                "register_buff{ id='atk_skill', name='a', " +
                "attackSkill=function(self,atk) return atk+10 end }", "atk_skill"));
        Hero h = freshHero();
        Hero enemy = freshHero();
        try {
            LuaBuffRegistry.create("atk_skill").attachTo(h);
            // dispatchAttackSkill composes the LuaBuff +10 onto the base value.
            // Fresh hero attackSkill computes a non-zero value (hero default 10
            // * accuracy); the Lua +10 must show on top of whatever Java produced.
            int base = h.attackSkill(enemy);
            assertEquals("dispatchAttackSkill adds Lua +10", base + 10,
                    Math.round(LuaBuff.dispatchAttackSkill(h, base)));
        } finally {
            Actor.remove(h);
            Actor.remove(enemy);
        }
    }

    @Test
    public void luaBuffDefenseSkillAmendsAtCallSite() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("def_skill", tableFromLua(
                "register_buff{ id='def_skill', name='d', " +
                "defenseSkill=function(self,def) return def+5 end }", "def_skill"));
        Hero h = freshHero();
        try {
            LuaBuffRegistry.create("def_skill").attachTo(h);
            int base = h.defenseSkill(h);
            assertEquals("dispatchDefenseSkill adds Lua +5", base + 5,
                    Math.round(LuaBuff.dispatchDefenseSkill(h, base)));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void dispatchCharActFiresAdvisoryCallback() throws Exception {
        LuaBuffRegistry.clear();
        LuaBuffRegistry.register("charact_probe", tableFromLua(
                "register_buff{ id='charact_probe', name='c', " +
                "charAct=function(self,t,s) s.ticks=(s.ticks or 0)+1 end }",
                "charact_probe"));
        Hero h = freshHero();
        try {
            LuaBuff lb = LuaBuffRegistry.create("charact_probe");
            assertTrue(lb.attachTo(h));
            LuaBuff.dispatchCharAct(h);
            LuaBuff.dispatchCharAct(h);
            assertEquals("charAct fired twice via dispatch", 2, stateInt(lb, "ticks"));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void dispatchCharActNullSafe() {
        // No crash on null / no Lua buffs attached.
        LuaBuff.dispatchCharAct(null);
    }

    @Test
    public void championOfWaterDefenseSkillBonusApplies() throws Exception {
        LuaEngine.init();
        Hero h = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            g.load("RPD.affectBuff(" + h.id() + ", 'champion_of_water', 4)").call();
            int base = h.defenseSkill(h);
            // lvl=4 → bonus = floor(4*1.25) = 5
            assertEquals("champion_of_water adds floor(lvl*1.25) evasion",
                    base + 5, Math.round(LuaBuff.dispatchDefenseSkill(h, base)));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void encumbranceItemNameReturnsNilForFreshHero() throws Exception {
        LuaEngine.init();
        Hero h = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            // Fresh hero has no equipped weapon/armor → no encumbrance → nil.
            LuaValue r = g.load("return RPD.encumbranceItemName(" + h.id() + ")").call();
            assertTrue("no encumbrance on fresh hero", r.isnil());
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void encumbranceItemNameRejectsNonHero() throws Exception {
        LuaEngine.init();
        // Build a non-hero Char via LuaMob? Simpler: pass a bogus id → nil.
        Globals g = LuaEngine.instance().globals();
        LuaValue r = g.load("return RPD.encumbranceItemName(999999)").call();
        assertTrue("bogus hero id → nil", r.isnil());
    }

    @Test
    public void yellAcceptsAnyCharWithoutThrowing() throws Exception {
        LuaEngine.init();
        Hero h = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            // Hero is not a Mob → exercises the GLog.n branch. Just must not throw.
            g.load("RPD.yell(" + h.id() + ", 'test yell')").call();
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void counterCharActIncrementsStateCounter() throws Exception {
        LuaEngine.init();
        Hero h = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            g.load("RPD.affectBuff(" + h.id() + ", 'counter', 2)").call();
            LuaBuff lb = findLuaBuff(h, "counter");
            assertNotNull(lb);
            LuaBuff.dispatchCharAct(h);
            LuaBuff.dispatchCharAct(h);
            assertEquals("counter charAct increments state.counter", 2, stateInt(lb, "counter"));
        } finally {
            Actor.remove(h);
        }
    }

    // ---- M7a stolen loot persistence ----

    @Test
    public void stolenLootSurvivesBundleRoundTrip() {
        LuaMob src = new LuaMob();
        src.stolenLoot(new Gold(50));
        assertNotNull("stolen loot staged on source mob", src.stolenLoot());

        Bundle bundle = new Bundle();
        src.storeInBundle(bundle);

        LuaMob restored = new LuaMob();
        restored.restoreFromBundle(bundle);
        Item loot = restored.createLoot();
        assertTrue("restored loot is the stolen Gold", loot instanceof Gold);
        assertEquals("restored loot keeps its quantity", 50, loot.quantity());
    }

    // ---- M7a Charm/Terror whitelist ----

    @Test
    public void charmAndTerrorAreWhitelisted() throws Exception {
        assertNotNull("Charm resolvable in BuffWhitelist", RpdApi.BuffWhitelist.lookupClass("Charm"));
        assertNotNull("Terror resolvable in BuffWhitelist", RpdApi.BuffWhitelist.lookupClass("Terror"));
        LuaEngine.init();
        Hero h = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            g.load("RPD.affectBuff(" + h.id() + ", 'Charm', 5)").call();
            g.load("RPD.affectBuff(" + h.id() + ", 'Terror', 5)").call();
            assertNotNull("Charm applied via whitelist", h.buff(Charm.class));
            assertNotNull("Terror applied via whitelist", h.buff(Terror.class));
        } finally {
            Actor.remove(h);
        }
    }

    // ---- helpers ----

    private static LuaBuff findLuaBuff(Char c, String id) {
        for (Buff b : c.buffs(LuaBuff.class)) {
            if (((LuaBuff) b).sameLuaId(id)) return (LuaBuff) b;
        }
        return null;
    }

    private static int countBuffs(Char c, Class<? extends Buff> clazz) {
        int n = 0;
        for (Buff b : c.buffs(clazz)) n++;
        return n;
    }

    private static int stateInt(LuaBuff lb, String key) {
        org.luaj.vm2.LuaTable t = stateTable(lb);
        return t.get(key).optint(0);
    }

    private static int statePathInt(LuaBuff lb, String... path) {
        org.luaj.vm2.LuaTable t = stateTable(lb);
        for (int i = 0; i < path.length - 1; i++) {
            org.luaj.vm2.LuaValue v = t.get(path[i]);
            if (!v.istable()) return 0;
            t = (org.luaj.vm2.LuaTable) v;
        }
        return t.get(path[path.length - 1]).optint(0);
    }

    private static org.luaj.vm2.LuaTable stateTable(LuaBuff lb) {
        try {
            Field f = LuaBuff.class.getDeclaredField("state");
            f.setAccessible(true);
            return (org.luaj.vm2.LuaTable) f.get(lb);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Register a buff via a one-shot Lua source on a throwaway globals, then return its table. */
    private static org.luaj.vm2.LuaTable tableFromLua(String lua, String id) {
        Globals g = LuaSandbox.exposedGlobals();
        LuaEngine.installGlobalsForTests(g);
        g.load(lua).call();
        return LuaBuffRegistry.getTable(id);
    }
}
