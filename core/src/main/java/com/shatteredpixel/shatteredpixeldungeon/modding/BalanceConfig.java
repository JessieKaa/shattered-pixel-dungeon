package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;

import java.util.Locale;
import java.util.Map;

/**
 * Centralized balance constants for the fork's modding layer (M9c). Pulls the
 * mana/shield numbers that were previously hardcoded as private constants in
 * {@link com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero},
 * {@link com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ManaRegen}, and
 * {@link ShieldTracker} behind public static fields so mods can override them
 * at runtime via {@link #applyModOverrides(Map)} (driven by {@link ModRegistry}
 * from each enabled mod's {@code mod.json} {@code balance} block).
 *
 * <h3>Default = M8b behaviour, except MANA_REGEN_DELAY</h3>
 * Defaults intentionally reproduce the pre-M9c values — {@link #MANA_BASE}=10,
 * {@link #MANA_PER_LEVEL}=2, {@link #SHIELD_MAX}=1000, {@link #SHIELD_DECAY_PER_TURN}=0
 * — so the only behavioural change with no mods installed is {@link #MANA_REGEN_DELAY}
 * 10f → 8f (slightly faster early mana). Shield decay defaults to 0 = no decay,
 * preserving M8b's "hit it to drop it" shield economy; a mod opting into
 * {@code shield_decay_per_turn > 0} turns the knob that M8b left absent.
 *
 * <h3>Mutation discipline</h3>
 * Fields are non-final because mod overrides write them. Production code must
 * only mutate via {@link #applyModOverrides} / {@link #resetToDefaults} (both
 * guarded by {@link ModRegistry}'s sync) so the global stays consistent.
 * Tests that touch fields directly must call {@link #resetToDefaults} in
 * {@code @Before}/{@code @After} to avoid leaking state into unrelated suites.
 */
public final class BalanceConfig {

    private BalanceConfig() {}

    public static volatile int MANA_BASE = 10;
    public static volatile int MANA_PER_LEVEL = 2;
    public static volatile float MANA_REGEN_DELAY = 8f;
    public static volatile int SHIELD_MAX = 1000;
    public static volatile float SHIELD_DECAY_PER_TURN = 0f;

    /**
     * Probability that a vanilla spawn slot in {@link com.shatteredpixel.shatteredpixeldungeon.actors.mobs.MobSpawner}
     * is replaced by a {@link LuaMobFactory} (and thus a random registered Lua mob). Declared in
     * {@code mod.json} {@code balance.lua_mob_spawn_prob}. 0 = Lua mobs never replace vanilla slots
     * (C3 regression baseline); 1 = every rotation has one Lua mob slot.
     */
    public static volatile float LUA_MOB_SPAWN_PROB = 0f;

    /**
     * Apply a mod's {@code balance} overrides on top of the current values.
     * Keys are matched case-insensitively ({@link Locale#ROOT}, so Turkish-i
     * is not a problem) against the canonical names below; unknown keys are
     * silently ignored (forward-compat for future fields). Known keys with
     * non-finite (NaN/Infinity), out-of-range, or absurdly large values are
     * skipped with a {@code Gdx.app.error} log — one bad balance entry must
     * not crash startup or corrupt the global. Int fields floor fractional
     * inputs rather than rejecting them.
     */
    public static void applyModOverrides(Map<String, ? extends Number> overrides) {
        if (overrides == null || overrides.isEmpty()) return;
        for (Map.Entry<String, ? extends Number> e : overrides.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            Number v = e.getValue();
            switch (key) {
                case "mana_base": {
                    Double d = finiteInRange(v, 1, 10000);
                    if (d != null) MANA_BASE = d.intValue();
                    else Gdx.app.error("BalanceConfig", "ignoring mana_base=" + v + " (must be finite, in [1,10000])");
                    break;
                }
                case "mana_per_level": {
                    Double d = finiteInRange(v, 0, 10000);
                    if (d != null) MANA_PER_LEVEL = d.intValue();
                    else Gdx.app.error("BalanceConfig", "ignoring mana_per_level=" + v + " (must be finite, in [0,10000])");
                    break;
                }
                case "mana_regen_delay": {
                    Double d = finiteInRange(v, 1e-6, 10000);
                    if (d != null) MANA_REGEN_DELAY = d.floatValue();
                    else Gdx.app.error("BalanceConfig", "ignoring mana_regen_delay=" + v + " (must be finite, in (0,10000])");
                    break;
                }
                case "shield_max": {
                    Double d = finiteInRange(v, 1, 1_000_000);
                    if (d != null) SHIELD_MAX = d.intValue();
                    else Gdx.app.error("BalanceConfig", "ignoring shield_max=" + v + " (must be finite, in [1,1000000])");
                    break;
                }
                case "shield_decay_per_turn": {
                    Double d = finiteInRange(v, 0, 10000);
                    if (d != null) SHIELD_DECAY_PER_TURN = d.floatValue();
                    else Gdx.app.error("BalanceConfig", "ignoring shield_decay_per_turn=" + v + " (must be finite, in [0,10000])");
                    break;
                }
                case "lua_mob_spawn_prob": {
                    Double d = finiteInRange(v, 0, 1);
                    if (d != null) LUA_MOB_SPAWN_PROB = d.floatValue();
                    else Gdx.app.error("BalanceConfig", "ignoring lua_mob_spawn_prob=" + v + " (must be finite, in [0,1])");
                    break;
                }
                default:
                    // unknown key — silently ignored for forward-compat
                    break;
            }
        }
    }

    /**
     * Return {@code v} as a finite double within {@code [min, max]}, else null.
     * Null input, non-finite (NaN/Infinity), and out-of-range values all yield
     * null so the caller can skip+log uniformly. Rejecting non-finite here is
     * what stops a JSON {@code 1e309} (which parses to Infinity) from slipping
     * through a {@code > 0} guard and corrupting the global.
     */
    private static Double finiteInRange(Number v, double min, double max) {
        if (v == null) return null;
        double d = v.doubleValue();
        if (!Double.isFinite(d) || d < min || d > max) return null;
        return d;
    }

    /** Restore built-in defaults. Called by {@link ModRegistry} before re-merging
     *  enabled mods (so rescans / enable toggles are idempotent) and by tests. */
    public static void resetToDefaults() {
        MANA_BASE = 10;
        MANA_PER_LEVEL = 2;
        MANA_REGEN_DELAY = 8f;
        SHIELD_MAX = 1000;
        SHIELD_DECAY_PER_TURN = 0f;
        LUA_MOB_SPAWN_PROB = 0f;
    }
}
