package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.watabou.noosa.Game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans {@code assets/mods/<id>/mod.json} manifests and applies the version gate. This is the
 * M5a discovery layer: it produces {@link ModManifest} list consumed by {@link ModRegistry}.
 *
 * <p>M16c: scanner now returns a {@link ScanResult} that also carries diagnostics for skipped
 * directories (bad manifest, id mismatch, duplicate id, version mismatch, external shadowed by
 * builtin). These diagnostics use a stable key {@code scan:<origin>:<dirname>} so they never
 * collide with legitimate mod ids in the registry's diagnostics map.
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
	private static final String EXTERNAL_MODS_DIR = "mods_user/";

	private ModScanner() {}

	public static ScanResult scan() {
		if (Gdx.files == null) {
			safeLog(TAG, "Gdx.files null, mod scan skipped", null);
			return new ScanResult(Collections.emptyList(), Collections.emptyMap());
		}
		ScanResult builtin = scanChildren(listModDirs(), ModManifest.Origin.BUILTIN);
		ScanResult external = scanExternalResult(externalModsRoot());
		return mergeById(builtin, external);
	}

	/**
	 * Resolve the external mods root as a writable {@link FileHandle} (M12a). Uses
	 * {@link Gdx.Files#local(String)} (Android {@code getFilesDir()} / desktop cwd) so no storage
	 * permission is required. Returns null if libgdx cannot resolve a local root (e.g. misconfigured
	 * headless launch); {@link #scanExternal(FileHandle)} treats null as "no external mods".
	 */
	private static FileHandle externalModsRoot() {
		try {
			return Gdx.files.local(EXTERNAL_MODS_DIR);
		} catch (Exception e) {
			safeLog(TAG, "Gdx.files.local(" + EXTERNAL_MODS_DIR + ") failed, external mods skipped", e);
			return null;
		}
	}

	/**
	 * Scan {@code baseDir} (the external {@code mods_user/} root) for external mods (M12a). Each
	 * admitted manifest is annotated {@link ModManifest.Origin#EXTERNAL} with {@code baseDir} set
	 * to its own {@code mods_user/<id>} directory. Missing/null base dir → empty result.
	 */
	static ScanResult scanExternalResult(FileHandle baseDir) {
		if (baseDir == null || !baseDir.exists()) {
			return new ScanResult(Collections.emptyList(), Collections.emptyMap());
		}
		return scanChildren(baseDir.list(), ModManifest.Origin.EXTERNAL);
	}

	/**
	 * Backwards-compatible seam used by tests that only need manifests. Delegates to
	 * {@link #scanExternalResult(FileHandle)} and drops diagnostics.
	 */
	static List<ModManifest> scanExternal(FileHandle baseDir) {
		return scanExternalResult(baseDir).manifests;
	}

	/**
	 * Merge builtin + external scan results, builtin-wins on id conflict (M12a). Builtin mods are
	 * kept in full; an external mod whose id collides with a builtin is skipped + logged (never
	 * throws) so a stale external copy cannot shadow the packaged version. Distinct external ids
	 * are appended after builtin (scan order: builtin first, then external).
	 */
	static ScanResult mergeById(ScanResult builtin, ScanResult external) {
		List<ModManifest> out = new ArrayList<>(builtin.manifests.size() + (external.manifests == null ? 0 : external.manifests.size()));
		Map<String, ModDiagnostics> diagnostics = new LinkedHashMap<>();
		if (builtin.diagnostics != null) diagnostics.putAll(builtin.diagnostics);
		if (external.diagnostics != null) diagnostics.putAll(external.diagnostics);
		Set<String> seen = new HashSet<>();
		if (builtin.manifests != null) {
			for (ModManifest m : builtin.manifests) {
				out.add(m);
				seen.add(m.id);
			}
		}
		if (external.manifests != null) {
			for (ModManifest m : external.manifests) {
				if (!seen.add(m.id)) {
					safeLog(TAG, "external mod " + m.id + " shadowed by builtin, skip", null);
					diagnostics.computeIfAbsent(scanKey(m.origin, m.id),
							k -> new ModDiagnostics().setDeclaredId(m.id))
							.addWarning("external shadowed by builtin id " + m.id);
					continue;
				}
				out.add(m);
			}
		}
		return new ScanResult(out, diagnostics);
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

	public static ScanResult scanDirResult(FileHandle baseDir) {
		if (baseDir == null || !baseDir.exists()) {
			return new ScanResult(Collections.emptyList(), Collections.emptyMap());
		}
		return scanChildren(baseDir.list(), ModManifest.Origin.BUILTIN);
	}

	/**
	 * Backwards-compatible seam used by tests that only need manifests. Delegates to
	 * {@link #scanDirResult(FileHandle)} and drops diagnostics.
	 */
	public static List<ModManifest> scanDir(FileHandle baseDir) {
		return scanDirResult(baseDir).manifests;
	}

	private static ScanResult scanChildren(FileHandle[] rawChildren, ModManifest.Origin origin) {
		if (rawChildren == null || rawChildren.length == 0) {
			return new ScanResult(Collections.emptyList(), Collections.emptyMap());
		}
		FileHandle[] children = rawChildren.clone();
		Arrays.sort(children, (a, b) -> {
			String an = a != null ? a.name() : "";
			String bn = b != null ? b.name() : "";
			return an.compareTo(bn);
		});

		List<ModManifest> mods = new ArrayList<>();
		Map<String, ModDiagnostics> diagnostics = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();
		for (FileHandle child : children) {
			if (child == null || !child.isDirectory()) continue;
			String dirName = child.name();
			FileHandle manifest = child.child(MANIFEST_NAME);
			if (!manifest.exists() || manifest.isDirectory()) continue;
			try {
				ModManifest m = ModManifest.fromJson(new JsonReader().parse(manifest.readString("UTF-8")));
				if (!m.id.equals(dirName)) {
					safeLog(TAG, "mod dir " + dirName + " declares id " + m.id + ", skip", null);
					diagnostics.computeIfAbsent(scanKey(origin, dirName),
							k -> new ModDiagnostics().setDeclaredId(m.id))
							.addError("id mismatch: declared " + m.id + " vs dir " + dirName);
					continue;
				}
				if (!seen.add(m.id)) {
					safeLog(TAG, "duplicate mod id " + m.id + ", skip", null);
					diagnostics.computeIfAbsent(scanKey(origin, dirName),
							k -> new ModDiagnostics().setDeclaredId(m.id))
							.addError("duplicate id " + m.id + " skipped");
					continue;
				}
				if (m.spd_version != Game.versionCode) {
					safeLog(TAG, "mod " + m.id + " spd_version=" + m.spd_version
							+ " != " + Game.versionCode + ", skip", null);
					diagnostics.computeIfAbsent(scanKey(origin, dirName),
							k -> new ModDiagnostics().setDeclaredId(m.id))
							.addWarning("spd_version " + m.spd_version + " != " + Game.versionCode);
					continue;
				}
				m.setRuntimeMeta(origin, child);
				mods.add(m);
			} catch (Exception e) {
				safeLog(TAG, "mod manifest parse fail: " + child.path(), e);
				diagnostics.computeIfAbsent(scanKey(origin, dirName),
						k -> new ModDiagnostics())
						.addError("manifest parse: " + e.getMessage());
			}
		}
		return new ScanResult(mods, diagnostics);
	}

	private static String scanKey(ModManifest.Origin origin, String dirName) {
		return "scan:" + origin.name() + ":" + dirName;
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

	/**
	 * M16c: scanner result carrying discovered manifests plus diagnostics for directories that
	 * were skipped during scan.
	 */
	public static final class ScanResult {
		public final List<ModManifest> manifests;
		public final Map<String, ModDiagnostics> diagnostics;

		public ScanResult(List<ModManifest> manifests, Map<String, ModDiagnostics> diagnostics) {
			this.manifests = manifests != null ? manifests : Collections.emptyList();
			this.diagnostics = diagnostics != null ? diagnostics : Collections.emptyMap();
		}
	}
}
