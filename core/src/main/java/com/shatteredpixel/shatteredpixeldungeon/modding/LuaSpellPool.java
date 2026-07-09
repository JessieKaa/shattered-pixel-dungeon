package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.watabou.utils.Random;

/**
 * Picks a Lua-defined spell id uniformly at random from {@link LuaSpellRegistry}
 * and creates a fresh {@link LuaSpell}. Used by {@link Generator} when the
 * {@code LUA_SPELL} category is rolled.
 *
 * <p>M15c: firstProb/secondProb for {@code LUA_SPELL} are 0 by default, so the
 * standard drop deck never selects this category unless a mod opts in via
 * {@code mod.json balance.lua_spell_drop_prob}. Returns {@code null} if no Lua
 * spells are registered, in which case {@code Generator} falls back to a vanilla
 * scroll (the category stays consumable-shaped).
 */
public final class LuaSpellPool {

    private LuaSpellPool() { }

    /** Uniform random from {@link LuaSpellRegistry#ids()}; null if empty. */
    public static Item random() {
        String id = LuaSpellRegistry.randomId();
        if (id == null) return null;
        return LuaSpellRegistry.create(id);
    }
}
