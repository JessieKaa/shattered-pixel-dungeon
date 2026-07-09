package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.watabou.gltextures.SmartTexture;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runtime cache for per-mod standalone sprite files (M16a).
 *
 * <p>Lua items/spells/mobs declare {@code spriteFile = "sprites/items/item_X.png"}.
 * This class resolves the path relative to the owning mod's directory, validates
 * it is traversal-safe, loads it into a {@link SmartTexture}, and caches the result
 * keyed by {@code modId + ":" + path}.
 *
 * <p>The cache is self-managed (not routed through {@link com.watabou.gltextures.TextureCache})
 * because builtin and external mods resolve to different source types, and a
 * single string key must work for both. SmartTextures are constructed lazily
 * (GL upload happens on first bind), so loading is safe in headless/non-GL contexts.
 *
 * <p>Only static image files are supported; animation frames, atlases, and
 * directional sprites are intentionally out of scope for M16a.
 */
public final class ModSpriteCache {

	private ModSpriteCache() { }

	private static final String TAG = "ModSpriteCache";

	private static final Map<String, SmartTexture> cache = new HashMap<>();

	/**
	 * Allowed file extensions for standalone sprite files. Restricted to common
	 * lossless/raster image formats libGDX can load as Pixmap/Texture.
	 */
	private static final String[] ALLOWED_EXTENSIONS = { ".png", ".jpg", ".jpeg", ".webp" };

	/**
	 * Matches a Windows drive-letter prefix (e.g. {@code C:/...}) so an absolute
	 * path that lacks a leading {@code /} or backslash is still rejected. On
	 * Windows, {@code Gdx.files.internal("C:/x")} and
	 * {@code baseDir.child("C:/x")} resolve to an absolute file, escaping the mod
	 * directory; blocking the drive letter closes that traversal vector.
	 */
	private static final Pattern DRIVE_LETTER = Pattern.compile("^[A-Za-z]:");

	/**
	 * Validates a sprite file path is safe to resolve under a mod directory.
	 *
	 * <p>Rejects:
	 * <ul>
	 *   <li>null or empty paths</li>
	 *   <li>absolute paths (leading '/')</li>
	 *   <li>Windows drive-letter paths (e.g. {@code C:/...})</li>
	 *   <li>backslashes</li>
	 *   <li>'..' segments (path traversal)</li>
	 *   <li>non-image extensions</li>
	 * </ul>
	 *
	 * @return the normalized path (forward slashes, no leading slash), or null if invalid.
	 */
	public static String validateSpritePath(String path) {
		if (path == null || path.isEmpty()) {
			return null;
		}
		if (path.startsWith("/")) {
			Gdx.app.error(TAG, "spriteFile must be relative, got absolute: " + path);
			return null;
		}
		if (DRIVE_LETTER.matcher(path).find()) {
			Gdx.app.error(TAG, "spriteFile must be relative, got drive-letter path: " + path);
			return null;
		}
		if (path.indexOf('\\') >= 0) {
			Gdx.app.error(TAG, "spriteFile must use forward slashes: " + path);
			return null;
		}

		for (String seg : path.split("/")) {
			if (seg.isEmpty()) {
				Gdx.app.error(TAG, "spriteFile contains empty segment: " + path);
				return null;
			}
			if ("..".equals(seg)) {
				Gdx.app.error(TAG, "spriteFile must not traverse up: " + path);
				return null;
			}
		}

		String lower = path.toLowerCase(Locale.ROOT);
		boolean ok = false;
		for (String ext : ALLOWED_EXTENSIONS) {
			if (lower.endsWith(ext)) {
				ok = true;
				break;
			}
		}
		if (!ok) {
			Gdx.app.error(TAG, "spriteFile must be an image (.png/.jpg/.jpeg/.webp): " + path);
			return null;
		}

		return path;
	}

	/**
	 * Resolves a sprite file for a mod. Returns null if the path is invalid, the
	 * file is missing, or libGDX cannot load it. Errors are logged but never thrown.
	 */
	public static SmartTexture get(ModManifest mod, String spriteFile) {
		String normalized = validateSpritePath(spriteFile);
		if (normalized == null) return null;
		if (mod == null) {
			Gdx.app.error(TAG, "missing mod manifest for spriteFile: " + spriteFile);
			return null;
		}

		String key = mod.id + ":" + normalized;
		SmartTexture cached = cache.get(key);
		if (cached != null) return cached;

		FileHandle fh = resolveFileHandle(mod, normalized);
		if (fh == null || !fh.exists()) {
			Gdx.app.error(TAG, "spriteFile not found for mod " + mod.id + ": " + normalized);
			return null;
		}

		try {
			Pixmap pixmap = new Pixmap(fh);
			SmartTexture tx = new SmartTexture(pixmap);
			cache.put(key, tx);
			return tx;
		} catch (Exception e) {
			Gdx.app.error(TAG, "failed to load spriteFile for mod " + mod.id + ": " + normalized, e);
			return null;
		}
	}

	/**
	 * Convenience overload that looks up the mod manifest by id. Useful for callers
	 * that only have the owner mod id (e.g. rendering code).
	 */
	public static SmartTexture get(String modId, String spriteFile) {
		if (modId == null) return null;
		return get(ModRegistry.get(modId), spriteFile);
	}

	/**
	 * Reports whether a sprite file would resolve for a mod, <b>without</b>
	 * loading it into a texture. Used at Lua-mob definition time to pick
	 * {@code ModMobSprite} vs a whitelist sprite, so a broken/missing
	 * {@code spriteFile} degrades to the legacy sprite instead of rendering
	 * blank. Safe in headless/non-GL contexts (no Pixmap, no GL upload).
	 */
	public static boolean resolves(String modId, String path) {
		if (modId == null) return false;
		return resolves(ModRegistry.get(modId), path);
	}

	/**
	 * Manifest-based variant of {@link #resolves(String, String)}.
	 */
	public static boolean resolves(ModManifest mod, String path) {
		String normalized = validateSpritePath(path);
		if (normalized == null || mod == null) return false;
		FileHandle fh = resolveFileHandle(mod, normalized);
		return fh != null && fh.exists();
	}

	/**
	 * Re-uploads every cached mod texture after a GL context loss. Mirrors
	 * {@link com.watabou.gltextures.TextureCache#reload()}: each
	 * {@link SmartTexture#reload()} resets its GL handle and re-runs
	 * {@code generate()}, re-uploading the retained {@link SmartTexture#bitmap}.
	 * The engine's own reload path lives in SPD-classes ({@code Game.resize}),
	 * which cannot depend on core, so {@code ShatteredPixelDungeon} invokes this
	 * from its context-loss-detected {@code resize()} override.
	 */
	public static void reload() {
		for (SmartTexture tx : cache.values()) {
			try {
				tx.reload();
			} catch (Exception e) {
				Gdx.app.error(TAG, "failed to reload mod sprite texture", e);
			}
		}
	}

	private static FileHandle resolveFileHandle(ModManifest mod, String path) {
		if (mod.origin == ModManifest.Origin.EXTERNAL && mod.baseDir != null) {
			FileHandle resolved = mod.baseDir.child(path);
			// Defense-in-depth: validateSpritePath already blocks '..', absolute,
			// and drive-letter forms, so child() cannot escape via traversal.
			// Verify the resolved path still lives under baseDir anyway, so a
			// platform quirk in FileHandle.child can't read outside the mod dir.
			String base = mod.baseDir.path();
			if (base != null && !base.isEmpty()
					&& !resolved.path().startsWith(base)) {
				Gdx.app.error(TAG, "spriteFile escapes mod dir " + mod.id + ": " + path);
				return null;
			}
			return resolved;
		}
		return Gdx.files.internal("mods/" + mod.id + "/" + path);
	}

	/** Drops every mod-sprite texture this cache has loaded. Primarily for tests. */
	public static void clear() {
		cache.clear();
	}
}
