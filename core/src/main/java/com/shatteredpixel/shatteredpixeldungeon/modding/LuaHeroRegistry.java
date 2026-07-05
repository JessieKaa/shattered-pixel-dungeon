package com.shatteredpixel.shatteredpixeldungeon.modding;

import org.luaj.vm2.LuaTable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Holds Lua-defined hero classes keyed by id (M3c). Direct analogue of
 * {@link LuaMobRegistry}/{@link LuaAllyRegistry}: {@link LuaHeroClass} is the
 * Java-side metadata captured at registration time, and the original Lua table
 * is also kept (via {@link #getTable(String)}) so future hooks can re-read
 * callback fields if M3c+ extends Lua heroes with behaviour scripts.
 *
 * <p><b>Not</b> part of the vanilla {@code HeroClass.values()} roster —
 * {@code HeroSelectScene} renders Lua heroes as extra buttons (D4) and
 * {@code Dungeon.init} routes to {@code Hero.initLuaHero} via
 * {@link LuaHeroService} when one is selected. The {@link HeroClass} enum stays
 * untouched (C3/C4).
 */
public final class LuaHeroRegistry {

	private static final Map<String, LuaHeroClass> heroes = new HashMap<>();
	private static final Map<String, LuaTable> tables = new HashMap<>();

	private LuaHeroRegistry() { }

	public static void register(LuaHeroClass hero, LuaTable table) {
		heroes.put(hero.id(), hero);
		tables.put(hero.id(), table);
	}

	public static LuaHeroClass get(String id) {
		return heroes.get(id);
	}

	public static LuaTable getTable(String id) {
		return tables.get(id);
	}

	/** All registered hero definitions, in insertion order. Used by HeroSelectScene to render buttons. */
	public static Collection<LuaHeroClass> all() {
		return Collections.unmodifiableCollection(heroes.values());
	}

	public static boolean contains(String id) {
		return heroes.containsKey(id);
	}

	public static int size() {
		return heroes.size();
	}

	/** Test helper — clears registered heroes so unit tests start from a clean slate. */
	public static void clear() {
		heroes.clear();
		tables.clear();
	}
}
