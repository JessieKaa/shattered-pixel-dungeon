package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;

import java.util.WeakHashMap;

/**
 * Fork-local shield-points pool shared by Lua shield buffs (M8b). Unifies the
 * per-buff hardcoded block logic that mana_shield / shield_left /
 * chaos_shield_left carried in M7a behind a single absorb model, and exposes
 * it to Lua via {@link RpdApi} ({@code RPD.addShield} / {@code charShield} /
 * {@code absorbShield}).
 *
 * <h3>Why Char-keyed, not charId</h3>
 * {@link Actor#nextID} resets to 1 on a new game, so an int-keyed static map
 * would leak the previous run's shield points onto a freshly created char that
 * reuses the same id. Keying by the live {@link Char} instance (default
 * identity — Char does not override equals/hashCode) makes the pool die with
 * the char itself: a new run allocates new Char objects, so old entries never
 * collide. {@link WeakHashMap} lets those entries clear on GC once the char is
 * no longer held by the actor registry.
 *
 * <h3>Ephemeral</h3>
 * The pool is not persisted in {@link com.watabou.utils.Bundle}. On save/load
 * the restored {@link Char} is a new object, so its entry is empty until a
 * shield buff re-seeds its declarative {@code shieldAmount} via
 * {@link LuaBuff#attachTo}. Mid-combat depletion is therefore lost on load —
 * the declarative baseline is what survives.
 *
 * <h3>Orthogonal to SPD's ShieldBuff</h3>
 * SPD's own shield system ({@link com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ShieldBuff}
 * / {@code Barrier} / {@code Char.shielding()}) is consumed inside
 * {@link Char#damage}. This pool is drained earlier, from a Lua buff's
 * {@code defenseProc} via {@code RPD.absorbShield}; the leftover damage then
 * flows through upstream {@code Char.damage} unchanged. The two systems do not
 * read or write each other's state.
 *
 * <h3>M9c shield decay — lazy reconcile against {@link Actor#now()}</h3>
 * The decay knob ({@link BalanceConfig#SHIELD_DECAY_PER_TURN}) is applied
 * lazily on every pool access, not from a per-turn {@code tick()} hook. SPD's
 * turn model has no clean "one Java call per hero turn" choke point: a single
 * logical turn can issue multiple {@code spend()} calls (e.g. mining dark gold
 * spends, refunds via {@code spend(-TICK)}, then spends again) and
 * {@code spendConstant()} is used as a multi-tick ability timer (MonkEnergy),
 * not 1-call = 1-turn. Hooking any of those would multi-decay or decay on
 * refunds. Instead, decay is computed from elapsed game time: {@link Actor#now()}
 * is the global monotonic clock (refunds move an actor's schedule backward but
 * never reverse {@code now}), so {@code (int)(now - lastDecay)} yields true
 * whole turns elapsed regardless of how many {@code spend} calls happened.
 * Default {@code SHIELD_DECAY_PER_TURN == 0} short-circuits reconcile, so M8b
 * behaviour is byte-for-byte preserved.
 */
final class ShieldTracker {

    /** Mutable pool entry: amount + the {@link Actor#now()} clock reading at
     *  which decay was last reconciled. Lazily decayed on every access. */
    private static final class Entry {
        int amount;
        float lastDecay;
        Entry(int amount, float lastDecay) {
            this.amount = amount;
            this.lastDecay = lastDecay;
        }
    }

    private static final WeakHashMap<Char, Entry> POOL = new WeakHashMap<>();

    /** Test seam: when non-NaN, {@link #clock()} returns this instead of
     *  {@link Actor#now()}, so decay tests can advance time deterministically
     *  without driving real actor processing. Package-private. */
    private static float clockOverride = Float.NaN;

    static void setClockForTest(float t) {
        clockOverride = t;
    }

    private static float clock() {
        return Float.isNaN(clockOverride) ? Actor.now() : clockOverride;
    }

    private ShieldTracker() { }

    /**
     * Apply any decay accrued since {@code e.lastDecay} against {@code e.amount}.
     * Decay is {@code ceil(turnsElapsed * SHIELD_DECAY_PER_TURN)}, floored at 0.
     * {@code lastDecay} advances by the whole turns consumed (fractional turns
     * carry), and is kept current even when decay is disabled so a mid-run mod
     * toggle doesn't retroactively decay the entire elapsed interval.
     */
    private static void reconcile(Entry e) {
        float now = clock();
        float elapsed = now - e.lastDecay;
        if (elapsed <= 0f) return;
        int turns = (int) elapsed; // whole turns; fractional carries
        if (turns <= 0) return;
        if (BalanceConfig.SHIELD_DECAY_PER_TURN > 0f) {
            int decay = (int) Math.ceil(turns * BalanceConfig.SHIELD_DECAY_PER_TURN);
            e.amount = Math.max(0, e.amount - decay);
        }
        e.lastDecay += turns;
    }

    /** Reconcile then return {@code c}'s entry, or null if it has none. */
    private static Entry entryFor(Char c) {
        if (c == null) return null;
        Entry e = POOL.get(c);
        if (e == null) return null;
        reconcile(e);
        return e;
    }

    /**
     * Add {@code amt} shield points to {@code c} (clamped to {@link BalanceConfig#SHIELD_MAX}).
     * Negative/zero amounts and null targets are ignored. Returns the new total.
     */
    static int addShield(Char c, int amt) {
        if (c == null || amt <= 0) return getShield(c);
        Entry e = POOL.get(c);
        if (e == null) {
            e = new Entry(0, clock());
            POOL.put(c, e);
        } else {
            reconcile(e);
        }
        e.amount = Math.min(BalanceConfig.SHIELD_MAX, e.amount + amt);
        return e.amount;
    }

    /**
     * Absorb {@code dmg} from {@code c}'s pool. Returns the leftover damage
     * (0 if fully absorbed). Mirrors {@code ShieldBuff.absorbDamage} semantics
     * so the Lua {@code defenseProc} can hand the result straight back to
     * {@link Char#defenseProc}. Null target / non-positive damage pass through.
     */
    static int absorb(Char c, int dmg) {
        if (c == null || dmg <= 0) return dmg;
        Entry e = entryFor(c);
        if (e == null || e.amount <= 0) return dmg;
        if (e.amount >= dmg) {
            e.amount -= dmg;
            return 0;
        }
        int leftover = dmg - e.amount;
        e.amount = 0;
        return leftover;
    }

    /** Current shield points on {@code c} (0 if none / null). */
    static int getShield(Char c) {
        Entry e = entryFor(c);
        return e == null ? 0 : e.amount;
    }

    /** Drop {@code c}'s pool entry entirely (a script can call this via {@code RPD}). */
    static void clear(Char c) {
        if (c != null) POOL.remove(c);
    }

    /** Test / new-run hook — empties the whole pool. */
    static void clearAll() {
        POOL.clear();
    }
}
