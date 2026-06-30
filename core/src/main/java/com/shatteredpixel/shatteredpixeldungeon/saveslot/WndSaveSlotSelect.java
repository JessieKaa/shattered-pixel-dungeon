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

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.IconButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.IconTitle;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndMessage;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;
import com.watabou.noosa.Image;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.SaveSlotBridge;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WndSaveSlotSelect extends Window {

	public enum Mode { SAVE, LOAD, DEATH_LOAD }

	private static final int WIDTH = 130;
	private static final int HEIGHT = 144;
	private static final int SLOT_HEIGHT = 18;
	private static final int SLOT_GAP = 2;
	private static final int MARGIN = 2;

	private static final boolean LANG_ZH =
			Locale.getDefault().getLanguage().equalsIgnoreCase("zh")
			|| (Messages.lang() != null && Messages.lang().code() != null
			    && Messages.lang().code().toLowerCase(Locale.ENGLISH).startsWith("zh"));

	private static final Map<String, String> TXT_ZH = new HashMap<>();
	private static final Map<String, String> TXT_EN = new HashMap<>();
	static {
		TXT_ZH.put("title_save", "保存到槽位");
		TXT_ZH.put("title_load", "从槽位加载");
		TXT_ZH.put("empty", "暂无存档槽位。");
		TXT_ZH.put("slot_info", "%s  层:%d 级:%d");
		TXT_ZH.put("btn_new", "新建槽位");
		TXT_ZH.put("btn_save", "保存");
		TXT_ZH.put("btn_load", "加载");
		TXT_ZH.put("btn_overwrite", "覆盖");
		TXT_ZH.put("btn_delete", "删除");
		TXT_ZH.put("btn_cancel", "取消");
		TXT_ZH.put("btn_export", "导出");
		TXT_ZH.put("btn_import", "导入");
		TXT_ZH.put("btn_rename", "重命名");
		TXT_ZH.put("btn_cancel_import", "取消");
		TXT_ZH.put("name_prompt", "新建存档槽位");
		TXT_ZH.put("name_desc", "输入名称(字母、数字、_ 或 -,最多 20 字符)");
		TXT_ZH.put("name_invalid", "槽位名无效。请使用字母、数字、_ 或 -,最多 20 字符。");
		TXT_ZH.put("rename_prompt", "重命名导入的槽位");
		TXT_ZH.put("rename_desc", "已有同名槽位,请输入新名称");
		TXT_ZH.put("overwrite_title", "覆盖已有槽位");
		TXT_ZH.put("overwrite_body", "槽位 \"%s\" 已存在。是否覆盖?");
		TXT_ZH.put("conflict_title", "槽位名冲突");
		TXT_ZH.put("conflict_body", "槽位 \"%s\" 已存在。覆盖、重命名还是取消?");
		TXT_ZH.put("load_title", "加载存档槽位");
		TXT_ZH.put("load_body", "加载槽位 \"%s\"?当前未保存的进度将丢失。");
		TXT_ZH.put("delete_title", "删除存档槽位");
		TXT_ZH.put("delete_body", "删除槽位 \"%s\"?此操作不可撤销。");
		TXT_ZH.put("saved", "已保存到槽位 \"%s\"。");
		TXT_ZH.put("save_failed", "保存失败。请检查磁盘空间。");
		TXT_ZH.put("load_failed", "加载槽位失败。");
		TXT_ZH.put("exported", "已导出槽位 \"%s\"。");
		TXT_ZH.put("export_failed", "导出失败。");
		TXT_ZH.put("imported", "已导入槽位 \"%s\"。");
		TXT_ZH.put("import_failed", "导入失败。");
		TXT_ZH.put("version_mismatch", "该槽位由不同游戏版本创建,无法加载。");
		TXT_ZH.put("invalid_zip", "zip 文件无效或已损坏。");
		TXT_ZH.put("cancelled", "已取消。");

		TXT_EN.put("title_save", "Save to Slot");
		TXT_EN.put("title_load", "Load from Slot");
		TXT_EN.put("empty", "No save slots yet.");
		TXT_EN.put("slot_info", "%s  d:%d l:%d");
		TXT_EN.put("btn_new", "New Slot");
		TXT_EN.put("btn_save", "Save");
		TXT_EN.put("btn_load", "Load");
		TXT_EN.put("btn_overwrite", "Overwrite");
		TXT_EN.put("btn_delete", "Delete");
		TXT_EN.put("btn_cancel", "Cancel");
		TXT_EN.put("btn_export", "Export");
		TXT_EN.put("btn_import", "Import");
		TXT_EN.put("btn_rename", "Rename");
		TXT_EN.put("btn_cancel_import", "Cancel");
		TXT_EN.put("name_prompt", "New Save Slot");
		TXT_EN.put("name_desc", "Enter a name (letters, digits, _ or -, max 20 chars)");
		TXT_EN.put("name_invalid", "Invalid slot name. Use letters, digits, _ or -, max 20 chars.");
		TXT_EN.put("rename_prompt", "Rename Imported Slot");
		TXT_EN.put("rename_desc", "A slot with that name exists. Enter a different name.");
		TXT_EN.put("overwrite_title", "Overwrite Existing Slot");
		TXT_EN.put("overwrite_body", "Slot \"%s\" already exists. Overwrite it?");
		TXT_EN.put("conflict_title", "Slot Name Conflict");
		TXT_EN.put("conflict_body", "Slot \"%s\" exists. Overwrite, rename, or cancel?");
		TXT_EN.put("load_title", "Load Save Slot");
		TXT_EN.put("load_body", "Load slot \"%s\"? Current unsaved progress will be lost.");
		TXT_EN.put("delete_title", "Delete Save Slot");
		TXT_EN.put("delete_body", "Delete slot \"%s\"? This cannot be undone.");
		TXT_EN.put("saved", "Saved to slot \"%s\".");
		TXT_EN.put("save_failed", "Failed to save. Check disk space.");
		TXT_EN.put("load_failed", "Failed to load slot.");
		TXT_EN.put("exported", "Exported slot \"%s\".");
		TXT_EN.put("export_failed", "Failed to export slot.");
		TXT_EN.put("imported", "Imported slot \"%s\".");
		TXT_EN.put("import_failed", "Failed to import slot.");
		TXT_EN.put("version_mismatch", "This slot was created by a different game version and cannot be loaded.");
		TXT_EN.put("invalid_zip", "The zip file is invalid or corrupted.");
		TXT_EN.put("cancelled", "Cancelled.");
	}

	public static String txt(String key, Object... args) {
		String tmpl;
		String fromBundle = null;
		try {
			fromBundle = Messages.get(WndSaveSlotSelect.class, key);
		} catch (Throwable ignored) {}
		if (fromBundle != null && !fromBundle.contains("NO TEXT FOUND")) {
			tmpl = fromBundle;
		} else {
			tmpl = LANG_ZH ? TXT_ZH.get(key) : TXT_EN.get(key);
		}
		if (tmpl == null) return key;
		if (args == null || args.length == 0) return tmpl;
		try {
			return String.format(tmpl, args);
		} catch (Throwable e) {
			return tmpl;
		}
	}
	private static final int BOTTOM_BTN_HEIGHT = 16;
	private static final int BOTTOM_BTN_GAP = MARGIN;

	private final Mode mode;
	private final Runnable onCancel;

	public WndSaveSlotSelect(boolean saving) {
		this(saving ? Mode.SAVE : Mode.LOAD, null);
	}

	public WndSaveSlotSelect(boolean saving, Runnable onCancel) {
		this(saving ? Mode.SAVE : Mode.LOAD, onCancel);
	}

	public WndSaveSlotSelect(Mode mode) {
		this(mode, null);
	}

	public WndSaveSlotSelect(Mode mode, Runnable onCancel) {
		super();
		this.mode = mode;
		this.onCancel = onCancel;

		resize(WIDTH, HEIGHT);

		IconTitle title = new IconTitle(
				mode == Mode.SAVE ? Icons.SHPX.get() : Icons.DEPTH.get(),
				txt( mode == Mode.SAVE ? "title_save" : "title_load"));
		title.setRect(0, 0, WIDTH, 0);
		title.setPos(0, 0);
		add(title);

		int topList = (int) title.bottom() + MARGIN * 2;
		int bottomRowTop = HEIGHT - BOTTOM_BTN_HEIGHT - MARGIN;
		int listHeight = bottomRowTop - topList;

		ScrollPane pane = new ScrollPane(new Component());
		add(pane);
		pane.setRect(0, topList, WIDTH, listHeight);

		Component content = pane.content();

		List<SaveSlotMeta> slots = SaveSlotService.listSlots();

		int y = 0;
		if (slots.isEmpty()) {
			RenderedTextBlock empty = PixelScene.renderTextBlock(
					txt( "empty"), 7);
			empty.maxWidth(WIDTH - MARGIN * 2);
			empty.setPos(MARGIN, y);
			content.add(empty);
			y = (int) empty.bottom() + MARGIN;
		} else {
			for (SaveSlotMeta meta : slots) {
				SlotRow row = new SlotRow(meta);
				row.setRect(0, y, WIDTH, SLOT_HEIGHT);
				content.add(row);
				y += SLOT_HEIGHT + SLOT_GAP;
			}
		}
		content.setRect(0, 0, WIDTH, y);

		// Bottom action row
		float bx = 0;
		float by = bottomRowTop;

		boolean showImport = (mode == Mode.SAVE) && bridgeAvailable();

		if (mode == Mode.SAVE) {
			RedButton newBtn = new RedButton(txt( "btn_new")) {
				@Override
				protected void onClick() {
					askNewSlotName();
				}
			};
			float newBtnW = showImport ? (WIDTH - 22 - BOTTOM_BTN_GAP) * 0.6f : WIDTH - 22 - BOTTOM_BTN_GAP;
			newBtn.setRect(bx, by, newBtnW, BOTTOM_BTN_HEIGHT);
			add(newBtn);
			bx = newBtn.right() + BOTTOM_BTN_GAP;

			if (showImport) {
				RedButton importBtn = new RedButton(txt("btn_import"), 6) {
					@Override
					protected void onClick() {
						startImport();
					}
				};
				importBtn.setRect(bx, by, WIDTH - bx - 22 - BOTTOM_BTN_GAP, BOTTOM_BTN_HEIGHT);
				add(importBtn);
				bx = importBtn.right() + BOTTOM_BTN_GAP;
			}
		}

		IconButton closeBtn = new IconButton(Icons.get(Icons.CLOSE)) {
			@Override
			protected void onClick() {
				hide();
				if (onCancel != null) onCancel.run();
			}
		};
		closeBtn.setRect(bx, by, WIDTH - bx, BOTTOM_BTN_HEIGHT);
		add(closeBtn);
	}

	private static boolean bridgeAvailable() {
		SaveSlotBridge b = SaveSlotService.getBridge();
		return b != null && b.available();
	}

	private void askNewSlotName() {
		ShatteredPixelDungeon.scene().addToFront(new WndTextInput(
				txt( "name_prompt"),
				txt( "name_desc"),
				"",
				20,
				false,
				txt( "btn_save"),
				txt( "btn_cancel")) {
			@Override
			public void onSelect(boolean positive, String text) {
				if (!positive || text == null) return;
				text = text.trim();
				if (!SaveSlotService.isValidName(text)) {
					ShatteredPixelDungeon.scene().addToFront(new WndMessage(
							txt( "name_invalid")));
					return;
				}
				if (SaveSlotService.slotExists(text)) {
					confirmOverwrite(text);
				} else {
					doSave(text);
				}
			}
		});
	}

	private void confirmOverwrite(final String name) {
		ShatteredPixelDungeon.scene().addToFront(new WndOptions(
				txt( "overwrite_title"),
				txt( "overwrite_body", name),
				txt( "btn_overwrite"),
				txt( "btn_cancel")) {
			@Override
			protected void onSelect(int index) {
				if (index == 0) {
					if (mode == Mode.SAVE) {
						doSave(name);
					} else {
						doLoad(name);
					}
				}
			}
		});
	}

	private void doSave(final String name) {
		try {
			SaveSlotService.saveToSlot(name);
			hide();
			ShatteredPixelDungeon.scene().addToFront(new WndMessage(
					txt( "saved", name)));
		} catch (IOException e) {
			ShatteredPixelDungeon.reportException(e);
			ShatteredPixelDungeon.scene().addToFront(new WndMessage(
					txt( "save_failed")));
		}
	}

	private void confirmLoad(final String name) {
		ShatteredPixelDungeon.scene().addToFront(new WndOptions(
				txt( "load_title"),
				txt( "load_body", name),
				txt( "btn_load"),
				txt( "btn_cancel")) {
			@Override
			protected void onSelect(int index) {
				if (index == 0) doLoad(name);
			}
		});
	}

	private void doLoad(String name) {
		try {
			SaveSlotService.loadFromSlot(name);
			hide();
		} catch (IOException e) {
			ShatteredPixelDungeon.reportException(e);
			String msg;
			if (e.getMessage() != null && e.getMessage().contains("version")) {
				msg = txt( "version_mismatch");
			} else {
				msg = txt( "load_failed");
			}
			ShatteredPixelDungeon.scene().addToFront(new WndMessage(msg));
		}
	}

	private void confirmDelete(final String name) {
		ShatteredPixelDungeon.scene().addToFront(new WndOptions(
				txt( "delete_title"),
				txt( "delete_body", name),
				txt( "btn_delete"),
				txt( "btn_cancel")) {
			@Override
			protected void onSelect(int index) {
				if (index == 0) {
					SaveSlotService.deleteSlot(name);
					hide();
					ShatteredPixelDungeon.scene().addToFront(new WndSaveSlotSelect(mode, onCancel));
				}
			}
		});
	}

	// ---- export / import ------------------------------------------------------

	private void startExport(final SaveSlotMeta meta) {
		final SaveSlotBridge b = SaveSlotService.getBridge();
		if (b == null || !b.available()) return;
		b.exportSlot(meta.name, new SaveSlotBridge.ExportCallback() {
			@Override
			public void onComplete(boolean ok, String message) {
				String msg = ok ? txt("exported", meta.name) : txt("export_failed");
				ShatteredPixelDungeon.scene().addToFront(new WndMessage(msg));
			}
		});
	}

	private void startImport() {
		final SaveSlotBridge b = SaveSlotService.getBridge();
		if (b == null || !b.available()) return;
		b.importSlot(new SaveSlotBridge.ImportCallback() {
			@Override
			public void onComplete(boolean ok, String importedName, String message) {
				if (ok) {
					hide();
					ShatteredPixelDungeon.scene().addToFront(new WndMessage(
							txt("imported", importedName)));
				} else {
					String m = mapImportMessage(message);
					ShatteredPixelDungeon.scene().addToFront(new WndMessage(m));
				}
			}
		});
	}

	private static String mapImportMessage(String code) {
		if (code == null) return txt("import_failed");
		switch (code) {
			case "version_mismatch": return txt("version_mismatch");
			case "invalid_zip_entry":
			case "missing_meta":
			case "meta_read_failed":
			case "zip_read_error":
			case "too_many_entries":
			case "zip_too_large":
			case "staging_create_failed":
				return txt("invalid_zip");
			case "cancelled": return txt("cancelled");
			default: return txt("import_failed");
		}
	}

	private class SlotRow extends Component {

		private final SaveSlotMeta meta;
		private final IconButton iconBtn;
		private final RenderedTextBlock label;
		private final RedButton actionBtn;
		private final IconButton exportBtn;
		private final IconButton deleteBtn;

		SlotRow(final SaveSlotMeta meta) {
			super();
			this.meta = meta;

			Image icon = meta.icon();
			iconBtn = new IconButton(icon);
			iconBtn.enable(false);
			add(iconBtn);

			String info = txt( "slot_info",
					meta.name, meta.depth, meta.level);
			label = PixelScene.renderTextBlock(info, 7);
			add(label);

			actionBtn = new RedButton(txt( mode == Mode.SAVE ? "btn_save" : "btn_load"), 6) {
				@Override
				protected void onClick() {
					if (mode == Mode.SAVE) {
						confirmOverwrite(meta.name);
					} else {
						confirmLoad(meta.name);
					}
				}
			};
			add(actionBtn);

			// Export only in non-DEATH_LOAD modes and only when the bridge is usable.
			if (mode != Mode.DEATH_LOAD && bridgeAvailable()) {
				exportBtn = new IconButton(Icons.get(Icons.COPY)) {
					@Override
					protected void onClick() {
						startExport(meta);
					}
				};
			} else {
				exportBtn = null;
			}
			if (exportBtn != null) add(exportBtn);

			deleteBtn = new IconButton(Icons.get(Icons.CLOSE)) {
				@Override
				protected void onClick() {
					confirmDelete(meta.name);
				}
			};
			add(deleteBtn);
		}

		@Override
		protected void layout() {
			super.layout();

			float actionW = 36;
			float deleteW = 14;
			float exportW = 14;

			deleteBtn.setRect(width - deleteW, y, deleteW, height);
			float cursor = deleteBtn.left() - MARGIN;
			if (exportBtn != null) {
				exportBtn.setRect(cursor - exportW, y, exportW, height);
				cursor = exportBtn.left() - MARGIN;
			}
			actionBtn.setRect(cursor - actionW, y, actionW, height);
			iconBtn.setRect(0, y, 14, height);

			label.maxWidth((int) (actionBtn.left() - MARGIN - (iconBtn.right() + MARGIN)));
			label.setPos(iconBtn.right() + MARGIN, y + (height - label.height()) / 2f);
		}
	}
}
