package com.shatteredpixel.shatteredpixeldungeon.modding;

import org.luaj.vm2.LuaTable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Holds Lua-defined level tables keyed by id, 1:1 with the M3 registries
 * ({@link LuaItemRegistry}/{@link LuaMobRegistry}). A level entry maps an id to either an
 * asset JSON path or an inline definition; {@link DataDrivenLevel} re-hydrates from the
 * entry on bundle restore (R3) and {@link LuaLevelService} looks the id up to find the
 * source on {@code enterLevel}.
 *
 * <p>M4a scope: production currently drives levels from {@code mods/levels/<id>.json}
 * files; the registry is the rendezvous point so {@code register_level} (Lua) and the
 * file loader share one id→source map with the bundle restore path.
 */
public final class LuaLevelRegistry {

	private static final Map<String, LuaTable> levels = new HashMap<>();

	private LuaLevelRegistry() { }

	public static void register(String id, LuaTable table) {
		levels.put(id, table);
	}

	public static LuaTable getTable(String id) {
		return levels.get(id);
	}

	public static java.util.Set<String> ids() {
		return Collections.unmodifiableSet(levels.keySet());
	}

	public static boolean contains(String id) {
		return levels.containsKey(id);
	}

	public static int size() {
		return levels.size();
	}

	/** Test helper — clears registered levels so unit tests start from a clean slate. */
	public static void clear() {
		levels.clear();
	}
}
