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
 *   onActivate = function(cell, charId) ... end,  -- optional
 * }
 * }</pre>
 *
 * <p>Lua never receives a {@link Char} object — only {@code int} ids (M1
 * sandbox boundary, same as {@link LuaMob}). {@code charId} is 0 when no char
 * is on the cell (search-triggered / remote).
 *
 * <h3>Persistence</h3>
 *
 * <p>Only {@code lua_trap_id} is persisted; on restore the definitional fields
 * (name/color/shape) are re-hydrated from {@link LuaTrapRegistry}. If the
 * script is gone (engine init failed / mod disabled mid-run), the trap
 * degrades to inactive rather than crashing the save load.
 */
public class LuaTrap extends Trap {

	private static final String TAG = "LuaTrap";
	private static final String LUA_TRAP_ID = "lua_trap_id";

	private String luaTrapId;
	private String nameStr = "???";

	/** Required for {@code Reflection.newInstance} during Bundle restore. */
	public LuaTrap() {
		super();
	}

	public LuaTrap(LuaTable tbl) {
		super();
		hydrate(tbl);
	}

	private void hydrate(LuaTable tbl) {
		luaTrapId = tbl.get("id").checkjstring();
		nameStr = tbl.get("name").optjstring(luaTrapId);
		color = tbl.get("color").optint(GREY);
		shape = tbl.get("shape").optint(DOTS);
		// onActivate is a plain table entry, validated lazily by LuaItemCallbacks.
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
				LuaValue.valueOf(charId));
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
	}
}
