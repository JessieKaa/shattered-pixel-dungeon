package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.watabou.utils.Random;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks a Lua-defined item id uniformly at random from {@link LuaItemRegistry}.
 * Used by {@link com.shatteredpixel.shatteredpixeldungeon.items.Generator} when
 * the {@code LUA_ITEM} category is rolled. Returns {@code null} if no Lua items
 * are registered, in which case {@code Generator} falls back to a vanilla weapon
 * (the {@code LUA_ITEM} category never spawns in normal play — firstProb/secondProb
 * are 0 — so this pool is only reached via explicit {@code Generator.random(LUA_ITEM)}
 * or a future opt-in toggle).
 *
 * <p>M6e balance #5: {@link #random()} skips {@code type=material} ids so the
 * generic LUA_ITEM roll (a weapon-shaped drop context) never emits a crafting
 * material. Materials and weapons no longer mix in the same pool. Callers that
 * specifically want a material use {@link #randomMaterial()}.
 */
public final class LuaItemPool {

	private LuaItemPool() { }

	/**
	 * Weapons-only default. Materials are excluded so {@code Generator.random(LUA_ITEM)}
	 * stays weapon-shaped (M6e balance #5). Returns {@code null} if no weapon ids
	 * are registered.
	 */
	public static Item random() {
		return random(false);
	}

	/**
	 * @param material true → pick only from material-typed ids (excludes weapons).
	 *                 false → pick only from weapon ids (the default path).
	 */
	public static Item random(boolean material) {
		int n = LuaItemRegistry.size();
		if (n <= 0) return null;
		List<String> pool = new ArrayList<>(n);
		for (String id : LuaItemRegistry.ids()) {
			if (LuaItemRegistry.isMaterial(id) == material) pool.add(id);
		}
		if (pool.isEmpty()) return null;
		String id = Random.oneOf(pool.toArray(new String[0]));
		return LuaItemRegistry.createItem(id);
	}

	/** Material-only pick for crafting-component contexts. {@code null} if none registered. */
	public static Item randomMaterial() {
		return random(true);
	}
}
