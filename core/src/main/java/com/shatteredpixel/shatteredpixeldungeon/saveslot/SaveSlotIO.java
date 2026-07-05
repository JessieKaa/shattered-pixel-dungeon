/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.saveslot;

import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Fork extension: pure-logic zip packaging for save slots.
 *
 * <p>This class must remain free of any {@code android.*}, {@code org.lwjgl.*},
 * {@code java.awt.*}, or {@code javax.swing.*} imports so that the {@code core}
 * module stays platform-agnostic. libgdx {@link FileHandle} / {@link FileUtils}
 * are allowed because {@code core} already depends on them.</p>
 *
 * <p>Zip format:</p>
 * <ul>
 *   <li>Entries are flat root-level filenames (no directories).</li>
 *   <li>{@code meta.bundle} is written first to let importers early-reject
 *       version mismatches.</li>
 *   <li>Entry order is {@code meta.bundle} first, then alphabetical.</li>
 * </ul>
 *
 * <p>Path traversal defence: only names matching {@code [A-Za-z0-9_.\\-]+}
 * (and not {@code .} / {@code ..}) are accepted. Count and total uncompressed
 * size are capped to mitigate zip bombs.</p>
 */
public final class SaveSlotIO {

	private static final String SLOT_ROOT = "save_slots/";
	private static final String META_FILE = "meta.bundle";
	private static final String STAGING_PREFIX = ".import-";
	private static final String TMP_SUFFIX = ".tmp";
	private static final String BAK_SUFFIX = ".bak";
	/**
	 * Marker file written to a slot dir after a fully-completed import promote.
	 * Its presence in a live slot dir is what tells {@link #cleanupLeftovers()}
	 * that the overwrite committed successfully and the matching {@code .bak}
	 * can be discarded; absence means the promote was interrupted and the
	 * {@code .bak} must be restored.
	 */
	private static final String COMMIT_MARKER = ".import-complete";

	private static final Pattern SAFE_ENTRY_NAME = Pattern.compile("[A-Za-z0-9_.\\-]+");

	private static final int MAX_ENTRY_COUNT = 64;
	private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024; // 64 MB cap
	private static final int BUFFER_SIZE = 16 * 1024;

	private SaveSlotIO() {}

	// ---- export ---------------------------------------------------------------

	/**
	 * Stream the named slot's contents as a zip into {@code out}.
	 * Caller owns {@code out}; this method does not close it.
	 */
	public static void writeSlotToStream(String slotName, OutputStream out) throws IOException {
		String dir = SLOT_ROOT + slotName;
		FileHandle srcDir = FileUtils.getFileHandle(dir);
		if (srcDir == null || !srcDir.isDirectory()) {
			throw new IOException("slot not found: " + slotName);
		}

		ArrayList<String> filenames = new ArrayList<>();
		for (FileHandle f : srcDir.list()) {
			if (!f.isDirectory()) filenames.add(f.name());
		}
		if (filenames.isEmpty()) {
			throw new IOException("slot is empty: " + slotName);
		}

		// Force meta.bundle first; sort the rest alphabetically.
		Collections.sort(filenames);
		if (filenames.remove(META_FILE)) {
			filenames.add(0, META_FILE);
		}

		ZipOutputStream zos = new ZipOutputStream(out);
		byte[] buf = new byte[BUFFER_SIZE];
		try {
			for (String name : filenames) {
				FileHandle f = srcDir.child(name);
				ZipEntry entry = new ZipEntry(name);
				entry.setSize(f.length());
				entry.setTime(f.lastModified());
				zos.putNextEntry(entry);
				InputStream in = f.read();
				try {
					int n;
					while ((n = in.read(buf)) > 0) {
						zos.write(buf, 0, n);
					}
				} finally {
					in.close();
				}
				zos.closeEntry();
			}
			zos.finish();
		} catch (IOException e) {
			try { zos.finish(); } catch (IOException ignored) {}
			throw e;
		}
	}

	// ---- import ---------------------------------------------------------------

	/**
	 * Read a zip from {@code in} into a unique staging directory, validate it,
	 * and return a descriptor for the next phase (commit / cancel).
	 *
	 * <p>Caller owns {@code in}; this method does not close it.</p>
	 */
	public static SlotImportResult readSlotFromStream(InputStream in, String suggestedName) {
		String stagingRelPath = SLOT_ROOT + STAGING_PREFIX + UUID.randomUUID();
		FileHandle stagingDir = FileUtils.getFileHandle(stagingRelPath);
		try {
			stagingDir.mkdirs();
		} catch (Exception e) {
			return SlotImportResult.fail("staging_create_failed");
		}

		Set<String> seen = new HashSet<>();
		int count = 0;
		long totalBytes = 0;
		boolean hadMeta = false;

		ZipInputStream zis = new ZipInputStream(in);
		byte[] buf = new byte[BUFFER_SIZE];
		try {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					// Slots are flat; reject any directory entry.
					return failAndCleanup(stagingRelPath, "invalid_zip_entry");
				}
				String name = entry.getName();
				if (!isSafeEntryName(name, seen)) {
					return failAndCleanup(stagingRelPath, "invalid_zip_entry");
				}
				if (++count > MAX_ENTRY_COUNT) {
					return failAndCleanup(stagingRelPath, "too_many_entries");
				}

				// Write entry to staging/{name}.
				FileHandle outFile = stagingDir.child(name);
				OutputStream os = outFile.write(false);
				try {
					int n;
					while ((n = zis.read(buf)) > 0) {
						os.write(buf, 0, n);
						totalBytes += n;
						if (totalBytes > MAX_TOTAL_BYTES) {
							os.close();
							return failAndCleanup(stagingRelPath, "zip_too_large");
						}
					}
				} finally {
					os.close();
				}
				if (name.equals(META_FILE)) hadMeta = true;
				zis.closeEntry();
				seen.add(name);
			}
		} catch (Throwable e) {
			return failAndCleanup(stagingRelPath, "zip_read_error");
		}

		if (!hadMeta) {
			return failAndCleanup(stagingRelPath, "missing_meta");
		}

		// Read meta and validate version.
		String metaPath = stagingRelPath + "/" + META_FILE;
		SaveSlotMeta meta;
		try {
			Bundle b = FileUtils.bundleFromFile(metaPath);
			meta = new SaveSlotMeta();
			String n = b.getString("name");
			meta.name = (n == null || n.isEmpty()) ? null : n;
			meta.version = b.getInt("version");
			meta.depth = b.getInt("depth");
			meta.level = b.getInt("level");
			meta.savedAt = b.getLong("saved_at");
			String cls = b.getString("hero_class");
			try {
				if (cls != null && !cls.isEmpty()) meta.heroClass =
						com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass.valueOf(cls);
			} catch (IllegalArgumentException ignored) {}
		} catch (IOException e) {
			return failAndCleanup(stagingRelPath, "meta_read_failed");
		}

		if (meta.version != Game.versionCode) {
			return failAndCleanup(stagingRelPath, "version_mismatch");
		}

		// Resolve final name: prefer caller's suggestion, then meta, then "imported".
		String finalName;
		if (SaveSlotService.isValidName(suggestedName)) {
			finalName = suggestedName;
		} else if (meta.name != null && SaveSlotService.isValidName(meta.name)) {
			finalName = meta.name;
		} else {
			finalName = "imported";
		}

		boolean conflict = SaveSlotService.slotExists(finalName);
		return SlotImportResult.ok(finalName, meta, stagingRelPath, conflict);
	}

	/**
	 * Atomically move a staged import into {@code save_slots/{finalName}/}.
	 * Caller must hold {@code SaveSlotService.IO_LOCK}.
	 *
	 * <p>Steps (with crash recovery semantics):</p>
	 * <ol>
	 *   <li>Rewrite {@code meta.bundle} inside staging so {@code name == finalName}.
	 *       Without this the slot would still self-identify by the old name.</li>
	 *   <li>Move staging → {@code {finalName}.tmp} (clean any leftover .tmp first).</li>
	 *   <li>If {@code {finalName}} exists:
	 *       <ul>
	 *         <li>without overwrite → restore staging, return false.</li>
	 *         <li>with overwrite → move {@code {finalName}} → {@code {finalName}.bak}.</li>
	 *       </ul>
	 *   </li>
	 *   <li>Move {@code {finalName}.tmp} → {@code {finalName}}.</li>
	 *   <li>On failure, restore {@code .bak} → {@code {finalName}}.</li>
	 *   <li>On success, delete {@code .bak}.</li>
	 * </ol>
	 *
	 * <p>Crash recovery: see {@link #cleanupLeftovers()} — a {@code .bak} with a
	 * missing/invalid live target is restored, not deleted.</p>
	 */
	public static boolean commitImport(String stagingRelPath, String finalName, boolean overwrite) {
		if (!SaveSlotService.isValidName(finalName)) return false;
		FileHandle staging = FileUtils.getFileHandle(stagingRelPath);
		if (!staging.exists() || !staging.isDirectory()) return false;

		// Rewrite meta.bundle's name to the final slot name.
		if (!rewriteMetaName(staging, finalName)) return false;

		// Defensive: strip any pre-existing marker that may have come from a
		// malicious zip. The marker is our own commit signal, never imported data.
		FileHandle stagingMarker = staging.child(COMMIT_MARKER);
		if (stagingMarker.exists()) stagingMarker.delete();

		String dstPath = SLOT_ROOT + finalName;
		String tmpPath = dstPath + TMP_SUFFIX;
		String bakPath = dstPath + BAK_SUFFIX;

		FileHandle dst = FileUtils.getFileHandle(dstPath);
		FileHandle tmp = FileUtils.getFileHandle(tmpPath);
		FileHandle bak = FileUtils.getFileHandle(bakPath);

		// Clean any leftovers at .tmp from a previous crashed run.
		// (.bak is NOT cleaned here — see cleanupLeftovers() for recovery logic.)
		if (tmp.exists()) FileUtils.deleteDir(tmpPath);

		// Move staging into .tmp.
		if (!moveDir(staging, tmp)) return false;

		// Handle existing destination.
		boolean haveBak = false;
		if (dst.exists()) {
			if (!overwrite) {
				// Defensive: caller should have checked conflict already. Restore staging.
				moveDir(tmp, staging);
				return false;
			}
			// Clean stale .bak if any, then move existing dst → .bak.
			if (bak.exists()) FileUtils.deleteDir(bakPath);
			if (!moveDir(dst, bak)) {
				moveDir(tmp, staging);
				return false;
			}
			haveBak = true;
		}

		// Promote .tmp -> dst.
		if (!moveDir(tmp, dst)) {
			// Failed: try to restore the prior slot before giving up.
			if (haveBak) {
				if (dst.exists()) FileUtils.deleteDir(dstPath);
				moveDir(bak, dst);
			}
			// Put staging back so the caller can retry / cancel cleanly.
			if (tmp.exists()) moveDir(tmp, staging);
			return false;
		}

		// Promote finished: stamp a commit marker so cleanup knows not to
		// restore .bak over the freshly imported slot. If we crash between
		// moveDir() above and this marker write, cleanup will treat the .bak
		// as authoritative and revert (safe: old slot is preserved, but the
		// new import is silently undone — the user can retry).
		if (!writeCommitMarker(dst)) {
			// Marker write failed: treat as incomplete. Restore .bak if any.
			if (haveBak) {
				if (dst.exists()) FileUtils.deleteDir(dstPath);
				moveDir(bak, dst);
			}
			return false;
		}

		// Success: drop .bak and the marker.
		if (haveBak) FileUtils.deleteDir(bakPath);
		FileHandle dstMarkerAfter = dst.child(COMMIT_MARKER);
		if (dstMarkerAfter.exists()) dstMarkerAfter.delete();
		return true;
	}

	/**
	 * Read meta.bundle in {@code staging}, replace its {@code name} field with
	 * {@code finalName}, and write it back. Returns false on read/write failure.
	 */
	private static boolean rewriteMetaName(FileHandle staging, String finalName) {
		String metaPath = staging.path() + "/" + META_FILE;
		try {
			Bundle b = FileUtils.bundleFromFile(metaPath);
			b.put("name", finalName);
			FileUtils.bundleToFile(metaPath, b);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private static boolean writeCommitMarker(FileHandle dstDir) {
		try {
			FileHandle marker = dstDir.child(COMMIT_MARKER);
			marker.writeBytes(new byte[]{1}, false);
			return marker.exists();
		} catch (Throwable t) {
			return false;
		}
	}

	/** Delete a staging directory produced by {@link #readSlotFromStream}. */
	public static void cleanupStaging(String stagingRelPath) {
		if (stagingRelPath == null) return;
		FileUtils.deleteDir(stagingRelPath);
	}

	/**
	 * Scan {@code save_slots/} for stale staging / .tmp / .bak directories left
	 * by a crashed import. Called once at startup from {@code ShatteredPixelDungeon}.
	 *
	 * <p>Recovery semantics:</p>
	 * <ul>
	 *   <li>{@code .import-*} staging dir: always delete (no useful data, the
	 *       source zip lives outside our control).</li>
	 *   <li>{@code {name}.tmp}: always delete — it was on its way to becoming
	 *       {@code {name}} but the import was interrupted before promotion.</li>
	 *   <li>{@code {name}.bak}: the previous content of {@code {name}} before an
	 *       overwrite. Whether the overwrite actually completed is determined by
	 *       the {@link #COMMIT_MARKER} inside {@code {name}}:
	 *       <ul>
	 *         <li>marker present (overwrite completed, marker-delete crashed) →
	 *             delete {@code .bak}, drop marker.</li>
	 *         <li>marker absent (overwrite interrupted mid-promote, or marker
	 *             write itself crashed before bak-delete) → restore
	 *             {@code .bak -> {name}} so the player keeps their old slot.</li>
	 *       </ul>
	 *   </li>
	 * </ul>
	 */
	public static void cleanupLeftovers() {
		ArrayList<String> children = FileUtils.filesInDir(SLOT_ROOT);
		for (String child : children) {
			if (child == null) continue;
			if (child.startsWith(STAGING_PREFIX)) {
				FileUtils.deleteDir(SLOT_ROOT + child);
				continue;
			}
			if (child.endsWith(TMP_SUFFIX)) {
				FileUtils.deleteDir(SLOT_ROOT + child);
				continue;
			}
			if (child.endsWith(BAK_SUFFIX)) {
				String live = child.substring(0, child.length() - BAK_SUFFIX.length());
				String livePath = SLOT_ROOT + live;
				String bakPath = SLOT_ROOT + child;
				boolean liveIntact = FileUtils.dirExists(livePath)
						&& FileUtils.fileExists(livePath + "/" + META_FILE)
						&& FileUtils.fileExists(livePath + "/" + COMMIT_MARKER);
				if (liveIntact) {
					// Overwrite completed; bak is a stale leftover. Also drop
					// the marker so it doesn't leak into a later export.
					FileUtils.deleteDir(bakPath);
					FileHandle liveMarker = FileUtils.getFileHandle(livePath + "/" + COMMIT_MARKER);
					if (liveMarker.exists()) liveMarker.delete();
				} else {
					// Overwrite was interrupted mid-promote — restore the
					// previous slot so the player doesn't lose it.
					if (FileUtils.dirExists(bakPath)) {
						FileUtils.deleteDir(livePath);
						FileHandle bak = FileUtils.getFileHandle(bakPath);
						FileHandle liveHandle = FileUtils.getFileHandle(livePath);
						moveDir(bak, liveHandle);
					} else {
						// No bak content either — nothing to recover.
						FileUtils.deleteDir(bakPath);
					}
				}
			}
		}
	}

	// ---- helpers --------------------------------------------------------------

	private static boolean isSafeEntryName(String name, Set<String> seen) {
		if (name == null || name.isEmpty()) return false;
		if (name.equals(".") || name.equals("..")) return false;
		if (name.indexOf('/') >= 0) return false;
		if (name.indexOf('\\') >= 0) return false;
		if (name.indexOf(':') >= 0) return false;
		if (!SAFE_ENTRY_NAME.matcher(name).matches()) return false;
		if (seen.contains(name)) return false;
		return true;
	}

	private static SlotImportResult failAndCleanup(String stagingRelPath, String message) {
		FileUtils.deleteDir(stagingRelPath);
		return SlotImportResult.fail(message);
	}

	/**
	 * Move a directory's contents into a target directory.
	 * libgdx {@code FileHandle.moveTo} on Local files does a rename when
	 * possible, but cross-filesystem moves may fall back to copy+delete.
	 * For slot-scale data (a handful of small files) this is acceptable.
	 */
	private static boolean moveDir(FileHandle src, FileHandle dst) {
		if (src == null || !src.exists()) return false;
		try {
			dst.mkdirs();
			for (FileHandle f : src.list()) {
				if (f.isDirectory()) continue;
				f.moveTo(dst.child(f.name()));
			}
			FileUtils.deleteDir(src.path());
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	// Re-export GamesInProgress.gameFolder so callers don't reach into core internals elsewhere.
	@SuppressWarnings("unused")
	private static String gameFolderFor(int slot) {
		return GamesInProgress.gameFolder(slot);
	}
}
