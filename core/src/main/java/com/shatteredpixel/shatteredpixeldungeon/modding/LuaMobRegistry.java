package com.shatteredpixel.shatteredpixeldungeon.modding;

import org.luaj.vm2.LuaTable;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds Lua-defined hostile-mob tables keyed by id. 1:1 analogue of
 * {@link LuaItemRegistry}: tables are stored as-is so {@link LuaMob} can
 * re-hydrate from them after a save/load cycle (the Lua table is the single
 * source of truth for hp/ht/attack/defense/name/sprite + AI callbacks, none of
 * which the default {@code Mob} bundle persists).
 *
 * <p>M3a scope: hostile mobs only ({@code LuaMob extends Mob}). Friendly mobs
 * (pets, {@code extends DirectableAlly}) are M3b and will get their own
 * registry/class.
 *
 * <p>Mobs registered here are <b>not</b> part of the vanilla spawn pool —
 * {@code Level.createMob}/{@code MobSpawner} are untouched (C3/C4). A Lua mob
 * only enters a level when {@code RPD.spawnMob(id, pos)} instantiates it via
 * {@link #create(String)} and hands it to {@code GameScene.add}.
 */
public final class LuaMobRegistry {

	private static final Map<String, LuaTable> mobs = new HashMap<>();

	private LuaMobRegistry() { }

	public static void register(String id, LuaTable table) {
		mobs.put(id, table);
	}

	public static LuaTable getTable(String id) {
		return mobs.get(id);
	}

	/** All registered ids. */
	public static java.util.Set<String> ids() {
		return java.util.Collections.unmodifiableSet(mobs.keySet());
	}

	public static LuaMob create(String id) {
		LuaTable tbl = mobs.get(id);
		if (tbl == null) return null;
		return new LuaMob(tbl);
	}

	public static boolean contains(String id) {
		return mobs.containsKey(id);
	}

	/** Uniformly random registered id, or {@code null} when empty. */
	public static String randomId() {
		if (mobs.isEmpty()) return null;
		int idx = com.watabou.utils.Random.Int(mobs.size());
		for (String id : mobs.keySet()) {
			if (idx-- == 0) return id;
		}
		return null; // unreachable
	}

	/** Number of registered mobs. Used by tests. */
	public static int size() {
		return mobs.size();
	}

	/** Test helper — clears registered mobs so unit tests start from a clean slate. */
	public static void clear() {
		mobs.clear();
	}
}
