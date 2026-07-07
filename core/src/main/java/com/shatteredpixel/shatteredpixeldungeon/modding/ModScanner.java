package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.watabou.noosa.Game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans {@code assets/mods/<id>/mod.json} manifests and applies the version gate. This is the
 * M5a discovery layer: it produces {@link ModManifest} list consumed by {@link ModRegistry}.
 *
 * <p>Design constraints (see docs/PLAN-modding-m5a-mod-manifest.md):
 * <ul>
 *   <li><b>Never crash startup</b>: {@code Gdx.files}/{@code Gdx.app} may be null during early
 *       startup; per-mod parse failures are caught and skipped.</li>
 *   <li><b>Strict version gate</b>: {@code spd_version == Game.versionCode} or skip (MVP, no
 *       range migration).</li>
 *   <li><b>id &harr; directory integrity</b>: a manifest declaring an id that differs from its
 *       parent directory is skipped, which also prevents duplicate-id ambiguity.</li>
 *   <li><b>Test seam</b>: {@link #scanDir(FileHandle)} takes the mods base dir directly so tests
 *       can inject an in-memory filesystem; {@link #scan()} is the runtime entry point.</li>
 * </ul>
 */
public final class ModScanner {

	private static final String TAG = "ModScanner";
	private static final String MANIFEST_NAME = "mod.json";

	private ModScanner() {}

	public static List<ModManifest> scan() {
		if (Gdx.files == null) {
			safeLog(TAG, "Gdx.files null, mod scan skipped", null);
			return Collections.emptyList();
		}
		return scanChildren(listModDirs());
	}

	/**
	 * Two-stage enumeration of {@code mods/} child directories.
	 *
	 * <p>Stage 1 (classpath-as-filesystem): {@code Gdx.files.internal("mods").list()} returns an
	 * empty array on desktop LWJGL3 when {@code mods/} only exists on the classpath (same quirk
	 * {@link LuaEngine}'s script loaders work around). {@code ClassLoader.getResource("mods")}
	 * resolves to a real {@code file:} URL in unpacked desktop runs and tests, so we list the
	 * {@link java.io.File} directly and wrap each child in {@link Gdx#files#absolute(String)}.
	 *
	 * <p>Stage 2 (libgdx fallback): {@code Gdx.files.internal("mods").list()} works on Android
	 * ({@code AssetManager.list}) and packaged jars.
	 */
	private static FileHandle[] listModDirs() {
		try {
			java.net.URL dirUrl = ModScanner.class.getClassLoader().getResource("mods");
			if (dirUrl != null && "file".equals(dirUrl.getProtocol())) {
				java.io.File dirFile = new java.io.File(dirUrl.toURI());
				java.io.File[] dirs = dirFile.listFiles(java.io.File::isDirectory);
				if (dirs != null && dirs.length > 0) {
					FileHandle[] out = new FileHandle[dirs.length];
					for (int i = 0; i < dirs.length; i++) {
						out[i] = Gdx.files.absolute(dirs[i].getAbsolutePath());
					}
					return out;
				}
			}
		} catch (Exception e) {
			safeLog(TAG, "Classpath-FS enumeration of mods failed, falling back", e);
		}
		try {
			FileHandle base = Gdx.files.internal("mods");
			if (base != null && base.exists()) return base.list();
		} catch (Exception e) {
			safeLog(TAG, "Gdx fallback list of mods failed", e);
		}
		return new FileHandle[0];
	}

	public static List<ModManifest> scanDir(FileHandle baseDir) {
		if (baseDir == null || !baseDir.exists()) {
			return Collections.emptyList();
		}
		return scanChildren(baseDir.list());
	}

	private static List<ModManifest> scanChildren(FileHandle[] rawChildren) {
		if (rawChildren == null || rawChildren.length == 0) {
			return Collections.emptyList();
		}
		// File-system list order is not a stable contract; sort for deterministic UI/tests.
		FileHandle[] children = rawChildren.clone();
		Arrays.sort(children, (a, b) -> {
			String an = a != null ? a.name() : "";
			String bn = b != null ? b.name() : "";
			return an.compareTo(bn);
		});

		List<ModManifest> mods = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (FileHandle child : children) {
			if (child == null || !child.isDirectory()) continue;
			FileHandle manifest = child.child(MANIFEST_NAME);
			if (!manifest.exists() || manifest.isDirectory()) continue;
			try {
				ModManifest m = ModManifest.fromJson(new JsonReader().parse(manifest.readString("UTF-8")));
				if (!m.id.equals(child.name())) {
					safeLog(TAG, "mod dir " + child.name() + " declares id " + m.id + ", skip", null);
					continue;
				}
				if (!seen.add(m.id)) {
					safeLog(TAG, "duplicate mod id " + m.id + ", skip", null);
					continue;
				}
				if (m.spd_version != Game.versionCode) {
					safeLog(TAG, "mod " + m.id + " spd_version=" + m.spd_version
							+ " != " + Game.versionCode + ", skip", null);
					continue;
				}
				mods.add(m);
			} catch (Exception e) {
				safeLog(TAG, "mod manifest parse fail: " + child.path(), e);
			}
		}
		return mods;
	}

	// Gdx.app may itself be null in early/headless misconfigured launches; fall back to stderr
	// rather than NPE inside the "never crash startup" guard.
	private static void safeLog(String tag, String msg, Throwable t) {
		if (Gdx.app != null) {
			if (t != null) Gdx.app.error(tag, msg, t);
			else Gdx.app.error(tag, msg);
		} else {
			System.err.println(tag + ": " + msg);
			if (t != null) t.printStackTrace(System.err);
		}
	}
}
