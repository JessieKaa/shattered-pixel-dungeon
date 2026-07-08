package com.shatteredpixel.shatteredpixeldungeon.modding;

import org.luaj.vm2.LuaTable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * M10b: holds Lua-defined trap tables keyed by id. {@link LuaTrap} re-hydrates
 * from the table on activate/restore (the table is the single source of truth,
 * same pattern as {@link LuaItemRegistry}).
 *
 * <p>Lua traps are <b>not</b> part of the vanilla trap pool —
 * {@code RegularPainter.paintTraps} is untouched. They enter a level only via
 * {@link LuaLevelService#injectLevelTraps}, which places them at EMPTY cells
 * after the upstream paint pipeline has run (so they never collide with
 * upstream-placed traps, which already flipped those cells to TRAP/SECRET_TRAP).
 */
public final class LuaTrapRegistry {

	private static final Map<String, LuaTable> traps = new HashMap<>();

	private LuaTrapRegistry() { }

	public static void register(String id, LuaTable table) {
		if (id == null || id.isEmpty() || table == null) return;
		traps.put(id, table);
	}

	public static LuaTable getTable(String id) {
		return traps.get(id);
	}

	public static boolean contains(String id) {
		return traps.containsKey(id);
	}

	/** True if any Lua trap is registered — injectLevelTraps early-returns otherwise. */
	public static boolean hasAny() {
		return !traps.isEmpty();
	}

	public static java.util.Set<String> ids() {
		return Collections.unmodifiableSet(traps.keySet());
	}

	public static int size() {
		return traps.size();
	}

	/** Test helper — clears registered traps so unit tests start from a clean slate. */
	public static void clear() {
		traps.clear();
	}
}
