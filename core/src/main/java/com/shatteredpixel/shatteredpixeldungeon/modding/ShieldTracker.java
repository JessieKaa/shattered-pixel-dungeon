package com.shatteredpixel.shatteredpixeldungeon.modding;

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
 * {@link com.shatteredpixel.shatteredpixeldungeon.actors.Actor#nextID} resets
 * to 1 on a new game, so an int-keyed static map would leak the previous run's
 * shield points onto a freshly created char that reuses the same id. Keying by
 * the live {@link Char} instance (default identity — Char does not override
 * equals/hashCode) makes the pool die with the char itself: a new run allocates
 * new Char objects, so old entries never collide. {@link WeakHashMap} lets those
 * entries clear on GC once the char is no longer held by the actor registry.
 *
 * <h3>Ephemeral</h3>
 * The pool is not persisted in {@link com.watabou.utils.Bundle}. On save/load
 * the restored {@link Char} is a new object, so its entry is empty until a
 * shield buff re-seeds its declarative {@code shieldAmount} via
 * {@link LuaBuff#attachTo}. Mid-combat depletion is therefore lost on load —
 * the declarative baseline is what survives. See PLAN §Pending Issues.
 *
 * <h3>Orthogonal to SPD's ShieldBuff</h3>
 * SPD's own shield system ({@link com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ShieldBuff}
 * / {@code Barrier} / {@code Char.shielding()}) is consumed inside
 * {@link Char#damage}. This pool is drained earlier, from a Lua buff's
 * {@code defenseProc} via {@code RPD.absorbShield}; the leftover damage then
 * flows through upstream {@code Char.damage} unchanged. The two systems do not
 * read or write each other's state.
 */
final class ShieldTracker {

    private static final int MAX_AMOUNT = 1000;

    private static final WeakHashMap<Char, Integer> POOL = new WeakHashMap<>();

    private ShieldTracker() { }

    /**
     * Add {@code amt} shield points to {@code c} (clamped to {@link #MAX_AMOUNT}).
     * Negative/zero amounts and null targets are ignored. Returns the new total.
     */
    static int addShield(Char c, int amt) {
        if (c == null || amt <= 0) return getShield(c);
        int cur = getShield(c);
        cur = Math.min(MAX_AMOUNT, cur + amt);
        POOL.put(c, cur);
        return cur;
    }

    /**
     * Absorb {@code dmg} from {@code c}'s pool. Returns the leftover damage
     * (0 if fully absorbed). Mirrors {@code ShieldBuff.absorbDamage} semantics
     * so the Lua {@code defenseProc} can hand the result straight back to
     * {@link Char#defenseProc}. Null target / non-positive damage pass through.
     */
    static int absorb(Char c, int dmg) {
        if (c == null || dmg <= 0) return dmg;
        int cur = getShield(c);
        if (cur <= 0) return dmg;
        if (cur >= dmg) {
            POOL.put(c, cur - dmg);
            return 0;
        }
        POOL.put(c, 0);
        return dmg - cur;
    }

    /** Current shield points on {@code c} (0 if none / null). */
    static int getShield(Char c) {
        if (c == null) return 0;
        Integer v = POOL.get(c);
        return v == null ? 0 : v;
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
