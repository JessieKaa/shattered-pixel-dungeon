package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.watabou.utils.Bundle;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * M10b: a {@link Trap} whose {@link #activate()} dispatches a Lua
 * {@code onActivate(cell, charId)} callback. Mirrors the {@link LuaNpc}/
 * {@link LuaMob} persistence + callback-dispatch pattern mapped onto
 * {@link Trap}.
 *
 * <h3>Lua contract</h3>
 * <pre>{@code
 * register_trap {
 *   id = "demo_trap",
 *   name = "Lua Trap",        -- optional, defaults to id
 *   color = Trap.GREY,        -- optional, defaults GREY
 *   shape = Trap.DOTS,        -- optional, defaults DOTS
 *   data = { msg = "hi", n = 3 },  -- optional, persisted per-instance (M19a)
 *   onActivate = function(cell, charId, data) ... end,  -- optional
 * }
 * }</pre>
 *
 * <p>Lua never receives a {@link Char} object — only {@code int} ids (M1
 * sandbox boundary, same as {@link LuaMob}). {@code charId} is 0 when no char
 * is on the cell (search-triggered / remote).
 *
 * <p>M19a: {@code data} is deep-copied from the spec at create time so each
 * trap instance owns an isolated copy, persisted via {@link LuaDataCodec}, and
 * passed as the third {@code onActivate} argument. Legacy 2-arg callbacks
 * ignore the extra argument (luaj drops surplus args), so old scripts are
 * unaffected; traps without {@code data} pass {@code nil}.
 *
 * <h3>Persistence</h3>
 *
 * <p>{@code lua_trap_id} is persisted; on restore the definitional fields
 * (name/color/shape) are re-hydrated from {@link LuaTrapRegistry}. {@code data}
 * is persisted independently under {@code lua_trap_data} (it is per-instance,
 * not re-hydrated from the spec). If the script is gone (engine init failed /
 * mod disabled mid-run), the trap degrades to inactive rather than crashing
 * the save load.
 */
public class LuaTrap extends Trap {

	private static final String TAG = "LuaTrap";
	private static final String LUA_TRAP_ID = "lua_trap_id";
	private static final String LUA_TRAP_DATA = "lua_trap_data";

	private String luaTrapId;
	private String nameStr = "???";
	private LuaValue data = LuaValue.NIL;

	/** Required for {@code Reflection.newInstance} during Bundle restore. */
	public LuaTrap() {
		super();
	}

	public LuaTrap(LuaTable tbl) {
		super();
		hydrate(tbl);
		// Deep-copy the spec's data so this instance owns an isolated tree —
		// later mutation here must never bleed into the shared registry spec or
		// sibling instances. Safe subset only (see LuaDataCodec).
		data = LuaDataCodec.deepCopy(tbl.get("data"));
	}

	private void hydrate(LuaTable tbl) {
		luaTrapId = tbl.get("id").checkjstring();
		nameStr = tbl.get("name").optjstring(luaTrapId);
		color = tbl.get("color").optint(GREY);
		shape = tbl.get("shape").optint(DOTS);
		// data is intentionally NOT touched here: hydrate is also the restore
		// path for definitional fields, and data must come from the Bundle, not
		// the spec (otherwise restore would clobber per-instance data).
	}

	private LuaTable luaTable() {
		return luaTrapId == null ? null : LuaTrapRegistry.getTable(luaTrapId);
	}

	@Override
	public void activate() {
		LuaTable tbl = luaTable();
		if (tbl == null) {
			// Script gone — degrade silently. Trap already disarmed by trigger().
			return;
		}
		Char ch = Actor.findChar(pos);
		int charId = ch != null ? ch.id() : 0;
		LuaItemCallbacks.callOpt(tbl, "onActivate",
				LuaValue.valueOf(pos),
				LuaValue.valueOf(charId),
				data);
	}

	@Override
	public String name() {
		return nameStr;
	}

	@Override
	public String desc() {
		return nameStr;
	}

	@Override
	public void storeInBundle(Bundle bundle) {
		super.storeInBundle(bundle);
		if (luaTrapId != null) bundle.put(LUA_TRAP_ID, luaTrapId);
		// Only emit the data key when there is data, so legacy/no-data traps keep
		// their pre-M19a bundle shape exactly.
		if (!data.isnil()) bundle.put(LUA_TRAP_DATA, LuaDataCodec.encode(data));
	}

	@Override
	public void restoreFromBundle(Bundle bundle) {
		super.restoreFromBundle(bundle);
		if (bundle.contains(LUA_TRAP_ID)) {
			luaTrapId = bundle.getString(LUA_TRAP_ID);
			LuaTable tbl = LuaTrapRegistry.getTable(luaTrapId);
			if (tbl != null) {
				hydrate(tbl);
			} else {
				// Engine init failed or script removed — degrade to inactive.
				active = false;
				nameStr = "??? (" + luaTrapId + ")";
			}
		}
		// data is per-instance: always rebuild from the Bundle, independent of
		// whether the spec table is still registered.
		if (bundle.contains(LUA_TRAP_DATA)) {
			data = LuaDataCodec.decode(bundle.getBundle(LUA_TRAP_DATA));
		} else {
			data = LuaValue.NIL;
		}
	}
}
