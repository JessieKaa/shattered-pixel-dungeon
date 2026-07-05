package com.shatteredpixel.shatteredpixeldungeon.modding;

import org.luaj.vm2.LuaTable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Holds Lua-defined ally (pet) tables keyed by id. 1:1 analogue of
 * {@link LuaMobRegistry}: tables are stored as-is so {@link LuaAlly} can
 * re-hydrate from them after a save/load cycle (the Lua table is the single
 * source of truth for hp/ht/attack/defense/name/sprite + AI/command callbacks).
 *
 * <p>M3b scope: friendly pets ({@code LuaAlly extends DirectableAlly}). They
 * inherit the follow/defend/attack state machine and {@code intelligentAlly}
 * from {@link com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.DirectableAlly}.
 *
 * <p>Allies registered here are <b>not</b> part of the vanilla spawn pool —
 * {@code Level.createMob}/{@code MobSpawner} are untouched (C3/C4). A Lua ally
 * only enters a level when {@code RPD.spawnAlly(id, pos)} instantiates it via
 * {@link #create(String)} and hands it to {@code GameScene.add}.
 */
public final class LuaAllyRegistry {

	private static final Map<String, LuaTable> allies = new HashMap<>();

	private LuaAllyRegistry() { }

	public static void register(String id, LuaTable table) {
		allies.put(id, table);
	}

	public static LuaTable getTable(String id) {
		return allies.get(id);
	}

	/** All registered ids. */
	public static Set<String> ids() {
		return Collections.unmodifiableSet(allies.keySet());
	}

	public static LuaAlly create(String id) {
		LuaTable tbl = allies.get(id);
		if (tbl == null) return null;
		return new LuaAlly(tbl);
	}

	public static boolean contains(String id) {
		return allies.containsKey(id);
	}

	/** Number of registered allies. Used by tests. */
	public static int size() {
		return allies.size();
	}

	/** Test helper — clears registered allies so unit tests start from a clean slate. */
	public static void clear() {
		allies.clear();
	}
}
