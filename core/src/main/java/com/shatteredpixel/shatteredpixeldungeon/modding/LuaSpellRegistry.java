package com.shatteredpixel.shatteredpixeldungeon.modding;

import org.luaj.vm2.LuaTable;

import com.watabou.utils.Random;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Holds Lua-defined consumable spell tables keyed by id. Mirrors
 * {@link LuaItemRegistry}: tables are stored as-is so {@link LuaSpell} can
 * re-hydrate from them after a save/load cycle (the Lua table is the single
 * source of truth for name/desc/image/onUse, which the default Item bundle
 * does not persist).
 */
public final class LuaSpellRegistry {

	private static final Map<String, LuaTable> spells = new HashMap<>();

	// M7d: flipped true the first time a spell with useMode="mana" registers.
	// StatusPane gates the MP indicator on this so vanilla runs (no mods → empty
	// registry) never show a mana UI (C3: vanilla zero pollution, round-2 fix).
	private static boolean hasManaSpell = false;

	private LuaSpellRegistry() { }

	public static void register(String id, LuaTable table) {
		spells.put(id, table);
		if ("mana".equals(table.get("useMode").optjstring("consumable"))) {
			hasManaSpell = true;
		}
	}

	/** True iff some registered spell uses mana (useMode="mana"). Drives StatusPane MP visibility. */
	public static boolean hasManaSpell() {
		return hasManaSpell;
	}

	public static LuaTable getTable(String id) {
		return spells.get(id);
	}

	/** All registered ids. */
	public static Set<String> ids() {
		return Collections.unmodifiableSet(spells.keySet());
	}

	/** Uniform random registered id; null if the registry is empty. */
	public static String randomId() {
		if (spells.isEmpty()) return null;
		return Random.oneOf(spells.keySet().toArray(new String[0]));
	}

	public static LuaSpell create(String id) {
		LuaTable tbl = spells.get(id);
		if (tbl == null) return null;
		return new LuaSpell(tbl);
	}

	public static boolean contains(String id) {
		return spells.containsKey(id);
	}

	/** Number of registered spells. Used by LuaEngine to warn on empty scans and by tests. */
	public static int size() {
		return spells.size();
	}

	/** Test helper — clears registered spells so unit tests start from a clean slate. */
	public static void clear() {
		spells.clear();
		hasManaSpell = false;
	}
}
