package com.shatteredpixel.shatteredpixeldungeon.modding;

import org.luaj.vm2.LuaTable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Holds Lua-defined interactive-NPC tables keyed by id. 1:1 analogue of
 * {@link LuaMobRegistry}/{@link LuaAllyRegistry}: tables are stored as-is so
 * {@link LuaNpc} can re-hydrate from them after a save/load cycle (the Lua table
 * is the single source of truth for name/sprite + the {@code onInteract} callback,
 * none of which the default {@link com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.NPC}
 * bundle persists).
 *
 * <p>M4b scope: interactive NPCs ({@code LuaNpc extends NPC}). They inherit
 * {@code NEUTRAL}/{@code PASSIVE} from NPC and clone RatKing's invincibility
 * overrides, so they are safe to drop into a SafeZone — they never fight back,
 * never die, never pick up buffs.
 *
 * <p>NPCs registered here are <b>not</b> part of the vanilla spawn pool. A Lua
 * NPC only enters a level when a {@code lua_npc:<id>} entry in a
 * {@link DataDrivenLevel} mob spec instantiates it via {@link #create(String)}
 * during {@code createMobs}. {@code Level.createMob}/{@code MobSpawner} are
 * untouched (C3/C4).
 */
public final class LuaNpcRegistry {

	private static final Map<String, LuaTable> npcs = new HashMap<>();

	private LuaNpcRegistry() { }

	public static void register(String id, LuaTable table) {
		npcs.put(id, table);
	}

	public static LuaTable getTable(String id) {
		return npcs.get(id);
	}

	/** All registered ids. */
	public static Set<String> ids() {
		return Collections.unmodifiableSet(npcs.keySet());
	}

	public static LuaNpc create(String id) {
		LuaTable tbl = npcs.get(id);
		if (tbl == null) return null;
		return new LuaNpc(tbl);
	}

	public static boolean contains(String id) {
		return npcs.containsKey(id);
	}

	/** Number of registered NPCs. Used by tests. */
	public static int size() {
		return npcs.size();
	}

	/** Test helper — clears registered NPCs so unit tests start from a clean slate. */
	public static void clear() {
		npcs.clear();
	}
}
