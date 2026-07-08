package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Immutable metadata for a single mod, parsed from {@code assets/mods/<id>/mod.json}. This is
 * the M5a metadata layer: it describes a mod (id/name/version/compat) but does not load or
 * execute any Lua content. Lua wiring is M5b; script repackaging is M5c.
 *
 * <p>{@link #fromJson(JsonValue)} throws {@link IllegalArgumentException} for any malformed
 * input (missing required field, illegal id, non-positive spd_version); the caller ({@link
 * ModScanner}) catches and skips the offending mod so one bad manifest cannot crash startup.
 */
public final class ModManifest {

	private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9_]+$");

	/**
	 * Where a mod was discovered (M12a). Determines how {@link LuaEngine} loads its scripts:
	 * builtin mods are resolved through the classpath double-channel; external mods are resolved
	 * purely through their {@link #baseDir} {@link FileHandle}.
	 */
	public enum Origin { BUILTIN, EXTERNAL }

	public final String id;
	public final String name;
	public final String version;
	public final int spd_version;
	public final String author;
	public final boolean default_enabled;
	public final String description;
	/**
	 * Optional path (relative to the mod directory) of an entry script loaded for enabled mods by
	 * {@link LuaEngine}. Null/empty for pure-manifest mods. Validated by {@link #validateEntryPath}
	 * to be relative, backslash-free, traversal-free, and {@code .lua}-suffixed so one enabled mod
	 * cannot load another mod's scripts or arbitrary internal assets.
	 */
	public final String entry;

	/**
	 * Optional {@code balance} overrides declared in {@code mod.json} (M9c). Keys
	 * are the canonical {@link BalanceConfig} names (e.g. {@code mana_regen_delay},
	 * {@code shield_decay_per_turn}); values are numbers. Empty (never null) when
	 * the manifest has no {@code balance} block. Merged into {@link BalanceConfig}
	 * by {@link ModRegistry} for enabled mods at scan/enable time.
	 */
	public final Map<String, Double> balance;

	/**
	 * Where the mod was discovered (M12a). {@link Origin#BUILTIN} = packaged in
	 * {@code assets/mods/} (classpath/internal); {@link Origin#EXTERNAL} = dropped into the
	 * writable {@code mods_user/} dir. Set by {@link ModScanner} after {@link #fromJson}; null on
	 * a freshly-parsed manifest that the scanner has not yet annotated. Runtime-only — never
	 * serialized (a {@link ModManifest} is not Bundle-persisted; it is reparsed from {@code mod.json}
	 * each launch).
	 */
	public Origin origin;

	/**
	 * This mod's own directory as a {@link FileHandle} (M12a). For builtin mods this is the
	 * {@code mods/<id>} classpath/internal handle; for external mods it is the
	 * {@code mods_user/<id>} local handle. {@link LuaEngine}'s loaders use it to locate scripts
	 * without a hardcoded {@code mods/} prefix. Runtime-only — never serialized (same reason as
	 * {@link #origin}). Set by {@link ModScanner} via {@link #setRuntimeMeta}.
	 */
	public FileHandle baseDir;

	private ModManifest(String id, String name, String version, int spdVersion,
	                    String author, boolean defaultEnabled, String description, String entry,
	                    Map<String, Double> balance) {
		this.id = id;
		this.name = name;
		this.version = version;
		this.spd_version = spdVersion;
		this.author = author;
		this.default_enabled = defaultEnabled;
		this.description = description;
		this.entry = entry;
		this.balance = balance;
	}

	public static ModManifest fromJson(JsonValue v) {
		if (v == null) {
			throw new IllegalArgumentException("manifest json is null");
		}
		// Type-strict extraction: libgdx getString/getInt coerce wrong JSON types (e.g. numeric
		// id -> string, string spd_version -> int), so validate via isString/isLong/isBoolean and
		// reject type mismatches rather than silently coercing.
		String id = requireString(v, "id");
		String name = requireString(v, "name");
		String version = requireString(v, "version");

		JsonValue spdNode = v.get("spd_version");
		if (spdNode == null) {
			throw new IllegalArgumentException("Named value not found: spd_version");
		}
		if (!spdNode.isLong()) {
			throw new IllegalArgumentException("spd_version must be an integer, got " + typeDesc(spdNode));
		}
		// asInt() silently narrows out-of-range longs via l2i, so a value like 2^32 + versionCode
		// would wrap to versionCode and bypass the version gate. Range-check the long first.
		long spdRaw = spdNode.asLong();
		if (spdRaw <= 0 || spdRaw > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(
					"spd_version must be in [1, " + Integer.MAX_VALUE + "], got " + spdRaw);
		}
		int spdVersion = (int) spdRaw;

		if (!ID_PATTERN.matcher(id).matches()) {
			throw new IllegalArgumentException("invalid mod id: " + id);
		}

		String author = optionalString(v, "author", "");
		boolean defaultEnabled = optionalBoolean(v, "default_enabled", false);
		String description = optionalString(v, "description", "");
		String entry = optionalString(v, "entry", null);
		validateEntryPath(entry);

		Map<String, Double> balance = parseBalance(v.get("balance"));

		return new ModManifest(id, name, version, spdVersion, author, defaultEnabled, description, entry, balance);
	}

	/**
	 * Annotate a freshly-parsed manifest with its discovery origin + base directory (M12a). Called
	 * by {@link ModScanner} once per admitted mod, immediately after {@link #fromJson}. These are
	 * runtime-only fields — they carry no {@code mod.json} representation and are never serialized.
	 */
	public void setRuntimeMeta(Origin origin, FileHandle baseDir) {
		this.origin = origin;
		this.baseDir = baseDir;
	}

	/**
	 * Parse the optional {@code balance} object. Null/absent → empty map (mod
	 * has no balance overrides). Non-object → IllegalArgumentException (strict,
	 * matches the rest of fromJson). Each value must be a number; keys are
	 * preserved as-is (canonicalization is {@link BalanceConfig}'s job).
	 */
	private static Map<String, Double> parseBalance(JsonValue node) {
		if (node == null) return Collections.emptyMap();
		if (!node.isObject()) {
			throw new IllegalArgumentException("balance must be an object, got " + typeDesc(node));
		}
		Map<String, Double> map = new LinkedHashMap<>();
		for (JsonValue child = node.child; child != null; child = child.next) {
			if (!child.isLong() && !child.isDouble()) {
				throw new IllegalArgumentException(
						"balance." + child.name + " must be a number, got " + typeDesc(child));
			}
			map.put(child.name, child.asDouble());
		}
		return Collections.unmodifiableMap(map);
	}

	private static String requireString(JsonValue v, String key) {
		JsonValue node = v.get(key);
		if (node == null) {
			throw new IllegalArgumentException("Named value not found: " + key);
		}
		if (!node.isString()) {
			throw new IllegalArgumentException(key + " must be a string, got " + typeDesc(node));
		}
		return node.asString();
	}

	private static String optionalString(JsonValue v, String key, String def) {
		JsonValue node = v.get(key);
		if (node == null) return def;
		if (!node.isString()) {
			throw new IllegalArgumentException(key + " must be a string, got " + typeDesc(node));
		}
		return node.asString();
	}

	private static boolean optionalBoolean(JsonValue v, String key, boolean def) {
		JsonValue node = v.get(key);
		if (node == null) return def;
		if (!node.isBoolean()) {
			throw new IllegalArgumentException(key + " must be a boolean, got " + typeDesc(node));
		}
		return node.asBoolean();
	}

	private static String typeDesc(JsonValue node) {
		return node.type().name();
	}

	/**
	 * Enforce that {@code entry} (if declared) is a safe relative {@code .lua} path inside the mod
	 * directory. Rejects absolute paths, backslashes, {@code ..} traversal, and non-{@code .lua}
	 * suffixes so an enabled mod cannot reach another mod's disabled entry or other internal assets
	 * via {@code mods/<id>/<entry>}. Null/empty is allowed (pure-manifest mod, no entry script).
	 */
	private static void validateEntryPath(String entry) {
		if (entry == null || entry.isEmpty()) return;
		if (!entry.endsWith(".lua")) {
			throw new IllegalArgumentException("entry must end with .lua, got: " + entry);
		}
		if (entry.startsWith("/")) {
			throw new IllegalArgumentException("entry must be relative (no leading /): " + entry);
		}
		if (entry.indexOf('\\') >= 0) {
			throw new IllegalArgumentException("entry must not contain backslashes: " + entry);
		}
		for (String seg : entry.split("/")) {
			if ("..".equals(seg)) {
				throw new IllegalArgumentException("entry must not contain '..' segments: " + entry);
			}
		}
	}

	@Override
	public String toString() {
		return "ModManifest{id=" + id + ", version=" + version + ", spd_version=" + spd_version + "}";
	}
}
