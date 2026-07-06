package com.shatteredpixel.shatteredpixeldungeon.modding;

import org.luaj.vm2.LuaTable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Holds Lua-defined buff tables keyed by id. Mirrors {@link LuaSpellRegistry}:
 * the table is the source of truth for name/desc/icon and the lifecycle
 * callbacks ({@code attachTo/act/detach/immunities/onRestore}) that
 * {@link LuaBuff} re-hydrates from after a save/load cycle.
 */
public final class LuaBuffRegistry {

    private static final Map<String, LuaTable> buffs = new HashMap<>();

    private LuaBuffRegistry() { }

    public static void register(String id, LuaTable table) {
        buffs.put(id, table);
    }

    public static LuaTable getTable(String id) {
        return buffs.get(id);
    }

    /** All registered ids. */
    public static Set<String> ids() {
        return Collections.unmodifiableSet(buffs.keySet());
    }

    public static LuaBuff create(String id) {
        LuaTable tbl = buffs.get(id);
        if (tbl == null) return null;
        return new LuaBuff(tbl);
    }

    public static boolean contains(String id) {
        return buffs.containsKey(id);
    }

    /** Number of registered buffs. Used by LuaEngine to warn on empty scans and by tests. */
    public static int size() {
        return buffs.size();
    }

    /** Test helper — clears registered buffs so unit tests start from a clean slate. */
    public static void clear() {
        buffs.clear();
    }
}
