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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shatteredpixel.shatteredpixeldungeon.saveslot;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndResurrect;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.FileUtils;
import com.watabou.utils.SaveSlotBridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fork extension: local save-slot service.
 *
 * Provides save-scum-style multiple named save slots, isolated from the upstream
 * `game{1..6}` slot system. Also intercepts hero death to offer a one-shot reload
 * from a slot before falling through to the upstream die() pipeline.
 *
 * All fork code lives in this subpackage to minimise merge conflict surface
 * with upstream. Upstream call sites are limited to:
 *   - {@link Hero#die(Object)}  -> single-line hook at top
 *   - {@link WndGame#WndGame()} -> single-line hook before resize()
 */
public class SaveSlotService {

	private static final String SLOT_ROOT = "save_slots/";
	private static final String META_FILE = "meta.bundle";

	private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]{1,20}");

	private static final boolean LANG_ZH =
			Locale.getDefault().getLanguage().equalsIgnoreCase("zh");

	/** Serialises all slot IO so export/import/clean-up can't race save/load/delete. */
	private static final Object IO_LOCK = new Object();

	private static SaveSlotBridge bridge;

	public static void setBridge(SaveSlotBridge b) { bridge = b; }
	public static SaveSlotBridge getBridge() { return bridge; }

	// ---- name & guard helpers -------------------------------------------------

	public static boolean isValidName(String name) {
		if (name == null || name.isEmpty()) return false;
		if (name.contains("..") || name.contains("/") || name.contains("\\")) return false;
		return NAME_PATTERN.matcher(name).matches();
	}

	public static boolean isSaveAllowed() {
		return !Dungeon.daily && !Dungeon.dailyReplay;
	}

	public static boolean slotExists(String name) {
		return FileUtils.dirExists(slotDir(name));
	}

	public static boolean hasAvailableSlots() {
		return !listSlots().isEmpty();
	}

	// ---- core save / load / delete -------------------------------------------

	public static void saveToSlot(String name) throws IOException {
		synchronized (IO_LOCK) {
			if (!isValidName(name)) {
				throw new IllegalArgumentException("invalid slot name: " + name);
			}
			if (!isSaveAllowed()) {
				throw new IllegalStateException("save disabled for daily run");
			}
			if (Dungeon.hero == null || !Dungeon.hero.isAlive()) {
				throw new IllegalStateException("hero not alive, cannot save");
			}

			Dungeon.saveAll();

			String src = GamesInProgress.gameFolder(GamesInProgress.curSlot);
			String dst = slotDir(name);

			FileUtils.deleteDir(dst);
			if (!FileUtils.copyDir(src, dst)) {
				throw new IOException("copyDir failed: " + src + " -> " + dst);
			}

			Bundle meta = new Bundle();
			meta.put("name", name);
			meta.put("version", Game.versionCode);
			meta.put("depth", Dungeon.depth);
			meta.put("level", Dungeon.hero.lvl);
			meta.put("hero_class", Dungeon.hero.heroClass);
			meta.put("saved_at", System.currentTimeMillis());
			FileUtils.bundleToFile(dst + "/" + META_FILE, meta);
		}
	}

	public static void loadFromSlot(String name) throws IOException {
		synchronized (IO_LOCK) {
			if (!isValidName(name)) {
				throw new IllegalArgumentException("invalid slot name: " + name);
			}
			if (!isSaveAllowed()) {
				throw new IllegalStateException("load disabled for daily run");
			}
			if (!slotExists(name)) {
				throw new IOException("slot not found: " + name);
			}

			SaveSlotMeta meta = readMeta(name);
			if (meta == null) {
				throw new IOException("missing meta for slot: " + name);
			}
			if (meta.version != Game.versionCode) {
				throw new IOException("version mismatch: slot=" + meta.version + " game=" + Game.versionCode);
			}

			copySlotToCurrentGame(name);

			GamesInProgress.setUnknown(GamesInProgress.curSlot);

			// 清除可能存在的 WndResurrect 占位符(死亡读档场景)
			// 否则后续死亡永远不进 Rankings
			WndResurrect.instance = null;

			InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
			Game.switchScene(InterlevelScene.class);
		}
	}

	static void copySlotToCurrentGame(String name) throws IOException {
		synchronized (IO_LOCK) {
			if (!isValidName(name)) {
				throw new IllegalArgumentException("invalid slot name: " + name);
			}
			if (!slotExists(name)) {
				throw new IOException("slot not found: " + name);
			}

			String src = slotDir(name);
			String dst = GamesInProgress.gameFolder(GamesInProgress.curSlot);

			FileUtils.deleteDir(dst);
			if (!FileUtils.copyDir(src, dst)) {
				throw new IOException("copyDir failed: " + src + " -> " + dst);
			}
		}
	}

	public static void deleteSlot(String name) {
		synchronized (IO_LOCK) {
			if (!isValidName(name)) return;
			FileUtils.deleteDir(slotDir(name));
		}
	}

	// ---- export / import ------------------------------------------------------

	/**
	 * Stream a slot's zip into the provided {@code out}. Caller owns the stream.
	 * Verifies the slot exists and its meta is intact before exporting.
	 */
	public static void exportToStream(String name, OutputStream out) throws IOException {
		synchronized (IO_LOCK) {
			if (!isValidName(name)) {
				throw new IllegalArgumentException("invalid slot name: " + name);
			}
			if (!isSaveAllowed()) {
				throw new IllegalStateException("export disabled for daily run");
			}
			if (!slotExists(name)) {
				throw new IOException("slot not found: " + name);
			}
			// Defensive: refuse to export a slot whose meta is missing or version-mismatched.
			SaveSlotMeta meta = readMeta(name);
			if (meta == null) {
				throw new IOException("missing meta for slot: " + name);
			}
			if (meta.version != Game.versionCode) {
				throw new IOException("version mismatch: slot=" + meta.version + " game=" + Game.versionCode);
			}
			SaveSlotIO.writeSlotToStream(name, out);
		}
	}

	/**
	 * Read a zip from {@code in} into a staging directory and return a descriptor.
	 * Does NOT touch the existing slot; caller must call {@link #commitImport} or
	 * {@link #cancelImport} afterwards.
	 */
	public static SlotImportResult importFromStream(InputStream in, String suggestedName) {
		synchronized (IO_LOCK) {
			if (!isSaveAllowed()) {
				return SlotImportResult.fail("daily_disabled");
			}
			return SaveSlotIO.readSlotFromStream(in, suggestedName);
		}
	}

	/**
	 * Promote a staged import into {@code save_slots/{finalName}/}.
	 * Must be called with {@code overwrite=true} if a slot with that name already
	 * exists.
	 */
	public static boolean commitImport(SlotImportResult result, String finalName, boolean overwrite) {
		synchronized (IO_LOCK) {
			if (result == null || !result.ok || result.stagingRelPath == null) return false;
			return SaveSlotIO.commitImport(result.stagingRelPath, finalName, overwrite);
		}
	}

	/** Discard a staged import. */
	public static void cancelImport(SlotImportResult result) {
		synchronized (IO_LOCK) {
			if (result == null || result.stagingRelPath == null) return;
			SaveSlotIO.cleanupStaging(result.stagingRelPath);
		}
	}

	/** Sweep stale staging/.tmp/.bak directories left by a crashed import. */
	public static void cleanupLeftovers() {
		synchronized (IO_LOCK) {
			SaveSlotIO.cleanupLeftovers();
		}
	}

	// ---- meta read / list -----------------------------------------------------

	public static List<SaveSlotMeta> listSlots() {
		List<SaveSlotMeta> result = new ArrayList<>();
		ArrayList<String> children = FileUtils.filesInDir(SLOT_ROOT);
		for (String child : children) {
			if (!isValidName(child)) continue;
			String metaPath = SLOT_ROOT + child + "/" + META_FILE;
			if (!FileUtils.fileExists(metaPath)) continue;
			SaveSlotMeta m = readMetaFromPath(metaPath, child);
			if (m != null) result.add(m);
		}
		return result;
	}

	public static SaveSlotMeta readMeta(String name) {
		if (!isValidName(name)) return null;
		String metaPath = slotDir(name) + "/" + META_FILE;
		if (!FileUtils.fileExists(metaPath)) return null;
		return readMetaFromPath(metaPath, name);
	}

	private static SaveSlotMeta readMetaFromPath(String metaPath, String fallbackName) {
		try {
			Bundle b = FileUtils.bundleFromFile(metaPath);
			SaveSlotMeta m = new SaveSlotMeta();
			m.name = b.getString("name");
			if (m.name == null || m.name.isEmpty()) m.name = fallbackName;
			m.version = b.getInt("version");
			m.depth = b.getInt("depth");
			m.level = b.getInt("level");
			m.savedAt = b.getLong("saved_at");
			String cls = b.getString("hero_class");
			try {
				m.heroClass = cls == null || cls.isEmpty() ? HeroClass.WARRIOR : HeroClass.valueOf(cls);
			} catch (IllegalArgumentException e) {
				m.heroClass = HeroClass.WARRIOR;
			}
			return m;
		} catch (IOException e) {
			return null;
		}
	}

	private static String slotDir(String name) {
		return SLOT_ROOT + name;
	}

	// ---- death-reload hook ----------------------------------------------------

	/**
	 * Single-point hook invoked at the top of {@link Hero#die(Object)}.
	 *
	 * First entry (per Hero instance): if save is allowed and at least one slot
	 * exists, set the saveSlotPrompted flag, occupy {@link WndResurrect#instance}
	 * to suppress Rankings submission, and show the slot-select window on the
	 * render thread. Returns {@code true} so the caller short-circuits.
	 *
	 * On cancel: the slot window clears WndResurrect.instance and re-enters
	 * {@code hero.die(cause)}. Because {@code saveSlotPrompted} is now true,
	 * this method returns {@code false} and the upstream die() body runs
	 * verbatim (ankh handling, reallyDie, etc).
	 *
	 * Ankh interaction: when the hero carries an unblessed ankh, cancelling the
	 * reload dialog falls through to the upstream ankh resurrect flow
	 * ({@code WndResurrect} window). With a blessed ankh, the upstream path
	 * consumes it. This matches the prior fork behaviour and is intentional.
	 */
	public static boolean interceptDeath(Hero hero, Object cause) {
		if (hero.saveSlotPrompted) return false;
		if (!isSaveAllowed() || !hasAvailableSlots()) return false;

		hero.saveSlotPrompted = true;
		WndResurrect.instance = new Object();
		final Object finalCause = cause;
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				GameScene.show(new WndSaveSlotSelect(WndSaveSlotSelect.Mode.DEATH_LOAD, () -> {
					WndResurrect.instance = null;
					hero.die(finalCause);
				}));
			}
		});
		return true;
	}

	// ---- WndGame menu hook ----------------------------------------------------

	private static final String MENU_SAVE_ZH = "保存到槽位";
	private static final String MENU_LOAD_ZH = "从槽位加载";
	private static final String MENU_SAVE_EN = "Save to Slot";
	private static final String MENU_LOAD_EN = "Load from Slot";

	/**
	 * Single-point hook invoked near the end of {@link WndGame#WndGame()}.
	 * Adds the Save/Load slot buttons when the current run allows saving.
	 */
	public static void addMenuButtons(WndGame wnd) {
		if (!isSaveAllowed()) return;

		RedButton saveBtn = new RedButton(LANG_ZH ? MENU_SAVE_ZH : MENU_SAVE_EN) {
			@Override
			protected void onClick() {
				wnd.hide();
				GameScene.show(new WndSaveSlotSelect(WndSaveSlotSelect.Mode.SAVE));
			}
		};
		saveBtn.icon(Icons.get(Icons.SHPX));
		wnd.addButton(saveBtn);

		RedButton loadBtn = new RedButton(LANG_ZH ? MENU_LOAD_ZH : MENU_LOAD_EN) {
			@Override
			protected void onClick() {
				wnd.hide();
				GameScene.show(new WndSaveSlotSelect(WndSaveSlotSelect.Mode.LOAD));
			}
		};
		loadBtn.icon(Icons.get(Icons.DEPTH));
		wnd.addButton(loadBtn);
	}
}
