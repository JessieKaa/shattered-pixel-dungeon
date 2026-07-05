package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.utils.JsonValue;

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

	public final String id;
	public final String name;
	public final String version;
	public final int spd_version;
	public final String author;
	public final boolean default_enabled;
	public final String description;

	private ModManifest(String id, String name, String version, int spdVersion,
	                    String author, boolean defaultEnabled, String description) {
		this.id = id;
		this.name = name;
		this.version = version;
		this.spd_version = spdVersion;
		this.author = author;
		this.default_enabled = defaultEnabled;
		this.description = description;
	}

	public static ModManifest fromJson(JsonValue v) {
		if (v == null) {
			throw new IllegalArgumentException("manifest json is null");
		}
		// Required fields: libgdx getString(name)/getInt(name) throw IllegalArgumentException
		// ("Named value not found: ...") when the key is absent, so missing-required is handled.
		String id = v.getString("id");
		String name = v.getString("name");
		String version = v.getString("version");
		int spdVersion = v.getInt("spd_version");

		if (id == null || !ID_PATTERN.matcher(id).matches()) {
			throw new IllegalArgumentException("invalid mod id: " + id);
		}
		if (spdVersion <= 0) {
			throw new IllegalArgumentException("spd_version must be > 0, got " + spdVersion);
		}

		String author = v.getString("author", "");
		boolean defaultEnabled = v.getBoolean("default_enabled", false);
		String description = v.getString("description", "");

		return new ModManifest(id, name, version, spdVersion, author, defaultEnabled, description);
	}

	@Override
	public String toString() {
		return "ModManifest{id=" + id + ", version=" + version + ", spd_version=" + spd_version + "}";
	}
}
