package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.watabou.utils.Bundle;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * M19a: serializes the safe subset of a Lua value (nil/boolean/string/number/
 * table-of-the-same) to/from a {@link Bundle}, and deep-copies it in memory.
 * Used by {@link LuaTrap} to persist per-instance {@code data} across save/load
 * and to isolate the instance copy from the shared registry spec table.
 *
 * <h3>Safe subset</h3>
 * <ul>
 *   <li>nil, boolean, string</li>
 *   <li>number — int stored losslessly as long, double narrowed to float</li>
 *   <li>table — recursively, keyed by string keys only, arbitrary nesting depth
 *       up to {@link #MAX_DEPTH}</li>
 * </ul>
 * Unsupported leaf types (function/userdata/thread), non-string table keys,
 * self-references, and tables deeper than {@link #MAX_DEPTH} are skipped with an
 * error log and stored as nil — mod input is untrusted at this boundary, so the
 * codec never throws.
 *
 * <h3>Encoding: per-value envelope</h3>
 * Every value (scalars included) encodes to a Bundle {@code {__t, __v}} where
 * {@code __t} is a type code ({@code n/b/s/i/f/t}) and {@code __v} the payload.
 * A table's {@code __v} is a "table bundle" whose keys are the table's string
 * keys and whose values are child envelopes (recursion). {@code __t}/{@code __v}
 * live only inside envelopes, so they can never collide with user keys, and a
 * root scalar encodes identically to a nested one.
 */
final class LuaDataCodec {

    private static final String TAG = "LuaDataCodec";

    /** Beyond this nesting tables are stored as nil rather than risk stack overflow. */
    static final int MAX_DEPTH = 32;

    private static final String ENVELOPE_TYPE  = "__t";
    private static final String ENVELOPE_VALUE = "__v";

    private static final String T_NIL    = "n";
    private static final String T_BOOL   = "b";
    private static final String T_STRING = "s";
    private static final String T_INT    = "i";
    private static final String T_FLOAT  = "f";
    private static final String T_TABLE  = "t";

    private LuaDataCodec() { }

    // ------------------------------------------------------------------
    // deep copy — in-memory isolation of the registry spec's data table
    // ------------------------------------------------------------------

    /** Deep-copies the safe subset into a fresh LuaValue tree (numbers lossless). */
    static LuaValue deepCopy(LuaValue v) {
        return deepCopy(v, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static LuaValue deepCopy(LuaValue v, Set<LuaTable> visited, int depth) {
        if (v == null || v.isnil())   return LuaValue.NIL;
        if (v.isboolean())            return LuaValue.valueOf(v.toboolean());
        // isnumber() MUST precede isstring(): in luaj, LuaNumber/LuaInteger report
        // isstring()==true (numbers are string-coercible), so testing isstring() first
        // would stringify every persisted number — breaking Lua arithmetic on data
        // values (e.g. `data.charges - 1`, `data.charges > 0`).
        if (v.isnumber()) {
            return v.isint() ? LuaValue.valueOf(v.toint())
                             : LuaValue.valueOf(v.todouble());
        }
        if (v.isstring())             return LuaValue.valueOf(v.tojstring());
        if (v.istable()) {
            LuaTable tbl = (LuaTable) v;
            if (!visited.add(tbl)) {
                Gdx.app.error(TAG, "deepCopy: cyclic table reference skipped");
                return LuaValue.NIL;
            }
            if (depth >= MAX_DEPTH) {
                Gdx.app.error(TAG, "deepCopy: table depth exceeds " + MAX_DEPTH + ", skipped");
                visited.remove(tbl);
                return LuaValue.NIL;
            }
            LuaTable out = new LuaTable();
            for (LuaValue key : tbl.keys()) {
                if (!key.isstring()) {
                    Gdx.app.error(TAG, "deepCopy: non-string table key skipped (" + key.typename() + ")");
                    continue;
                }
                LuaValue child = deepCopy(tbl.get(key), visited, depth + 1);
                if (!child.isnil()) out.set(key, child);
            }
            visited.remove(tbl);
            return out;
        }
        Gdx.app.error(TAG, "deepCopy: unsupported type " + v.typename() + " skipped");
        return LuaValue.NIL;
    }

    // ------------------------------------------------------------------
    // Bundle encode/decode — persistence
    // ------------------------------------------------------------------

    static Bundle encode(LuaValue v) {
        return encode(v, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static Bundle encode(LuaValue v, Set<LuaTable> visited, int depth) {
        Bundle env = new Bundle();
        if (v == null || v.isnil()) {
            env.put(ENVELOPE_TYPE, T_NIL);
            return env;
        }
        if (v.isboolean()) {
            env.put(ENVELOPE_TYPE, T_BOOL);
            env.put(ENVELOPE_VALUE, v.toboolean());
            return env;
        }
        // isnumber() MUST precede isstring(): luaj numbers report isstring()==true,
        // so testing strings first would round-trip every number as a string.
        if (v.isnumber()) {
            if (v.isint()) {
                env.put(ENVELOPE_TYPE, T_INT);
                env.put(ENVELOPE_VALUE, (long) v.toint());
            } else {
                env.put(ENVELOPE_TYPE, T_FLOAT);
                env.put(ENVELOPE_VALUE, (float) v.todouble());
            }
            return env;
        }
        if (v.isstring()) {
            env.put(ENVELOPE_TYPE, T_STRING);
            env.put(ENVELOPE_VALUE, v.tojstring());
            return env;
        }
        if (v.istable()) {
            LuaTable tbl = (LuaTable) v;
            if (!visited.add(tbl)) {
                Gdx.app.error(TAG, "encode: cyclic table reference stored as nil");
                env.put(ENVELOPE_TYPE, T_NIL);
                return env;
            }
            if (depth >= MAX_DEPTH) {
                Gdx.app.error(TAG, "encode: table depth exceeds " + MAX_DEPTH + ", stored as nil");
                visited.remove(tbl);
                env.put(ENVELOPE_TYPE, T_NIL);
                return env;
            }
            Bundle tableBundle = new Bundle();
            for (LuaValue key : tbl.keys()) {
                if (!key.isstring()) {
                    Gdx.app.error(TAG, "encode: non-string table key skipped (" + key.typename() + ")");
                    continue;
                }
                tableBundle.put(key.tojstring(), encode(tbl.get(key), visited, depth + 1));
            }
            visited.remove(tbl);
            env.put(ENVELOPE_TYPE, T_TABLE);
            env.put(ENVELOPE_VALUE, tableBundle);
            return env;
        }
        Gdx.app.error(TAG, "encode: unsupported type " + v.typename() + " stored as nil");
        env.put(ENVELOPE_TYPE, T_NIL);
        return env;
    }

    static LuaValue decode(Bundle env) {
        return decode(env, 0);
    }

    private static LuaValue decode(Bundle env, int depth) {
        if (env == null) return LuaValue.NIL;
        if (depth > MAX_DEPTH) {
            Gdx.app.error(TAG, "decode: depth exceeds " + MAX_DEPTH);
            return LuaValue.NIL;
        }
        String t = env.getString(ENVELOPE_TYPE);
        if (t == null) return LuaValue.NIL;
        switch (t) {
            case T_NIL:    return LuaValue.NIL;
            case T_BOOL:   return LuaValue.valueOf(env.getBoolean(ENVELOPE_VALUE));
            case T_STRING: return LuaValue.valueOf(env.getString(ENVELOPE_VALUE));
            case T_INT:    return LuaValue.valueOf(env.getLong(ENVELOPE_VALUE));
            case T_FLOAT:  return LuaValue.valueOf((double) env.getFloat(ENVELOPE_VALUE));
            case T_TABLE: {
                Bundle tableBundle = env.getBundle(ENVELOPE_VALUE);
                if (tableBundle == null) return new LuaTable();
                LuaTable out = new LuaTable();
                ArrayList<String> keys = tableBundle.getKeys();
                if (keys != null) {
                    for (String k : keys) {
                        LuaValue child = decode(tableBundle.getBundle(k), depth + 1);
                        if (!child.isnil()) out.set(k, child);
                    }
                }
                return out;
            }
            default:       return LuaValue.NIL;
        }
    }
}
