package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.watabou.utils.Bundle;
import com.watabou.utils.Reflection;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/**
 * A {@link Buff} whose metadata + lifecycle callbacks ({@code attachTo/act/detach/
 * immunities/onRestore}) come from a Lua table registered via {@code register_buff}.
 * The M6c analogue of {@link LuaMob} — same persistence + callback-dispatch pattern,
 * mapped onto {@link Buff}.
 *
 * <h3>Lifecycle (M6c)</h3>
 * <ul>
 *   <li><b>attachTo(targetId,state)</b> — fresh attach path. Returning {@code false}
 *       rejects the attach. Bundle restore sets {@link #restoring} so SPD's
 *       {@code Char.restoreFromBundle} re-attach skips Lua side effects (no
 *       one-time-effect replay, no duplicate reject).</li>
 *   <li><b>act(selfId,targetId,state)</b> — number ⇒ {@code spend(seconds)};
 *       {@code true} ⇒ {@code spend(TICK)}; {@code nil/false} ⇒ detach. Default
 *       (no function) detaches like {@link Buff#act}.</li>
 *   <li><b>detach(targetId,state)</b> — Lua notified before super cleanup.</li>
 *   <li><b>icon/name/info(state)</b> — read from the table (value or function).</li>
 *   <li><b>immunities(state)</b> — table of whitelist ids resolved to Blob/Buff
 *       Classes via {@link RpdApi.BuffWhitelist}/{@code BlobRegistry}.</li>
 *   <li><b>onRestore(state)</b> — optional, called after Bundle restore so a
 *       script can rebuild non-serializable fields we skipped at save time.</li>
 * </ul>
 *
 * <h3>Per-instance state</h3>
 * Each Java buff owns an isolated {@link LuaTable} {@link #state} passed to every
 * callback. Remished scripts rely on {@code self.data.*}; sharing the registry
 * table across instances would leak state, so we never do. {@link Bundle}
 * cannot store a raw LuaTable, so {@link #storeState}/{@link #loadState}
 * serialize only string/number/boolean/nested-table values and skip
 * function/userdata/thread — the script's {@code onRestore} rebuilds the rest.
 *
 * <h3>Sandbox (D3 / D5'-(a))</h3>
 * Lua never receives a {@link Char} object — only {@code int} ids. Scheduling
 * primitives ({@code postpone}/{@code diactivate}/{@code spend}) stay protected
 * in {@link com.shatteredpixel.shatteredpixeldungeon.actors.Actor}; this class
 * wraps them in public helpers ({@link #refresh}/{@link #makePermanent}) so
 * {@link RpdApi} never has to reach across the package boundary.
 */
public class LuaBuff extends Buff {

    private static final String TAG = "LuaBuff";
    private static final String LUA_BUFF_ID = "lua_buff_id";
    private static final String LUA_BUFF_LEVEL = "lua_buff_level";
    private static final String LUA_BUFF_RESTORING = "lua_buff_restoring";
    private static final String LUA_BUFF_STATE = "lua_buff_state";
    private static final String LUA_BUFF_PERMANENT = "lua_buff_permanent";

    private String luaBuffId;
    private int level = 0;
    private boolean restoring = false;
    private boolean permanent = false;
    private LuaTable state;

    /** Required for {@code Reflection.newInstance} during Bundle restore. */
    public LuaBuff() {
        super();
    }

    LuaBuff(LuaTable tbl) {
        super();
        this.luaBuffId = tbl.get("id").checkjstring();
        this.state = new LuaTable();
    }

    /** Re-applies the non-persisted Lua definition (id) and creates fresh state. */
    private void hydrate(LuaTable tbl) {
        luaBuffId = tbl.get("id").checkjstring();
        if (state == null) state = new LuaTable();
    }

    private LuaTable luaTable() {
        return luaBuffId == null ? null : LuaBuffRegistry.getTable(luaBuffId);
    }

    public String luaBuffId() {
        return luaBuffId;
    }

    public int level() {
        return level;
    }

    public void setLevel(int level) {
        this.level = Math.max(0, level);
    }

    /** Same-id check for stacking without {@code target.buff(LuaBuff.class)} (class-exact). */
    public boolean sameLuaId(String id) {
        return id != null && id.equals(luaBuffId);
    }

    /**
     * Update level and (re)arm the act timer. {@code duration<=0} is treated as
     * "no timer change" — used by {@code makePermanent}/{@code affectLuaBuff} when
     * a script only wants to bump level. Only LuaBuff exposes this because
     * {@link com.shatteredpixel.shatteredpixeldungeon.actors.Actor#postpone} is
     * protected and {@link RpdApi} cannot call it directly.
     */
    public void refresh(int newLevel, float duration) {
        this.level = newLevel;
        if (duration > 0) postpone(duration);
    }

    /**
     * Park this buff at {@code Float.MAX_VALUE} so it never acts, without
     * detaching it. Used by {@code RPD.permanentBuff} for Lua buffs only —
     * Java whitelist buffs are intentionally excluded (source-aware/FlavourBuff
     * semantics would be wrong).
     */
    public void makePermanent() {
        permanent = true;
        diactivate();
    }

    public boolean isPermanent() {
        return permanent;
    }

    @Override
    public boolean attachTo(Char target) {
        if (restoring) {
            // SPD Char.restoreFromBundle re-attaches each buff; skip Lua side
            // effects so one-time attach behaviours do not replay on load.
            boolean attached = super.attachTo(target);
            restoring = false;
            return attached;
        }
        if (!super.attachTo(target)) return false;
        LuaTable tbl = luaTable();
        if (tbl != null) {
            LuaValue fn = tbl.get("attachTo");
            if (fn.isfunction()) {
                try {
                    LuaValue res = fn.call(LuaValue.valueOf(target.id()), state);
                    if (res.isboolean() && !res.toboolean()) {
                        // Script rejected; roll back the attach so the buff is
                        // not left half-registered on the target.
                        detach();
                        return false;
                    }
                } catch (Exception e) {
                    Gdx.app.error(TAG, "attachTo callback threw", e);
                }
            }
        }
        return true;
    }

    @Override
    public boolean act() {
        if (permanent) {
            // permanent buffs park at MAX_VALUE; act should never fire, but if
            // it does, stay parked rather than detach and silently drop the buff.
            diactivate();
            return true;
        }
        LuaTable tbl = luaTable();
        if (tbl == null) {
            // Script removed between save and load — degrade by detaching so a
            // dangling buff does not pin the actor queue forever.
            detach();
            return true;
        }
        LuaValue fn = tbl.get("act");
        if (!fn.isfunction()) {
            detach();
            return true;
        }
        try {
            LuaValue res = fn.invoke(LuaValue.varargsOf(
                    LuaValue.valueOf(id()),
                    target == null ? LuaValue.NIL : LuaValue.valueOf(target.id()),
                    state)).arg1();
            if (res.isnumber()) {
                float t = (float) res.todouble();
                if (t > 0) spend(t);
                else detach();
            } else if (res.isboolean() && res.toboolean()) {
                spend(TICK);
            } else {
                detach();
            }
        } catch (Exception e) {
            Gdx.app.error(TAG, "act callback threw", e);
            detach();
        }
        return true;
    }

    @Override
    public void detach() {
        if (!restoring) {
            LuaTable tbl = luaTable();
            if (tbl != null) {
                LuaItemCallbacks.callOpt(tbl, "detach",
                        target == null ? LuaValue.NIL : LuaValue.valueOf(target.id()),
                        state);
            }
        }
        super.detach();
    }

    // ---- combat numerical hooks (M7a) ----
    //
    // Each slot mirrors the upstream Char hook name. The host Char dispatches
    // its computed value through every attached LuaBuff in attach order
    // (buffs(LuaBuff.class) iteration is a stable LinkedHashSet), so multiple
    // Lua buffs compose deterministically. Lua receives only int ids + the
    // current int/float value (D5'-(a): never a Char object); a missing
    // function, Lua error, or non-numeric return passes the value through
    // unchanged so a broken script never freezes combat.

    /** Amend outgoing damage on the bearer's own attack. */
    public int attackProc(int selfId, int enemyId, int damage) {
        LuaTable tbl = luaTable();
        if (tbl == null) return damage;
        return LuaItemCallbacks.callOptInt(tbl, "attackProc", damage,
                LuaValue.valueOf(selfId), LuaValue.valueOf(enemyId), LuaValue.valueOf(damage));
    }

    /** Amend incoming damage before it is applied to the bearer. */
    public int defenseProc(int selfId, int enemyId, int damage) {
        LuaTable tbl = luaTable();
        if (tbl == null) return damage;
        return LuaItemCallbacks.callOptInt(tbl, "defenseProc", damage,
                LuaValue.valueOf(selfId), LuaValue.valueOf(enemyId), LuaValue.valueOf(damage));
    }

    /** Amend the bearer's damage-reduction roll (armor-style DR). */
    public int drRoll(int selfId, int dr) {
        LuaTable tbl = luaTable();
        if (tbl == null) return dr;
        return LuaItemCallbacks.callOptInt(tbl, "drRoll", dr,
                LuaValue.valueOf(selfId), LuaValue.valueOf(dr));
    }

    /** Amend the bearer's movement-speed multiplier (float). */
    public float speed(int selfId, float spd) {
        LuaTable tbl = luaTable();
        if (tbl == null) return spd;
        return LuaItemCallbacks.callOptFloat(tbl, "speed", spd,
                LuaValue.valueOf(selfId), LuaValue.valueOf(spd));
    }

    // ---- M7b skill + charAct hooks ----
    //
    // attackSkill/defenseSkill amend the bearer's to-hit / evasion. Unlike the
    // M7a slots, these are NOT dispatched from the Char method body: Hero and
    // Mob override attackSkill/defenseSkill without calling super, and ~20 mob
    // subclasses override attackSkill individually, so a base-method dispatch
    // would silently miss them. Instead the host dispatches at the two real
    // combat read sites — Char.hit() and Stone.proc() — via the static helpers
    // below, which compose every attached LuaBuff in attach order.

    /** Amend the bearer's attackSkill (to-hit). int in/out; float boundary at the dispatch helper. */
    public int attackSkill(int selfId, int atk) {
        LuaTable tbl = luaTable();
        if (tbl == null) return atk;
        return LuaItemCallbacks.callOptInt(tbl, "attackSkill", atk,
                LuaValue.valueOf(selfId), LuaValue.valueOf(atk));
    }

    /** Amend the bearer's defenseSkill (evasion). int in/out; float boundary at the dispatch helper. */
    public int defenseSkill(int selfId, int def) {
        LuaTable tbl = luaTable();
        if (tbl == null) return def;
        return LuaItemCallbacks.callOptInt(tbl, "defenseSkill", def,
                LuaValue.valueOf(selfId), LuaValue.valueOf(def));
    }

    /**
     * Compose every attached LuaBuff's {@code attackSkill} amendment onto
     * {@code v} (a float, matching {@link Char#hit}'s local). Used at the
     * call site so Hero/Mob/mob-subclass overrides are all covered without
     * editing each one.
     */
    public static float dispatchAttackSkill(Char self, float v) {
        if (self == null) return v;
        int iv = Math.round(v);
        for (Buff b : self.buffs()) {
            if (b instanceof LuaBuff) {
                iv = ((LuaBuff) b).attackSkill(self.id(), iv);
            }
        }
        return iv;
    }

    /** Twin of {@link #dispatchAttackSkill} for defenseSkill. */
    public static float dispatchDefenseSkill(Char self, float v) {
        if (self == null) return v;
        int iv = Math.round(v);
        for (Buff b : self.buffs()) {
            if (b instanceof LuaBuff) {
                iv = ((LuaBuff) b).defenseSkill(self.id(), iv);
            }
        }
        return iv;
    }

    /**
     * Fire the advisory {@code charAct} callback on every LuaBuff attached to
     * {@code c}. Dispatched from {@code Actor.process} before the Char's own
     * {@code act()} so it covers Hero, Mob, and every mob-subclass / LuaMob
     * override (several skip {@code super.act()}; LuaMob skips it entirely when
     * Lua takes over the tick). {@code charAct} is the Char-level per-tick
     * active-behaviour hook (distinct from {@link #act}, the buff's own
     * lifecycle timer): no return value is consumed — a script that wants to
     * detach during charAct calls {@code RPD.detachBuff} itself.
     */
    public static void dispatchCharAct(Char c) {
        if (c == null) return;
        for (Buff b : c.buffs()) {
            if (b instanceof LuaBuff) {
                ((LuaBuff) b).charAct();
            }
        }
    }

    /** Per-tick Char-level callback; advisory (return ignored). */
    private void charAct() {
        LuaTable tbl = luaTable();
        if (tbl == null) return;
        LuaItemCallbacks.callOpt(tbl, "charAct",
                LuaValue.valueOf(id()),
                target == null ? LuaValue.NIL : LuaValue.valueOf(target.id()),
                state);
    }

    @Override
    public int icon() {
        return readIntField("icon", BuffIndicator.NONE);
    }

    @Override
    public String name() {
        String s = readStringField("name", null);
        return s != null ? s : (luaBuffId != null ? luaBuffId : "LuaBuff");
    }

    @Override
    public String desc() {
        return readStringField("info", name());
    }

    @Override
    public String iconTextDisplay() {
        if (level != 0) return Integer.toString(level);
        if (!permanent && cooldown() > 0) return Integer.toString((int) visualcooldown());
        return "";
    }

    /**
     * Resolve a Lua {@code immunities} field (id list or function returning one)
     * to whitelisted Java Classes. Unrecognised ids are dropped (logged) so a
     * script cannot claim immunity to anything outside the M6c whitelist.
     */
    @Override
    public HashSet<Class> immunities() {
        HashSet<Class> out = new HashSet<>();
        LuaTable tbl = luaTable();
        if (tbl == null) return out;
        LuaValue field = tbl.get("immunities");
        LuaValue ids = null;
        if (field.isfunction()) {
            try {
                ids = field.call(state);
            } catch (Exception e) {
                Gdx.app.error(TAG, "immunities callback threw", e);
            }
        } else if (field.istable()) {
            ids = field;
        }
        if (ids != null && ids.istable()) {
            for (Varargs k = ids.next(LuaValue.NIL); !k.arg1().isnil(); k = ids.next(k.arg1())) {
                String id = k.arg(2).optjstring("");
                Class<?> c = resolveImmunityClass(id);
                if (c != null) out.add(c);
                else if (!id.isEmpty()) Gdx.app.error(TAG, "immunities dropped unknown id: " + id);
            }
        }
        return out;
    }

    private static Class<?> resolveImmunityClass(String id) {
        if (id == null || id.isEmpty()) return null;
        Class<?> b = RpdApi.BlobRegistry.lookup(id);
        if (b != null) return b;
        return RpdApi.BuffWhitelist.lookupClass(id);
    }

    private String readStringField(String name, String fallback) {
        LuaTable tbl = luaTable();
        if (tbl == null) return fallback;
        LuaValue v = tbl.get(name);
        if (v.isfunction()) {
            try {
                LuaValue r = v.call(state);
                if (r.isstring()) return r.tojstring();
            } catch (Exception e) {
                Gdx.app.error(TAG, name + " callback threw", e);
            }
            return fallback;
        }
        if (v.isstring()) return v.tojstring();
        return fallback;
    }

    private int readIntField(String name, int fallback) {
        LuaTable tbl = luaTable();
        if (tbl == null) return fallback;
        LuaValue v = tbl.get(name);
        if (v.isfunction()) {
            try {
                LuaValue r = v.call(state);
                if (r.isnumber()) return r.toint();
            } catch (Exception e) {
                Gdx.app.error(TAG, name + " callback threw", e);
            }
            return fallback;
        }
        if (v.isnumber()) return v.toint();
        return fallback;
    }

    // ---- persistence (D4) ----

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        if (luaBuffId != null) bundle.put(LUA_BUFF_ID, luaBuffId);
        bundle.put(LUA_BUFF_LEVEL, level);
        bundle.put(LUA_BUFF_RESTORING, true);
        bundle.put(LUA_BUFF_PERMANENT, permanent);
        if (state != null) bundle.put(LUA_BUFF_STATE, serializeState(state));
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        if (bundle.contains(LUA_BUFF_ID)) {
            luaBuffId = bundle.getString(LUA_BUFF_ID);
            LuaTable tbl = LuaBuffRegistry.getTable(luaBuffId);
            if (tbl != null) {
                hydrate(tbl);
            } else {
                // Engine not yet initialised or script removed; the act path
                // will detach gracefully rather than crash.
                state = new LuaTable();
            }
        } else {
            state = new LuaTable();
        }
        level = bundle.getInt(LUA_BUFF_LEVEL);
        restoring = bundle.getBoolean(LUA_BUFF_RESTORING);
        permanent = bundle.getBoolean(LUA_BUFF_PERMANENT);
        if (bundle.contains(LUA_BUFF_STATE)) {
            loadState(bundle.getStringArray(LUA_BUFF_STATE));
        }
        // Lua onRestore is advisory: rebuild non-serializable fields we skipped.
        LuaTable tbl = luaTable();
        if (tbl != null) {
            LuaValue fn = tbl.get("onRestore");
            if (fn.isfunction()) {
                try {
                    fn.call(state);
                } catch (Exception e) {
                    Gdx.app.error(TAG, "onRestore threw", e);
                }
            }
        }
        // restoring flag is consumed by attachTo during SPD's restore pass;
        // keep it set here so that re-attach can skip Lua attach side effects.
    }

    /**
     * Flatten {@code state} into Base64-encoded path/value rows. Values limited
     * to string/number/boolean and nested tables of the same. function/userdata/
     * thread values are skipped — the Lua {@code onRestore} callback is
     * responsible for rebuilding them.
     */
    private static String[] serializeState(LuaTable t) {
        java.util.List<String> out = new java.util.ArrayList<>();
        writeStateRows(t, new java.util.ArrayList<>(), out);
        return out.toArray(new String[0]);
    }

    private static void writeStateRows(LuaTable table, java.util.List<String> path, java.util.List<String> out) {
        for (Varargs k = table.next(LuaValue.NIL); !k.arg1().isnil(); k = table.next(k.arg1())) {
            LuaValue key = k.arg1();
            LuaValue val = k.arg(2);
            String ks = key.isstring() || key.isnumber() ? key.tojstring() : null;
            if (ks == null) continue;
            path.add(ks);
            if (val.istable()) {
                writeStateRows((LuaTable) val, path, out);
            } else {
                String enc = encodeScalar(val);
                if (enc != null) out.add(encodePath(path) + "=" + enc);
            }
            path.remove(path.size() - 1);
        }
    }

    private static String encodePath(java.util.List<String> path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(b64(path.get(i)));
        }
        return sb.toString();
    }

    private static String encodeScalar(LuaValue v) {
        if (v.isnumber()) return "n:" + v.todouble();
        if (v.isstring()) return "s:" + b64(v.tojstring());
        if (v.isboolean()) return "b:" + v.toboolean();
        return null;
    }

    private void loadState(String[] rows) {
        if (state == null) state = new LuaTable();
        for (String row : rows) {
            int eq = row.indexOf('=');
            if (eq < 0) continue;
            String key = row.substring(0, eq);
            String encoded = row.substring(eq + 1);
            LuaValue val = decodeScalar(encoded);
            if (val == null) continue;
            String[] parts = key.split("/");
            if (parts.length == 0) continue;
            LuaTable cur = state;
            for (int i = 0; i < parts.length - 1; i++) {
                String part = unb64(parts[i]);
                if (part == null) { cur = null; break; }
                LuaValue child = cur.get(part);
                LuaTable sub;
                if (child.istable()) {
                    sub = (LuaTable) child;
                } else {
                    sub = new LuaTable();
                    cur.set(part, sub);
                }
                cur = sub;
            }
            if (cur == null) continue;
            String leaf = unb64(parts[parts.length - 1]);
            if (leaf != null) cur.set(leaf, val);
        }
    }

    private static LuaValue decodeScalar(String encoded) {
        if (encoded == null || encoded.length() < 2) return null;
        char tag = encoded.charAt(0);
        String body = encoded.substring(2);
        switch (tag) {
            case 'n': try { return LuaValue.valueOf(Double.parseDouble(body)); } catch (Exception e) { return null; }
            case 's': {
                String s = unb64(body);
                return s == null ? null : LuaValue.valueOf(s);
            }
            case 'b': return LuaValue.valueOf("true".equals(body));
            default: return null;
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        try {
            return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // Suppress unused-import warning for Reflection; kept for future Class-based
    // immunity restore symmetry with LuaMob without re-editing imports.
    @SuppressWarnings("unused")
    private static Class<?> forName(String fqcn) { return Reflection.forName(fqcn); }
}
