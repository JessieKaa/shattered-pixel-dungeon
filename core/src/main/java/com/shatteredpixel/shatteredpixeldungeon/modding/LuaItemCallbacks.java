package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * Dispatches Java→Lua callbacks for {@link LuaItem}.
 *
 * <p>All entry points are best-effort: a missing function field, a thrown Lua
 * error, or a non-numeric return value must never crash the host. Failures are
 * logged via {@code Gdx.app.error} and the caller gets the supplied fallback so
 * upstream gameplay (damage, equip) proceeds undisturbed.
 *
 * <p>M2 callbacks hand Lua {@code int} char ids (D3 option B: never coerce Char
 * objects — that would bypass the M1 sandbox). Return values are converted with
 * {@code optint} so a Lua {@code nil} or a non-number degrades to the fallback.
 */
final class LuaItemCallbacks {

    private static final String TAG = "LuaItemCallbacks";

    private LuaItemCallbacks() { }

    /**
     * Call {@code fnName} on {@code table} if it is a function. Swallows every
     * failure (no field, not a function, Lua error). Returns nothing — use
     * {@link #callOptInt} when the callback should override a Java value.
     */
    static void callOpt(LuaTable table, String fnName, LuaValue... args) {
        if (table == null) return;
        try {
            LuaValue fn = table.get(fnName);
            if (!fn.isfunction()) return;
            fn.invoke(LuaValue.varargsOf(args));
        } catch (Exception e) {
            Gdx.app.error(TAG, "callback " + fnName + " threw", e);
        }
    }

    /**
     * Call {@code fnName} on {@code table}, expecting a numeric return that
     * overrides {@code fallback}. On any failure (no field, Lua error,
     * non-numeric return, nil) returns {@code fallback} unchanged so the host
     * keeps its upstream-computed value.
     */
    static int callOptInt(LuaTable table, String fnName, int fallback, LuaValue... args) {
        if (table == null) return fallback;
        try {
            LuaValue fn = table.get(fnName);
            if (!fn.isfunction()) return fallback;
            Varargs res = fn.invoke(LuaValue.varargsOf(args));
            LuaValue first = res.arg1();
            if (first.isnil()) return fallback;
            // for strict numeric checks, isnumber() gates the override so a Lua
            // string can't sneak in.
            if (!first.isnumber()) {
                Gdx.app.error(TAG, "callback " + fnName + " returned non-number " + first.typename() + "; using fallback");
                return fallback;
            }
            return first.toint();
        } catch (Exception e) {
            Gdx.app.error(TAG, "callback " + fnName + " threw", e);
            return fallback;
        }
    }

    /**
     * Float-returning twin of {@link #callOptInt}, for callbacks that override a
     * {@code float} host value (e.g. {@code Char#speed()}). Same failure contract:
     * no field / Lua error / non-numeric / nil returns {@code fallback} so the
     * host keeps its upstream-computed value.
     */
    static float callOptFloat(LuaTable table, String fnName, float fallback, LuaValue... args) {
        if (table == null) return fallback;
        try {
            LuaValue fn = table.get(fnName);
            if (!fn.isfunction()) return fallback;
            Varargs res = fn.invoke(LuaValue.varargsOf(args));
            LuaValue first = res.arg1();
            if (first.isnil()) return fallback;
            if (!first.isnumber()) {
                Gdx.app.error(TAG, "callback " + fnName + " returned non-number " + first.typename() + "; using fallback");
                return fallback;
            }
            return (float) first.todouble();
        } catch (Exception e) {
            Gdx.app.error(TAG, "callback " + fnName + " threw", e);
            return fallback;
        }
    }

    /** Convenience: wrap an int char id as a LuaValue argument. */
    static LuaValue arg(int charId) {
        return LuaValue.valueOf(charId);
    }
}
