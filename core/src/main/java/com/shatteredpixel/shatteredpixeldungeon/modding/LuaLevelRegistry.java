package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.files.FileHandle;
import org.luaj.vm2.LuaTable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
 *
 * <p>M12d: each {@link Entry} also carries the registering mod's discovery
 * {@link ModManifest.Origin origin} + {@link FileHandle baseDir} + an optional explicit
 * path, so {@link LuaLevelService#enterLevel} can resolve an <em>external</em> mod's level
 * json from {@code baseDir.child(...)} (writable {@code mods_user/} FS) instead of the
 * hardcoded classpath {@code mods/levels/} dir. These three fields are runtime-only —
 * they are NOT persisted into {@link com.watabou.utils.Bundle}: the bundle restore path
 * (R3) only round-trips the id→table mapping, and origin/baseDir/path are recomputed when
 * mods re-run {@code register_level} on the next launch.
 */
public final class LuaLevelRegistry {

	/**
	 * A registered level: its Lua table plus the runtime load metadata captured from the
	 * mod context that registered it (M12d). {@code origin}/{@code baseDir}/{@code path}
	 * are null when the level was registered outside any mod context (e.g. global
	 * {@code init.lua}), in which case {@link LuaLevelService#enterLevel} falls back to
	 * the builtin classpath path.
	 */
	public static final class Entry {
		public final LuaTable table;
		public final ModManifest.Origin origin;
		public final FileHandle baseDir;
		public final String path;

		Entry(LuaTable table, ModManifest.Origin origin, FileHandle baseDir, String path) {
			this.table = table;
			this.origin = origin;
			this.baseDir = baseDir;
			this.path = path;
		}
	}

	private static final Map<String, Entry> levels = new HashMap<>();

	private LuaLevelRegistry() { }

	/**
	 * Register a level with its mod load context (M12d). The {@code origin}/{@code baseDir}
	 * pair drives {@link LuaLevelService#enterLevel}'s external-vs-classpath branch;
	 * {@code path} (when non-null) overrides the per-origin default path.
	 */
	public static void register(String id, LuaTable table, ModManifest.Origin origin,
	                            FileHandle baseDir, String path) {
		levels.put(id, new Entry(table, origin, baseDir, path));
	}

	/** Legacy/builtin register: no mod context, so enterLevel falls back to classpath fromAsset. */
	public static void register(String id, LuaTable table) {
		register(id, table, null, null, null);
	}

	/** Full entry (table + origin/baseDir/path) for the load-time branching decision. */
	public static Entry get(String id) {
		return levels.get(id);
	}

	public static LuaTable getTable(String id) {
		Entry e = levels.get(id);
		return e == null ? null : e.table;
	}

	public static Set<String> ids() {
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
