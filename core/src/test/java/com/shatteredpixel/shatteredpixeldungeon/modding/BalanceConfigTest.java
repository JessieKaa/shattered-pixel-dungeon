package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.utils.JsonReader;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * M9c coverage for {@link BalanceConfig}, {@link ShieldTracker#tick()} (decay
 * knob), and {@link ModManifest} balance parsing. Pins:
 * <ul>
 *   <li>Defaults reproduce M8b except MANA_REGEN_DELAY 10→8 (the single
 *       behavioural change), and SHIELD_DECAY_PER_TURN=0 (knob off by default).</li>
 *   <li>applyModOverrides merges by canonical key (case-insensitive), ignores
 *       unknown keys, and rejects out-of-range values without corrupting state.</li>
 *   <li>tick() is a true no-op at decay=0 (M8b parity) and decays the pool per
 *       hero turn when &gt;0, floored at 0 (never negative).</li>
 *   <li>ModManifest.fromJson parses the optional balance block; absent → empty,
 *       non-numeric value → IllegalArgumentException (strict).</li>
 * </ul>
 */
public class BalanceConfigTest {

    private static HeadlessApplication application;

    @BeforeClass
    public static void initHeadless() {
        HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
        config.updatesPerSecond = 1;
        application = new HeadlessApplication(new ApplicationAdapter() {}, config);
    }

    @AfterClass
    public static void shutdown() {
        try { if (application != null) application.exit(); } catch (Throwable ignored) { }
    }

    @Before
    public void reset() {
        BalanceConfig.resetToDefaults();
        ShieldTracker.clearAll();
        ShieldTracker.setClockForTest(Float.NaN);
    }

    @After
    public void cleanup() {
        BalanceConfig.resetToDefaults();
        ShieldTracker.clearAll();
        ShieldTracker.setClockForTest(Float.NaN);
    }

    // ---- defaults ----

    @Test
    public void defaultsMatchPlan() {
        assertEquals(10, BalanceConfig.MANA_BASE);
        assertEquals(2, BalanceConfig.MANA_PER_LEVEL);
        assertEquals(8f, BalanceConfig.MANA_REGEN_DELAY, 0f);
        assertEquals(1000, BalanceConfig.SHIELD_MAX);
        assertEquals(0f, BalanceConfig.SHIELD_DECAY_PER_TURN, 0f);
    }

    @Test
    public void resetRestoresDefaultsAfterMutation() {
        BalanceConfig.MANA_REGEN_DELAY = 3f;
        BalanceConfig.SHIELD_MAX = 500;
        BalanceConfig.SHIELD_DECAY_PER_TURN = 2f;
        BalanceConfig.LUA_ITEM_DROP_PROB = 12f;
        BalanceConfig.resetToDefaults();
        assertEquals(8f, BalanceConfig.MANA_REGEN_DELAY, 0f);
        assertEquals(1000, BalanceConfig.SHIELD_MAX);
        assertEquals(0f, BalanceConfig.SHIELD_DECAY_PER_TURN, 0f);
        assertEquals(0f, BalanceConfig.LUA_ITEM_DROP_PROB, 0f);
    }

    // ---- applyModOverrides ----

    @Test
    public void overrideKnownKeysByCanonicalName() {
        Map<String, Number> o = new HashMap<>();
        o.put("mana_regen_delay", 5.0);
        o.put("shield_max", 1500);
        o.put("mana_base", 12);
        o.put("mana_per_level", 3);
        o.put("shield_decay_per_turn", 1.5);
        o.put("lua_item_drop_prob", 7.5);
        BalanceConfig.applyModOverrides(o);
        assertEquals(5f, BalanceConfig.MANA_REGEN_DELAY, 0f);
        assertEquals(1500, BalanceConfig.SHIELD_MAX);
        assertEquals(12, BalanceConfig.MANA_BASE);
        assertEquals(3, BalanceConfig.MANA_PER_LEVEL);
        assertEquals(1.5f, BalanceConfig.SHIELD_DECAY_PER_TURN, 0f);
        assertEquals(7.5f, BalanceConfig.LUA_ITEM_DROP_PROB, 0f);
    }

    @Test
    public void overrideKeysAreCaseInsensitive() {
        Map<String, Number> o = new HashMap<>();
        o.put("MANA_REGEN_DELAY", 6.0);
        BalanceConfig.applyModOverrides(o);
        assertEquals(6f, BalanceConfig.MANA_REGEN_DELAY, 0f);
    }

    @Test
    public void unknownKeyIsSilentlyIgnored() {
        Map<String, Number> o = new HashMap<>();
        o.put("unknown_field", 99);
        o.put("mana_regen_delay", 7.0);
        BalanceConfig.applyModOverrides(o);
        assertEquals(7f, BalanceConfig.MANA_REGEN_DELAY, 0f);
        // unknown key did not throw and did not change any known field beyond the above
        assertEquals(10, BalanceConfig.MANA_BASE);
    }

    @Test
    public void nullAndEmptyMapsAreNoOp() {
        BalanceConfig.applyModOverrides(null);
        assertEquals(8f, BalanceConfig.MANA_REGEN_DELAY, 0f);
        BalanceConfig.applyModOverrides(new HashMap<String, Number>());
        assertEquals(8f, BalanceConfig.MANA_REGEN_DELAY, 0f);
    }

    @Test
    public void outOfRangeValuesAreRejectedWithoutCorruptingState() {
        // non-positive delays/caps and negative decay must be skipped, not applied.
        Map<String, Number> o = new HashMap<>();
        o.put("mana_regen_delay", 0.0);     // rejected (must be > 0)
        o.put("shield_max", -5);             // rejected (must be > 0)
        o.put("mana_base", 0);               // rejected (must be > 0)
        o.put("shield_decay_per_turn", -1f); // rejected (must be >= 0)
        o.put("lua_item_drop_prob", -2f);    // rejected (must be >= 0)
        BalanceConfig.applyModOverrides(o);
        assertEquals("regen delay untouched", 8f, BalanceConfig.MANA_REGEN_DELAY, 0f);
        assertEquals("shield max untouched", 1000, BalanceConfig.SHIELD_MAX);
        assertEquals("mana base untouched", 10, BalanceConfig.MANA_BASE);
        assertEquals("decay untouched", 0f, BalanceConfig.SHIELD_DECAY_PER_TURN, 0f);
        assertEquals("lua_item_drop_prob untouched", 0f, BalanceConfig.LUA_ITEM_DROP_PROB, 0f);
    }

    @Test
    public void fractionalIntFieldsAreFloored() {
        Map<String, Number> o = new HashMap<>();
        o.put("mana_base", 12.9);
        o.put("shield_max", 1499.4);
        BalanceConfig.applyModOverrides(o);
        assertEquals(12, BalanceConfig.MANA_BASE);
        assertEquals(1499, BalanceConfig.SHIELD_MAX);
    }

    @Test
    public void zeroPerLevelIsAllowed() {
        Map<String, Number> o = new HashMap<>();
        o.put("mana_per_level", 0);
        BalanceConfig.applyModOverrides(o);
        assertEquals(0, BalanceConfig.MANA_PER_LEVEL);
    }

    @Test
    public void nonFiniteAndHugeValuesAreRejected() {
        // NaN / Infinity (e.g. from a JSON 1e309) and absurdly large values
        // must not corrupt the globals despite passing a naive > 0 guard.
        Map<String, Number> o = new HashMap<>();
        o.put("mana_base", Double.POSITIVE_INFINITY);
        o.put("mana_regen_delay", Double.NaN);
        o.put("shield_max", 1e18);
        o.put("shield_decay_per_turn", Double.NEGATIVE_INFINITY);
        o.put("lua_item_drop_prob", Double.NaN);
        BalanceConfig.applyModOverrides(o);
        assertEquals(10, BalanceConfig.MANA_BASE);
        assertEquals(8f, BalanceConfig.MANA_REGEN_DELAY, 0f);
        assertEquals(1000, BalanceConfig.SHIELD_MAX);
        assertEquals(0f, BalanceConfig.SHIELD_DECAY_PER_TURN, 0f);
        assertEquals(0f, BalanceConfig.LUA_ITEM_DROP_PROB, 0f);
    }

    // ---- ShieldTracker lazy decay (reconcile on read vs Actor.now clock) ----

    private static Hero freshHero() {
        Hero h = new Hero();
        h.HT = 50; h.HP = 50;
        h.pos = 0;
        Actor.add(h);
        return h;
    }

    @Test
    public void decayZeroLeavesPoolUntouchedAcrossTime() {
        assertEquals(0f, BalanceConfig.SHIELD_DECAY_PER_TURN, 0f);
        ShieldTracker.setClockForTest(0f);
        Hero h = freshHero();
        try {
            ShieldTracker.addShield(h, 10);
            ShieldTracker.setClockForTest(5f);
            ShieldTracker.setClockForTest(20f);
            assertEquals("decay=0 leaves pool untouched (M8b parity)",
                    10, ShieldTracker.getShield(h));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void decayDecaysByElapsedTurnsAndFloorsAtZero() {
        BalanceConfig.SHIELD_DECAY_PER_TURN = 1f;
        ShieldTracker.setClockForTest(0f);
        Hero h = freshHero();
        try {
            ShieldTracker.addShield(h, 5);
            ShieldTracker.setClockForTest(1f);
            assertEquals(4, ShieldTracker.getShield(h));
            ShieldTracker.setClockForTest(3f);
            assertEquals(2, ShieldTracker.getShield(h));
            // drain past zero — must not go negative
            ShieldTracker.setClockForTest(10f);
            assertEquals(0, ShieldTracker.getShield(h));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void fractionalDecayCeilsPerWholeTurn() {
        // 0.5/turn must still decay (ceil(1*0.5)=1); a plain int cast would
        // round to 0 and the knob would silently never fire.
        BalanceConfig.SHIELD_DECAY_PER_TURN = 0.5f;
        ShieldTracker.setClockForTest(0f);
        Hero h = freshHero();
        try {
            ShieldTracker.addShield(h, 3);
            ShieldTracker.setClockForTest(1f);
            assertEquals(2, ShieldTracker.getShield(h));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void decayDecaysMultipleBearersByElapsedTurns() {
        BalanceConfig.SHIELD_DECAY_PER_TURN = 1f;
        ShieldTracker.setClockForTest(0f);
        Hero a = freshHero();
        Hero b = freshHero();
        try {
            ShieldTracker.addShield(a, 4);
            ShieldTracker.addShield(b, 2);
            ShieldTracker.setClockForTest(1f);
            assertEquals(3, ShieldTracker.getShield(a));
            assertEquals(1, ShieldTracker.getShield(b));
        } finally {
            Actor.remove(a);
            Actor.remove(b);
        }
    }

    @Test
    public void multipleReadsInSameTurnDoNotMultiDecay() {
        // Pins the M9c design fix: decay is reconciled against elapsed game
        // time, not per-spend-call, so a single turn that issues several
        // spend()/read calls (the mining-dark-gold pattern) decays exactly
        // once for that turn — not once per call.
        BalanceConfig.SHIELD_DECAY_PER_TURN = 1f;
        ShieldTracker.setClockForTest(0f);
        Hero h = freshHero();
        try {
            ShieldTracker.addShield(h, 5);
            ShieldTracker.setClockForTest(1f);
            assertEquals(4, ShieldTracker.getShield(h));
            assertEquals("repeat read at same clock does not decay again",
                    4, ShieldTracker.getShield(h));
            assertEquals(4, ShieldTracker.getShield(h));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void decayReconcilesRetroactivelyAcrossUntouchedTurns() {
        // A shield left unqueried for several turns still decays for all
        // elapsed time on the next access (lazy reconcile is game-time-correct,
        // not access-count-correct).
        BalanceConfig.SHIELD_DECAY_PER_TURN = 1f;
        ShieldTracker.setClockForTest(0f);
        Hero h = freshHero();
        try {
            ShieldTracker.addShield(h, 5);
            ShieldTracker.setClockForTest(3f);
            assertEquals("3 untouched turns → 3 decay", 2, ShieldTracker.getShield(h));
        } finally {
            Actor.remove(h);
        }
    }

    // ---- ModManifest balance parsing ----

    private static ModManifest parse(String json) {
        return ModManifest.fromJson(new JsonReader().parse(json));
    }

    private static String baseManifest() {
        return "{'id':'t','name':'t','version':'1','spd_version':896}";
    }

    @Test
    public void manifestWithoutBalanceHasEmptyMap() {
        ModManifest m = parse(baseManifest().replace('\'', '"'));
        assertTrue("absent balance → empty map", m.balance.isEmpty());
    }

    @Test
    public void manifestParsesBalanceBlock() {
        String json = baseManifest().replace('\'', '"')
                .replace("}", ", 'balance': {'mana_regen_delay': 5.0, 'shield_decay_per_turn': 1, 'lua_item_drop_prob': 12.5}}")
                .replace('\'', '"');
        ModManifest m = parse(json);
        assertEquals(3, m.balance.size());
        assertEquals(5.0, m.balance.get("mana_regen_delay"), 0f);
        assertEquals(1.0, m.balance.get("shield_decay_per_turn"), 0f);
        assertEquals(12.5, m.balance.get("lua_item_drop_prob"), 0f);
    }

    @Test
    public void manifestEmptyBalanceObjectIsAllowed() {
        String json = baseManifest().replace('\'', '"')
                .replace("}", ", 'balance': {}}")
                .replace('\'', '"');
        ModManifest m = parse(json);
        assertTrue(m.balance.isEmpty());
    }

    @Test
    public void manifestNonObjectBalanceThrows() {
        String json = baseManifest().replace('\'', '"')
                .replace("}", ", 'balance': 5}")
                .replace('\'', '"');
        try {
            parse(json);
            fail("non-object balance must throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("balance"));
        }
    }

    @Test
    public void manifestNonNumericBalanceValueThrows() {
        String json = baseManifest().replace('\'', '"')
                .replace("}", ", 'balance': {'mana_regen_delay': 'fast'}}")
                .replace('\'', '"');
        try {
            parse(json);
            fail("non-numeric balance value must throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("mana_regen_delay"));
        }
    }
}
