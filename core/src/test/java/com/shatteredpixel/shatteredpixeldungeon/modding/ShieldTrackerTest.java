package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * M8b unit coverage for {@link ShieldTracker} — the fork-local shared shield
 * pool. Pure Java (no Lua); the Lua-side integration (declarative seed,
 * defenseProc drain, recharge) is covered in {@link RpdApiBuffTest}.
 *
 * <p>Pins: add accumulates + caps + ignores non-positive; absorb partial / full
 * / empty-pool / non-positive; get / clear / clearAll; two bearers are
 * independent (the Char-identity key must not bleed one char's shield onto
 * another).
 */
public class ShieldTrackerTest {

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
    public void resetPool() {
        ShieldTracker.clearAll();
    }

    private static Hero freshHero() {
        Hero h = new Hero();
        h.HT = 50; h.HP = 50;
        h.pos = 0;
        Actor.add(h);
        return h;
    }

    // ---- add ----

    @Test
    public void addAccumulatesAcrossCalls() {
        Hero h = freshHero();
        try {
            assertEquals(5, ShieldTracker.addShield(h, 5));
            assertEquals(8, ShieldTracker.addShield(h, 3));
            assertEquals(8, ShieldTracker.getShield(h));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void addCapsAtMaxAmount() {
        Hero h = freshHero();
        try {
            assertEquals("single huge add clamped to 1000", 1000, ShieldTracker.addShield(h, 2000));
            assertEquals(1000, ShieldTracker.getShield(h));
            // further adds cannot exceed the cap
            ShieldTracker.addShield(h, 500);
            assertEquals("repeated add stays capped", 1000, ShieldTracker.getShield(h));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void addIgnoresNonPositiveAndNull() {
        assertEquals("null target no-op", 0, ShieldTracker.addShield(null, 5));
        Hero h = freshHero();
        try {
            assertEquals("negative ignored", 0, ShieldTracker.addShield(h, -5));
            assertEquals("zero ignored", 0, ShieldTracker.addShield(h, 0));
            assertEquals(0, ShieldTracker.getShield(h));
        } finally {
            Actor.remove(h);
        }
    }

    // ---- absorb ----

    @Test
    public void absorbFullyWhenShieldCoversDamage() {
        Hero h = freshHero();
        try {
            ShieldTracker.addShield(h, 10);
            assertEquals("shield 10 vs dmg 6 → 0 leftover", 0, ShieldTracker.absorb(h, 6));
            assertEquals("pool reduced to 4", 4, ShieldTracker.getShield(h));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void absorbPartiallyAndDrainsPool() {
        Hero h = freshHero();
        try {
            ShieldTracker.addShield(h, 10);
            assertEquals("shield 10 vs dmg 14 → 4 leftover", 4, ShieldTracker.absorb(h, 14));
            assertEquals("pool drained to 0", 0, ShieldTracker.getShield(h));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void absorbOnEmptyPoolPassesDamageThrough() {
        Hero h = freshHero();
        try {
            assertEquals("no shield → full damage through", 10, ShieldTracker.absorb(h, 10));
            assertEquals(0, ShieldTracker.getShield(h));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void absorbNonPositivePassesThrough() {
        Hero h = freshHero();
        try {
            ShieldTracker.addShield(h, 10);
            assertEquals("zero damage → 0", 0, ShieldTracker.absorb(h, 0));
            assertEquals("pool untouched by zero damage", 10, ShieldTracker.getShield(h));
            assertEquals("negative damage → returned as-is", -3, ShieldTracker.absorb(h, -3));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void absorbNullTargetReturnsDamage() {
        assertEquals(7, ShieldTracker.absorb(null, 7));
    }

    // ---- get / clear / clearAll / independence ----

    @Test
    public void getShieldZeroForUnknownAndNull() {
        Hero h = freshHero();
        try {
            assertEquals(0, ShieldTracker.getShield(h));
            assertEquals(0, ShieldTracker.getShield(null));
        } finally {
            Actor.remove(h);
        }
    }

    @Test
    public void clearDropsOneBearersPool() {
        Hero a = freshHero();
        Hero b = freshHero();
        try {
            ShieldTracker.addShield(a, 5);
            ShieldTracker.addShield(b, 7);
            ShieldTracker.clear(a);
            assertEquals("a cleared", 0, ShieldTracker.getShield(a));
            assertEquals("b untouched", 7, ShieldTracker.getShield(b));
        } finally {
            Actor.remove(a);
            Actor.remove(b);
        }
    }

    @Test
    public void clearAllEmptiesEveryEntry() {
        Hero a = freshHero();
        Hero b = freshHero();
        try {
            ShieldTracker.addShield(a, 5);
            ShieldTracker.addShield(b, 7);
            ShieldTracker.clearAll();
            assertEquals(0, ShieldTracker.getShield(a));
            assertEquals(0, ShieldTracker.getShield(b));
        } finally {
            Actor.remove(a);
            Actor.remove(b);
        }
    }

    @Test
    public void twoBearersHaveIndependentPools() {
        // The Char-identity key is what stops cross-run / cross-char leakage;
        // this pins that two live chars never share a pool entry.
        Hero a = freshHero();
        Hero b = freshHero();
        try {
            ShieldTracker.addShield(a, 5);
            assertEquals("b has no shield before any add", 0, ShieldTracker.getShield(b));
            ShieldTracker.absorb(b, 3);
            assertEquals("absorbing on b did not touch a", 5, ShieldTracker.getShield(a));
        } finally {
            Actor.remove(a);
            Actor.remove(b);
        }
    }

    @Test
    public void reattachSameCharReusesItsPool() {
        // Same Char object across a detach/reattach lifecycle keeps its pool —
        // this is what lets a shield buff query ShieldTracker after refresh().
        Hero h = freshHero();
        try {
            ShieldTracker.addShield(h, 10);
            Char sameRef = h;
            assertEquals(10, ShieldTracker.getShield(sameRef));
            ShieldTracker.absorb(sameRef, 4);
            assertEquals(6, ShieldTracker.getShield(h));
        } finally {
            Actor.remove(h);
        }
    }
}
