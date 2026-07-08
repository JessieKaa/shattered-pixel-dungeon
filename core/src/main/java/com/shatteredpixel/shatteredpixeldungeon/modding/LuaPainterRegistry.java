package com.shatteredpixel.shatteredpixeldungeon.modding;

import org.luaj.vm2.LuaTable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * M10b: holds Lua-defined painter tables keyed by Room class simple name
 * (e.g. {@code "ShopRoom"}, {@code "StandardRoom"}). SPD {@code Room} has no
 * Remished-style {@code .type} enum, so class identity is the dispatch key.
 *
 * <p>The table is stored as-is so {@link LuaPainterAdapter} can re-read the
 * optional {@code paint}/{@code decorate} callbacks each paint call (the table
 * is the single source of truth, same pattern as {@link LuaItemRegistry}).
 *
 * <p>Painters are a pure overlay: they run <b>after</b> the upstream
 * {@code RegularPainter} pipeline (doors / per-room paint / water / grass /
 * traps / decorate), and {@link LuaPainterAdapter}'s {@code setTile} safety gate
 * structurally prevents them from touching door/water/grass/trap cells. So a
 * registered painter can only add decorative tiles inside a room's interior —
 * it cannot break levelgen connectivity or vanilla layout guarantees.
 */
public final class LuaPainterRegistry {

	private static final Map<String, LuaTable> painters = new HashMap<>();

	private LuaPainterRegistry() { }

	public static void register(String id, LuaTable table) {
		if (id == null || id.isEmpty() || table == null) return;
		painters.put(id, table);
	}

	public static LuaTable getTable(String id) {
		return painters.get(id);
	}

	public static boolean contains(String id) {
		return painters.containsKey(id);
	}

	/** True if any painter is registered — RegularLevel.build uses this to decide whether to wrap. */
	public static boolean hasAny() {
		return !painters.isEmpty();
	}

	public static int size() {
		return painters.size();
	}

	/** Test helper — clears registered painters so unit tests start from a clean slate. */
	public static void clear() {
		painters.clear();
	}
}
