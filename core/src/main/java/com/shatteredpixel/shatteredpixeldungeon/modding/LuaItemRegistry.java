package com.shatteredpixel.shatteredpixeldungeon.modding;

import org.luaj.vm2.LuaTable;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds Lua-defined item tables keyed by id. M0 keeps a single item
 * ({@code test_sword}); the Map leaves room for more without redesign.
 *
 * Tables are stored as-is so {@link LuaItem} can re-hydrate from them after a
 * save/load cycle (the Lua table is the single source of truth for
 * name/desc/tier/image, which the default Item bundle does not persist).
 */
public final class LuaItemRegistry {

	private static final Map<String, LuaTable> items = new HashMap<>();

	private LuaItemRegistry() { }

	public static void register(String id, LuaTable table) {
		items.put(id, table);
	}

	public static LuaTable getTable(String id) {
		return items.get(id);
	}

	/** All registered ids. Used by {@link LuaItemPool} to pick uniformly at random. */
	public static java.util.Set<String> ids() {
		return java.util.Collections.unmodifiableSet(items.keySet());
	}

	public static LuaItem create(String id) {
		LuaTable tbl = items.get(id);
		if (tbl == null) return null;
		return new LuaItem(tbl);
	}

	public static boolean contains(String id) {
		return items.containsKey(id);
	}

	/** Number of registered items. Used by LuaEngine to warn on empty scans and by tests. */
	public static int size() {
		return items.size();
	}

	/** Test helper — clears registered items so unit tests start from a clean slate. */
	public static void clear() {
		items.clear();
	}
}
