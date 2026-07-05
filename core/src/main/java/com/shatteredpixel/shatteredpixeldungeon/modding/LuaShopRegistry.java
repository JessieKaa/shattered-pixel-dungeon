package com.shatteredpixel.shatteredpixeldungeon.modding;

import org.luaj.vm2.LuaTable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Holds Lua-defined shop tables keyed by id. M4c 1:1 analogue of
 * {@link LuaNpcRegistry}: tables are stored as-is so {@link LuaShopNpc} can
 * re-hydrate from them after a save/load cycle (the Lua table is the single
 * source of truth for shop name/sprite + the items pool).
 *
 * <p>M4c scope: shop NPCs ({@code LuaShopNpc extends LuaNpc}). They inherit the
 * RatKing invincibility overrides from {@link LuaNpc}, so they are SafeZone-safe
 * (never fight back, never die, never pick up buffs). Interacting opens a custom
 * purchase window ({@link com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions})
 * driven entirely by the item pool carried in the Lua table.
 *
 * <p>Shops registered here are <b>not</b> part of the vanilla spawn pool. A Lua
 * shop only enters a level when a {@code lua_shop:<id>} entry in a
 * {@link DataDrivenLevel} mob spec instantiates it via {@link #create(String)}
 * during {@code createMobs}. {@code Level.createMob}/{@code MobSpawner} are
 * untouched (C3/C4).
 */
public final class LuaShopRegistry {

    private static final Map<String, LuaTable> shops = new HashMap<>();

    private LuaShopRegistry() { }

    public static void register(String id, LuaTable table) {
        shops.put(id, table);
    }

    public static LuaTable getTable(String id) {
        return shops.get(id);
    }

    /** All registered ids. */
    public static Set<String> ids() {
        return Collections.unmodifiableSet(shops.keySet());
    }

    public static LuaShopNpc create(String id) {
        LuaTable tbl = shops.get(id);
        if (tbl == null) return null;
        return new LuaShopNpc(tbl);
    }

    public static boolean contains(String id) {
        return shops.containsKey(id);
    }

    /** Number of registered shops. Used by tests. */
    public static int size() {
        return shops.size();
    }

    /** Test helper — clears registered shops so unit tests start from a clean slate. */
    public static void clear() {
        shops.clear();
    }
}
