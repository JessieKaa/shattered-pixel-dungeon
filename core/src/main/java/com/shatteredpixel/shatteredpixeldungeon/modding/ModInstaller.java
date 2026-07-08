package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.watabou.noosa.Game;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Platform-neutral mod-zip installer (M12b). Unzips a chosen {@code .zip} into a staging dir under
 * {@code mods_user/}, validates the manifest (reusing {@link ModManifest}/{@link ModScanner}
 * semantics: id regex + type strictness via {@code fromJson}, plus the {@code spd_version} gate),
 * and promotes the staged mod to {@code mods_user/<id>/}. On any failure the staging dir is
 * deleted and {@link ModImporter.ImportCallback#onError} is called with a stable error code; the
 * callback is invoked exactly once.
 *
 * <p>Design (docs/PLAN-m12b-desktop-zip-import.md):
 * <ul>
 *   <li>Pure {@code core}: only {@link FileHandle}, {@link java.util.zip}, {@link JsonReader},
 *       {@link Game}. No AWT / SAF / platform imports &mdash; the picker lives in platform modules.</li>
 *   <li>Zip-bomb defence lifted from {@code SaveSlotIO}: per-entry path-traversal check + capped
 *       entry count and total uncompressed size.</li>
 *   <li><b>Every</b> entry (file or directory) is validated and counted <em>before</em> any
 *       filesystem write, so a malicious directory entry like {@code ../evil/} cannot escape the
 *       staging dir either.</li>
 *   <li>Directory entries are honoured (mods are nested: {@code scripts/items/*.lua}); staging
 *       layout mirrors the zip. The mod root is the zip root (if it directly holds
 *       {@code mod.json}) or the single top-level dir that does.</li>
 *   <li>The mod {@code id} from {@code mod.json} is authoritative: the target dir is always
 *       {@code mods_user/<id>}, regardless of the zip's top-level dir name. So a zip whose top
 *       dir differs from the declared id installs cleanly (renamed to {@code <id>}).</li>
 *   <li>Restart-to-load contract (M12a) is preserved: import only drops files; no hot reload.</li>
 * </ul>
 *
 * <p>Error codes (install): {@code io_error}, {@code invalid_zip}, {@code too_many_entries},
 * {@code zip_too_large}, {@code bad_manifest}, {@code version_mismatch}, {@code already_exists}.
 * Error codes (remove): {@code not_found}, {@code not_external}, {@code io_error}.
 */
public final class ModInstaller {

	static final int MAX_ENTRY_COUNT = 256;                 // mods carry many small files (sprites+scripts)
	static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;   // 64 MB uncompressed cap (zip-bomb guard)
	static final int BUFFER_SIZE = 8192;
	static final String STAGING_PREFIX = ".staging-";
	static final String EXTERNAL_MODS_DIR = "mods_user/";
	static final String MANIFEST_NAME = "mod.json";

	private ModInstaller() {}

	/**
	 * Runtime entry: resolve {@code mods_user/} via {@link Gdx.Files#local(String)} and install.
	 * The callback is invoked exactly once, on the caller's thread &mdash; platform pickers should
	 * wrap it to hop back to the libgdx render thread.
	 */
	public static void installFromStream(InputStream in, ModImporter.ImportCallback cb) {
		FileHandle root;
		try {
			root = Gdx.files.local(EXTERNAL_MODS_DIR);
			root.mkdirs();
		} catch (Throwable t) {
			cb.onError("io_error");
			return;
		}
		installInto(root, in, cb);
	}

	/**
	 * Test seam + shared core: install against an injected {@code mods_user/} root so headless
	 * tests do not depend on a live {@link Gdx.Files#local} mount (mirrors
	 * {@code LuaEngineExternalLoadTest}'s {@code new FileHandle(tmpDir)} pattern). Package-private.
	 */
	static void installInto(FileHandle externalRoot, InputStream in, ModImporter.ImportCallback cb) {
		if (externalRoot == null || in == null || cb == null) {
			if (cb != null) cb.onError("io_error");
			return;
		}
		try {
			externalRoot.mkdirs();
		} catch (Throwable t) {
			cb.onError("io_error");
			return;
		}

		FileHandle staging = externalRoot.child(STAGING_PREFIX + UUID.randomUUID());
		try {
			staging.mkdirs();
			unzipSafely(staging, in);
			FileHandle contentRoot = resolveModRoot(staging);
			ModManifest mf = readManifest(contentRoot);
			promote(contentRoot, externalRoot, mf, staging);
			cb.onSuccess(mf.id);
		} catch (InstallException e) {
			cleanup(staging);
			cb.onError(e.code);
		} catch (Throwable t) {
			cleanup(staging);
			cb.onError("io_error");
		}
	}

	// ---- remove (M13a) ------------------------------------------------------

	/**
	 * Uninstall an external mod by deleting its {@code mods_user/<id>/} directory (M13a, the
	 * reverse of {@link #installFromStream}). Resolves the mod through {@link ModRegistry} so the
	 * {@link ModManifest#origin} guard is authoritative: only {@link ModManifest.Origin#EXTERNAL}
	 * mods are deletable. Builtin mods (classpath assets) and unknown ids are refused &mdash; this
	 * origin guard is the safety core that prevents deleting game assets. The deletion target is
	 * always the mod's own {@link ModManifest#baseDir} (= {@code mods_user/<id>/}), never the
	 * {@code mods_user/} root, so a corrupt or malicious id cannot widen the blast radius.
	 *
	 * <p>Registration is irreversible at the Lua layer (registered items/spells survive until
	 * restart), so callers must communicate the restart-to-apply contract (mirrors the M12 import
	 * contract). The callback is invoked exactly once, on the caller's thread.
	 *
	 * <p>Error codes: {@code not_found} (id not in registry), {@code not_external} (builtin / null
	 * origin), {@code io_error} (delete failed). Reuses {@link ModImporter.ImportCallback};
	 * {@code onCancel} is never called (no cancellation semantics).
	 */
	public static void removeMod(String id, ModImporter.ImportCallback cb) {
		if (cb == null) return;
		ModManifest m = ModRegistry.get(id);
		if (m == null) {
			cb.onError("not_found");
			return;
		}
		// Origin guard: only EXTERNAL mods live under the writable mods_user/ root. BUILTIN mods
		// resolve to classpath/internal handles (game assets) and must never be deleted. A null
		// origin (un-scanned manifest) also fails here — fail-safe rather than guess.
		if (m.origin != ModManifest.Origin.EXTERNAL) {
			cb.onError("not_external");
			return;
		}
		try {
			if (!m.baseDir.deleteDirectory()) {
				cb.onError("io_error");
				return;
			}
		} catch (Throwable t) {
			cb.onError("io_error");
			return;
		}
		cb.onSuccess(id);
	}

	// ---- unzip ---------------------------------------------------------------

	/**
	 * Unzip every entry of {@code in} into {@code staging}, enforcing path safety + caps.
	 * <p>Per-entry order (invariant: <b>no FS write before validation</b>): get name →
	 * {@link #isSafeEntryName} → count check → only then mkdir (directory) or write (file).
	 * @throws InstallException on any violation; caller cleans up staging.
	 */
	private static void unzipSafely(FileHandle staging, InputStream in) throws InstallException {
		int count = 0;
		long totalBytes = 0;
		byte[] buf = new byte[BUFFER_SIZE];
		try (ZipInputStream zis = new ZipInputStream(in)) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				String name = entry.getName();
				if (!isSafeEntryName(name)) {
					throw new InstallException("invalid_zip");
				}
				if (++count > MAX_ENTRY_COUNT) {
					throw new InstallException("too_many_entries");
				}
				FileHandle out = staging.child(name);
				if (entry.isDirectory()) {
					out.mkdirs();
					zis.closeEntry();
					continue;
				}
				FileHandle parent = out.parent();
				if (parent != null && !parent.exists()) parent.mkdirs();
				OutputStream os = out.write(false);
				try {
					int n;
					while ((n = zis.read(buf)) > 0) {
						// Cap BEFORE writing so the staging file never exceeds MAX_TOTAL_BYTES
						// (checking after would let a buffer-sized chunk slip past the cap first).
						if (totalBytes + n > MAX_TOTAL_BYTES) {
							throw new InstallException("zip_too_large");
						}
						os.write(buf, 0, n);
						totalBytes += n;
					}
				} finally {
					os.close();
				}
				zis.closeEntry();
			}
		} catch (InstallException e) {
			throw e;
		} catch (Throwable t) {
			throw new InstallException("invalid_zip");
		}
	}

	/**
	 * Path-traversal guard for zip entries (adapted from {@code SaveSlotIO.isSafeEntryName}).
	 * Unlike the save-slot variant (flat, charset-locked) this allows nested forward-slash paths so
	 * mod trees like {@code scripts/items/foo.lua} survive, but still rejects absolute paths,
	 * drive-letter prefixes, backslashes, and {@code ..} segments. Applied to every entry
	 * (including directories).
	 */
	static boolean isSafeEntryName(String name) {
		if (name == null || name.isEmpty()) return false;
		if (name.charAt(0) == '/') return false;                          // absolute unix path
		if (name.length() >= 2 && name.charAt(1) == ':') return false;   // drive letter (C:\, C:/)
		if (name.indexOf('\\') >= 0) return false;                        // backslash
		for (String seg : name.split("/")) {
			if ("..".equals(seg) || ".".equals(seg)) return false;
		}
		return true;
	}

	// ---- manifest root resolution --------------------------------------------

	/**
	 * Resolve the directory holding {@code mod.json}: the zip root, or the single top-level subdir
	 * if the zip was packed that way. @throws InstallException("bad_manifest") otherwise (no
	 * manifest anywhere, or two top-level dirs each carrying one — ambiguous).
	 */
	private static FileHandle resolveModRoot(FileHandle staging) throws InstallException {
		if (manifestExists(staging)) return staging;
		FileHandle dirWithManifest = null;
		for (FileHandle c : staging.list()) {
			if (c.isDirectory() && manifestExists(c)) {
				if (dirWithManifest != null) {
					throw new InstallException("bad_manifest");   // two candidate roots — ambiguous
				}
				dirWithManifest = c;
			}
		}
		if (dirWithManifest != null) return dirWithManifest;
		throw new InstallException("bad_manifest");
	}

	private static boolean manifestExists(FileHandle dir) {
		FileHandle mf = dir.child(MANIFEST_NAME);
		return mf.exists() && !mf.isDirectory();
	}

	private static ModManifest readManifest(FileHandle root) throws InstallException {
		FileHandle mf = root.child(MANIFEST_NAME);
		try {
			JsonValue v = new JsonReader().parse(mf.readString("UTF-8"));
			ModManifest parsed = ModManifest.fromJson(v);   // throws on bad id/type
			if (parsed.spd_version != Game.versionCode) {
				throw new InstallException("version_mismatch");
			}
			return parsed;
		} catch (InstallException e) {
			throw e;
		} catch (Throwable t) {
			throw new InstallException("bad_manifest");
		}
	}

	// ---- promote -------------------------------------------------------------

	private static void promote(FileHandle contentRoot, FileHandle externalRoot,
	                            ModManifest mf, FileHandle staging) throws InstallException {
		FileHandle target = externalRoot.child(mf.id);
		if (target.exists()) {
			throw new InstallException("already_exists");   // never overwrite an existing mod
		}
		try {
			contentRoot.moveTo(target);   // same Local type → rename (atomic-ish); else copyTo+delete
		} catch (Throwable t) {
			throw new InstallException("io_error");
		}
		// contentRoot == staging: it was renamed to target, so this is a no-op.
		// contentRoot == staging/<topdir>: staging still exists minus that subdir; drop it.
		cleanup(staging);
	}

	private static void cleanup(FileHandle staging) {
		if (staging != null && staging.exists()) {
			try { staging.deleteDirectory(); } catch (Throwable ignored) {}
		}
	}

	/** Internal control-flow exception carrying a stable error code; never escapes the public API. */
	static final class InstallException extends Exception {
		final String code;
		InstallException(String code) { super(code); this.code = code; }
	}
}
