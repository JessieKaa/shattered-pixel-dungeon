package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.luaj.vm2.Globals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M20d {@code remixed_full} buff content: the pack's previously-missing
 * {@code scripts/buffs/} dir ships exactly two remixed-style Lua buffs — a shield
 * buff ({@code remixed_full_mana_shield}, declarative ShieldTracker pool drained
 * via {@code RPD.absorbShield}, self-detach when empty) and a regen/aura buff
 * ({@code remixed_full_regen_aura}, periodic {@code RPD.healChar} trickle +
 * {@code regenerationBonus} + {@code setGlowing} aura). Independent of
 * {@link RpdApiBuffTest} (which pins the engine against {@code test_mod}) and of
 * {@link RemixedFullPackTest} (whose manifest asserts deliberately omit buffs).
 *
 * <p>Pins:
 * <ol>
 *   <li>enabled remixed_full registers exactly the two new buffs; disabled loads zero.</li>
 *   <li>mana_shield seeds the shared pool (15), absorbs a hit, and self-detaches
 *       when the pool empties (the M8b contract).</li>
 *   <li>regen_aura's {@code act} trickle-heals the bearer via {@code RPD.healChar}.</li>
 * </ol>
 */
public class RemixedFullBuffContentTest {

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

    @AfterClass
    public static void shutdown() {
        Dungeon.level = savedLevel;
        Dungeon.hero = savedHero;
        com.watabou.noosa.Game.versionCode = savedVersionCode;
        try { if (application != null) application.exit(); } catch (Throwable ignored) { }
    }

    @Before
    public void resetState() throws Exception {
        com.watabou.utils.GameSettings.set(new ModTestSupport.FakePreferences());
        ModRegistry.resetForTests();
        ModTestSupport.resetLuaState();
        ShieldTracker.clearAll();
        Dungeon.level = null;
        Dungeon.hero = null;
    }

    private void enableRemixedFull() throws Exception {
        ModRegistry.scanDir(ModTestSupport.realModsHandle());
        ModRegistry.setEnabled("remixed_full", true);
        // Keep other mods out of the exact-size assertion.
        ModRegistry.setEnabled("remished_lite", false);
        ModRegistry.setEnabled("test_mod", false);
        ModRegistry.setEnabled("regression_demo", false);
    }

    private static Hero freshHero() {
        Hero h = new Hero();
        h.HT = 50;
        h.HP = 50;
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

    // ---------------- registration ----------------

    @Test
    public void enabled_loadsBothRemixedFullBuffs() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        assertTrue("mana_shield registered",
                LuaBuffRegistry.contains("remixed_full_mana_shield"));
        assertTrue("regen_aura registered",
                LuaBuffRegistry.contains("remixed_full_regen_aura"));
        assertEquals("remixed_full ships exactly 2 buffs", 2, LuaBuffRegistry.size());
    }

    @Test
    public void disabled_loadsZeroBuffs() throws Exception {
        ModRegistry.scanDir(ModTestSupport.realModsHandle());
        // remixed_full is default_enabled=false; ensure the others are off too.
        ModRegistry.setEnabled("remixed_full", false);
        ModRegistry.setEnabled("remished_lite", false);
        ModRegistry.setEnabled("test_mod", false);
        ModRegistry.setEnabled("regression_demo", false);
        assertFalse("remixed_full must be disabled", ModRegistry.isEnabled("remixed_full"));

        LuaEngine.init();
        assertEquals("disabled mod contributes 0 Lua buffs", 0, LuaBuffRegistry.size());
        assertFalse("mana_shield absent when disabled",
                LuaBuffRegistry.contains("remixed_full_mana_shield"));
        assertFalse("regen_aura absent when disabled",
                LuaBuffRegistry.contains("remixed_full_regen_aura"));
    }

    // ---------------- mana_shield behavior ----------------

    @Test
    public void manaShield_absorbsAndSelfDetachesWhenPoolEmpty() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        Hero h = freshHero();
        Hero enemy = freshHero();
        try {
            Globals g = LuaEngine.instance().globals();
            g.load("RPD.affectBuff(" + h.id() + ", 'remixed_full_mana_shield', 1)").call();
            assertEquals("declarative shieldAmount=15 seeded the pool",
                    15, ShieldTracker.getShield(h));

            // 19 damage: absorbs 15, 4 passes through, pool empties → self-detach.
            assertEquals("leftover after mana_shield exhausted", 4, h.defenseProc(enemy, 19));
            assertEquals("pool drained to 0", 0, ShieldTracker.getShield(h));
            assertNull("mana_shield self-detached when pool emptied",
                    findLuaBuff(h, "remixed_full_mana_shield"));
        } finally {
            Actor.remove(h);
            Actor.remove(enemy);
        }
    }

    // ---------------- regen_aura behavior ----------------

    @Test
    public void regenAura_actHealsBearer() throws Exception {
        enableRemixedFull();
        LuaEngine.init();

        Hero h = freshHero();
        h.HP = 20; // wounded (HT=50)
        try {
            Globals g = LuaEngine.instance().globals();
            g.load("RPD.affectBuff(" + h.id() + ", 'remixed_full_regen_aura', 1)").call();
            LuaBuff lb = findLuaBuff(h, "remixed_full_regen_aura");
            assertNotNull("regen_aura attached", lb);

            lb.act(); // act() trickle-heals 2 via RPD.healChar(targetId, 2)
            assertEquals("act trickle-healed 2 HP", 22, h.HP);
            assertNotNull("regen_aura stays attached after act (returns 10s cooldown)",
                    findLuaBuff(h, "remixed_full_regen_aura"));
        } finally {
            Actor.remove(h);
        }
    }
}
