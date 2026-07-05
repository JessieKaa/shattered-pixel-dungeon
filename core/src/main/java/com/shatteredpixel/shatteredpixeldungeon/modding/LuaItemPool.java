package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.watabou.utils.Random;

/**
 * Picks a Lua-defined item id uniformly at random from {@link LuaItemRegistry}.
 * Used by {@link com.shatteredpixel.shatteredpixeldungeon.items.Generator} when
 * the {@code LUA_ITEM} category is rolled. Returns {@code null} if no Lua items
 * are registered, in which case {@code Generator} falls back to a vanilla weapon
 * (the {@code LUA_ITEM} category never spawns in normal play — firstProb/secondProb
 * are 0 — so this pool is only reached via explicit {@code Generator.random(LUA_ITEM)}
 * or a future opt-in toggle).
 */
public final class LuaItemPool {

	private LuaItemPool() { }

	public static LuaItem random() {
		int n = LuaItemRegistry.size();
		if (n <= 0) return null;
		String[] ids = LuaItemRegistry.ids().toArray(new String[0]);
		String id = Random.oneOf(ids);
		return LuaItemRegistry.create(id);
	}
}
