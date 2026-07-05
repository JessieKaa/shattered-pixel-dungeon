package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barkskin;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FlavourBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Roots;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Slow;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Vertigo;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;

/**
 * The narrow {@code RPD.*} surface injected into the Lua sandbox
 * ({@code globals.set("RPD", RpdApi.build())}). Lua never receives a Char
 * object — only an {@code int} char id resolved here via {@link Actor#findById}
 * (D3 option B). Every write path validates its arguments and silently logs +
 * returns NIL on anything illegal, so a misbehaving script cannot crash combat
 * or tunnel past the buff whitelist.
 *
 * <p>The buff whitelist is per-buff ({@link BuffApplier}) because SPD buffs do
 * not share a uniform "apply(duration)" contract: FlavourBuff subclasses use
 * {@link Buff#prolong}, while Bleeding/Poison/Barkskin expose type-specific
 * setters. Calling {@code Actor.spend} from this package is impossible
 * (protected) and would also leave level-based buffs at level 0.
 */
final class RpdApi {

    private static final String TAG = "RpdApi";

    /** Cap for any single amount/duration argument — large enough for gameplay, small enough to bound a runaway script. */
    private static final float MAX_AMOUNT = 1000f;

    /**
     * Identifiable singleton passed to {@link Char#damage} as the source.
     * {@code Char.damage} reads {@code src.getClass()} for resistance/death-cause
     * categorisation, so a named object (not {@code RpdApi.class}, whose getClass
     * is {@code java.lang.Class}) keeps logs meaningful.
     */
    static final Object LUA_SOURCE = new Object() {
        @Override public String toString() { return "LuaScript"; }
    };

    /** Per-buff application strategy. */
    @FunctionalInterface
    interface BuffApplier {
        void apply(Char target, float amount);
    }

    private RpdApi() { }

    static LuaTable build() {
        LuaTable rpd = new LuaTable();
        rpd.set("affectBuff", new AffectBuff());
        rpd.set("damageChar", new DamageChar());
        rpd.set("healChar", new HealChar());
        rpd.set("GLog", new GLogI());
        rpd.set("GLogW", new GLogW());
        rpd.set("charHP", new CharHP());
        rpd.set("charPos", new CharPos());
        rpd.set("charName", new CharName());
        return rpd;
    }

    /** Resolve a Lua-passed char id to a live Char, or null (logged) if missing/wrong type. */
    private static Char resolveChar(LuaValue idVal) {
        if (!idVal.isint()) {
            Gdx.app.error(TAG, "expected int charId, got " + idVal.typename());
            return null;
        }
        Actor a = Actor.findById(idVal.toint());
        if (!(a instanceof Char)) {
            Gdx.app.error(TAG, "charId " + idVal.toint() + " is not a live Char");
            return null;
        }
        return (Char) a;
    }

    private static boolean validAmount(double amt) {
        return amt > 0 && amt <= MAX_AMOUNT && !Double.isNaN(amt);
    }

    // ---- functions ----

    /** {@code RPD.affectBuff(charId, buffName, amount)} — amount semantics are per-buff (duration or level). */
    private static final class AffectBuff extends ThreeArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue buffName, LuaValue amount) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                String name = buffName.optjstring("");
                BuffApplier applier = BuffWhitelist.lookup(name);
                if (applier == null) {
                    Gdx.app.error(TAG, "affectBuff rejected non-whitelisted buff: " + name);
                    return NIL;
                }
                double amt = amount.isnumber() ? amount.todouble() : -1;
                if (!validAmount(amt)) {
                    Gdx.app.error(TAG, "affectBuff rejected amount " + amt + " for " + name);
                    return NIL;
                }
                applier.apply(target, (float) amt);
            } catch (Exception e) {
                Gdx.app.error(TAG, "affectBuff threw", e);
            }
            return NIL;
        }
    }

    /** {@code RPD.damageChar(charId, amount)} — routes through Char.damage so shields/death/immunity still apply. */
    private static final class DamageChar extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue amount) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                double amt = amount.isnumber() ? amount.todouble() : -1;
                if (!validAmount(amt)) {
                    Gdx.app.error(TAG, "damageChar rejected amount " + amt);
                    return NIL;
                }
                target.damage((int) amt, LUA_SOURCE);
            } catch (Exception e) {
                Gdx.app.error(TAG, "damageChar threw", e);
            }
            return NIL;
        }
    }

    /** {@code RPD.healChar(charId, amount)} — positive only, clamped to [0, HT]. */
    private static final class HealChar extends TwoArgFunction {
        @Override public LuaValue call(LuaValue charId, LuaValue amount) {
            try {
                Char target = resolveChar(charId);
                if (target == null) return NIL;
                double amt = amount.isnumber() ? amount.todouble() : -1;
                if (!validAmount(amt)) {
                    Gdx.app.error(TAG, "healChar rejected amount " + amt);
                    return NIL;
                }
                target.HP = Math.min(target.HT, target.HP + (int) amt);
            } catch (Exception e) {
                Gdx.app.error(TAG, "healChar threw", e);
            }
            return NIL;
        }
    }

    private static final class GLogI extends OneArgFunction {
        @Override public LuaValue call(LuaValue msg) { GLog.i(msg.optjstring("")); return NIL; }
    }
    private static final class GLogW extends OneArgFunction {
        @Override public LuaValue call(LuaValue msg) { GLog.w(msg.optjstring("")); return NIL; }
    }
    private static final class CharHP extends OneArgFunction {
        @Override public LuaValue call(LuaValue charId) {
            Char c = resolveChar(charId);
            return c == null ? NIL : LuaValue.valueOf(c.HP);
        }
    }
    private static final class CharPos extends OneArgFunction {
        @Override public LuaValue call(LuaValue charId) {
            Char c = resolveChar(charId);
            return c == null ? NIL : LuaValue.valueOf(c.pos);
        }
    }
    private static final class CharName extends OneArgFunction {
        @Override public LuaValue call(LuaValue charId) {
            Char c = resolveChar(charId);
            return c == null ? NIL : LuaValue.valueOf(c.name());
        }
    }

    /**
     * The buff whitelist. Each entry maps a Lua-facing simple class name to the
     * correct application strategy for that buff type. Names not in this map are
     * rejected by {@link AffectBuff} — this is what stops a script from injecting
     * an invulnerability/hero-clone buff.
     *
     * <p>FlavourBuff subclasses share the {@link Buff#prolong} strategy; the
     * level-based debuffs (Bleeding/Poison/Barkskin) use their public setters.
     */
    static final class BuffWhitelist {
        private static final java.util.Map<String, BuffApplier> ENTRIES = new java.util.HashMap<>();
        static {
            // FlavourBuff family: prolong(target, clazz, duration).
            putFlavour("Roots", Roots.class);
            putFlavour("Slow", Slow.class);
            putFlavour("Cripple", Cripple.class);
            putFlavour("Paralysis", Paralysis.class);
            putFlavour("Vertigo", Vertigo.class);
            putFlavour("Haste", Haste.class);
            // Level-based buffs with type-specific setters.
            ENTRIES.put("Bleeding", (t, amt) -> {
                Bleeding b = Buff.affect(t, Bleeding.class);
                b.set(amt);
            });
            ENTRIES.put("Poison", (t, amt) -> {
                Poison b = Buff.affect(t, Poison.class);
                b.set(amt);
            });
            ENTRIES.put("Barkskin", (t, amt) -> {
                Barkskin b = Buff.affect(t, Barkskin.class);
                int v = Math.max(1, (int) amt);
                b.set(v, v);
            });
        }

        private static <T extends FlavourBuff> void putFlavour(String name, Class<T> clazz) {
            ENTRIES.put(name, (t, amt) -> Buff.prolong(t, clazz, amt));
        }

        static BuffApplier lookup(String simpleName) {
            return ENTRIES.get(simpleName);
        }

        private BuffWhitelist() { }
    }
}
