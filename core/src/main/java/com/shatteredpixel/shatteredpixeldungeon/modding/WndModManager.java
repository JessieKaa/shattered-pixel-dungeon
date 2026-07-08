package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.IconTitle;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndMessage;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.ui.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M5b mod-toggle window. Lists every discovered {@link ModManifest} (from {@link ModRegistry}) with
 * a per-mod {@link CheckBox}; toggling a box persists the choice via {@link ModRegistry#setEnabled}
 * so the next {@link LuaEngine#init()} honours it when loading entry scripts.
 *
 * <p>i18n is hardcoded ZH/EN via {@link #TXT_ZH}/{@link #TXT_EN} + {@link #txt(String)} rather than
 * {@link Messages#get}, which does not reliably resolve keys for fork classes outside the upstream
 * windows package (see CLAUDE.md). The pattern mirrors {@code WndSaveSlotSelect}.
 *
 * <p>Toggle takes effect on restart: registration is an irreversible add into the Lua registries,
 * so M5b does not hot-reload. The bottom hint states this. A headless test cannot exercise the
 * render path; {@code LuaModEntryTest} covers the data layer (load on enable, skip on disable).
 *
 * <p>M13a: long-pressing an {@link ModManifest.Origin#EXTERNAL} row opens an uninstall confirm
 * ({@link ModInstaller#removeMod}); builtin rows are refused. The {@code already_exists} import
 * error message points users here for the update flow (uninstall + re-import), since the
 * {@code ModImporter} picker abstraction does not expose the conflicting id or stream needed for a
 * single-confirm overwrite.
 */
public class WndModManager extends Window {

	private static final int WIDTH = 130;
	private static final int HEIGHT = 144;
	private static final int MARGIN = 2;
	private static final int CHECK_HEIGHT = 18;
	private static final int CHECK_GAP = 1;
	private static final int DESC_GAP = 4;
	private static final int HINT_HEIGHT = 18;
	// M12b: import button + 1-line result block sit between the scroll list and the hint.
	private static final int IMPORT_BTN_HEIGHT = 16;
	private static final int RESULT_HEIGHT = 14;

	private static final boolean LANG_ZH =
			Locale.getDefault().getLanguage().equalsIgnoreCase("zh")
			|| (Messages.lang() != null && Messages.lang().code() != null
			    && Messages.lang().code().toLowerCase(Locale.ENGLISH).startsWith("zh"));

	private static final Map<String, String> TXT_ZH = new HashMap<>();
	private static final Map<String, String> TXT_EN = new HashMap<>();
	static {
		TXT_ZH.put("title", "模组管理");
		TXT_ZH.put("empty", "未发现模组。");
		TXT_ZH.put("hint", "长按外部模组可卸载,更改重启后生效。");
		TXT_ZH.put("origin_builtin", "[内建]");
		TXT_ZH.put("origin_external", "[外部]");
		// M12b: mod zip import
		TXT_ZH.put("import_button", "导入模组 (.zip)");
		TXT_ZH.put("import_start", "请在弹出的对话框中选择 zip…");
		TXT_ZH.put("import_ok", "已导入:%s,重启游戏后生效。");
		TXT_ZH.put("import_cancel", "已取消导入。");
		TXT_ZH.put("err_io_error", "导入失败(读写错误)。");
		TXT_ZH.put("err_invalid_zip", "压缩包无效或已损坏。");
		TXT_ZH.put("err_too_many_entries", "压缩包内文件过多。");
		TXT_ZH.put("err_zip_too_large", "压缩包解压后过大(超过 64MB)。");
		TXT_ZH.put("err_bad_manifest", "模组描述文件(mod.json)无效。");
		TXT_ZH.put("err_version_mismatch", "模组与当前游戏版本不兼容。");
		// M13a: already_exists now points at the in-app uninstall entry (update = uninstall + re-import).
		TXT_ZH.put("err_already_exists", "该模组已存在。如需更新,请先卸载已安装的版本,再重新导入。");
		TXT_ZH.put("err_unknown", "导入失败(%s)。");
		// M13a: uninstall (long-press an external mod row)
		TXT_ZH.put("uninstall_title", "卸载模组");
		TXT_ZH.put("uninstall_confirm", "确认卸载 %s?需重启游戏才能彻底生效。");
		TXT_ZH.put("uninstall_btn", "卸载");
		TXT_ZH.put("cancel", "取消");
		TXT_ZH.put("builtin_no_uninstall", "内建模组不可卸载。");
		TXT_ZH.put("err_uninstall", "卸载失败(%s)。");
		TXT_EN.put("title", "Mods");
		TXT_EN.put("empty", "No mods found.");
		TXT_EN.put("hint", "Long-press an external mod to uninstall. Changes apply after restart.");
		TXT_EN.put("origin_builtin", "[built-in]");
		TXT_EN.put("origin_external", "[external]");
		// M12b: mod zip import
		TXT_EN.put("import_button", "Import Mod (.zip)");
		TXT_EN.put("import_start", "Pick a zip in the dialog…");
		TXT_EN.put("import_ok", "Imported: %s. Restart to load.");
		TXT_EN.put("import_cancel", "Import cancelled.");
		TXT_EN.put("err_io_error", "Import failed (I/O error).");
		TXT_EN.put("err_invalid_zip", "Invalid or corrupted zip.");
		TXT_EN.put("err_too_many_entries", "Zip has too many files.");
		TXT_EN.put("err_zip_too_large", "Zip is too large (over 64MB unpacked).");
		TXT_EN.put("err_bad_manifest", "Invalid mod manifest (mod.json).");
		TXT_EN.put("err_version_mismatch", "Mod is incompatible with this game version.");
		// M13a: already_exists now points at the in-app uninstall entry (update = uninstall + re-import).
		TXT_EN.put("err_already_exists", "This mod already exists. To update, uninstall the installed version first, then re-import.");
		TXT_EN.put("err_unknown", "Import failed (%s).");
		// M13a: uninstall (long-press an external mod row)
		TXT_EN.put("uninstall_title", "Uninstall Mod");
		TXT_EN.put("uninstall_confirm", "Uninstall %s? Restart to fully apply.");
		TXT_EN.put("uninstall_btn", "Uninstall");
		TXT_EN.put("cancel", "Cancel");
		TXT_EN.put("builtin_no_uninstall", "Built-in mods cannot be uninstalled.");
		TXT_EN.put("err_uninstall", "Uninstall failed (%s).");
	}

	private static String txt(String key) {
		Map<String, String> m = LANG_ZH ? TXT_ZH : TXT_EN;
		String s = m.get(key);
		return s != null ? s : key;
	}

	/** Localize a {@code %s} template (import_ok / err_unknown). */
	private static String txtf(String key, String arg) {
		String tpl = txt(key);
		int i = tpl.indexOf("%s");
		if (i < 0) return tpl;
		return tpl.substring(0, i) + arg + tpl.substring(i + 2);
	}

	/** Map a {@link ModInstaller} error code to a localized result string. */
	private static String errorText(String code) {
		String key = "err_" + code;
		String localized = txt(key);
		if (localized.equals(key)) return txtf("err_unknown", code);   // unmapped code → fallback
		return localized;
	}

	/** M12a: localized origin badge so players can tell packaged mods from external ones.
	 *  Static + outer-placed so {@link ModCheckBox}'s super() can call it before its own
	 *  constructor runs (an instance method would be illegal there). Null origin renders as
	 *  builtin — the safe default. */
	private static String originTag(ModManifest mod) {
		boolean external = mod.origin == ModManifest.Origin.EXTERNAL;
		return txt(external ? "origin_external" : "origin_builtin");
	}

	/** M12b: single-line import result block, updated from the picker callback (render thread). */
	private RenderedTextBlock importResult;

	public WndModManager() {
		super();
		resize(WIDTH, HEIGHT);

		IconTitle title = new IconTitle(Icons.get(Icons.SHPX), txt("title"));
		title.setRect(0, 0, WIDTH, 0);
		add(title);

		int hintTop = HEIGHT - HINT_HEIGHT;
		int listTop = (int) title.bottom() + MARGIN * 2;

		// M12b: when a platform picker is registered, carve out a bottom band for the import
		// button + a result line. When none is registered (android pre-M12c, iOS) the layout
		// collapses to the M12a form (no button, full-height list) — no crash.
		boolean importerAvailable = ModImporter.get() != null;
		int btnTop = -1;
		int resultTop = -1;
		int listBottom;
		if (importerAvailable) {
			resultTop = hintTop - MARGIN - RESULT_HEIGHT;
			btnTop = resultTop - MARGIN - IMPORT_BTN_HEIGHT;
			listBottom = btnTop - MARGIN;
		} else {
			listBottom = hintTop;
		}

		ScrollPane pane = new ScrollPane(new Component());
		add(pane);
		pane.setRect(0, listTop, WIDTH, listBottom - listTop);
		Component content = pane.content();

		List<ModManifest> mods = ModRegistry.all();
		float y = 0;
		if (mods.isEmpty()) {
			RenderedTextBlock empty = PixelScene.renderTextBlock(txt("empty"), 8);
			empty.maxWidth(WIDTH - MARGIN * 2);
			empty.setPos(MARGIN, y);
			content.add(empty);
			y = empty.bottom() + MARGIN;
		} else {
			for (ModManifest mod : mods) {
				ModCheckBox box = new ModCheckBox(mod);
				box.checked(ModRegistry.isEnabled(mod.id));
				box.setRect(MARGIN, y, WIDTH - MARGIN * 2, CHECK_HEIGHT);
				content.add(box);
				y = box.bottom() + CHECK_GAP;

				if (mod.description != null && !mod.description.isEmpty()) {
					RenderedTextBlock desc = PixelScene.renderTextBlock(mod.description, 6);
					desc.maxWidth(WIDTH - MARGIN * 2);
					desc.hardlight(0xCCCCCC);
					desc.setPos(MARGIN, y);
					content.add(desc);
					y = desc.bottom() + DESC_GAP;
				}
			}
		}
		content.setRect(0, 0, WIDTH, y);

		if (importerAvailable) {
			RedButton importBtn = new RedButton(txt("import_button"), 7) {
				@Override
				protected void onClick() {
					ModImporter imp = ModImporter.get();
					if (imp == null) return;
					// The picker runs off the render thread; it hops the callback back here.
					showImportResult(txt("import_start"), 0xCCCCCC);
					imp.pickZip(new ModImporter.ImportCallback() {
						@Override public void onSuccess(String modId) {
							showImportResult(txtf("import_ok", modId), 0x88FF88);
						}
						@Override public void onError(String code) {
							showImportResult(errorText(code), 0xFF8888);
						}
						@Override public void onCancel() {
							showImportResult(txt("import_cancel"), 0xCCCCCC);
						}
					});
				}
			};
			importBtn.setRect(MARGIN, btnTop, WIDTH - MARGIN * 2, IMPORT_BTN_HEIGHT);
			add(importBtn);

			importResult = PixelScene.renderTextBlock("", 6);
			importResult.maxWidth(WIDTH - MARGIN * 2);
			importResult.setPos(MARGIN, resultTop);
			add(importResult);
		}

		RenderedTextBlock hint = PixelScene.renderTextBlock(txt("hint"), 6);
		hint.maxWidth(WIDTH - MARGIN * 2);
		hint.hardlight(0xFFFF88);
		hint.setPos(MARGIN, hintTop);
		add(hint);
	}

	/** Update the import-result line. Runs on the render thread (callback hops back via postRunnable). */
	private void showImportResult(String text, int color) {
		if (importResult == null || text == null) return;
		importResult.text(text);
		importResult.hardlight(color);
		PixelScene.align(importResult);
	}

	/**
	 * M13a: re-scan {@link ModRegistry} so an uninstalled mod drops out of the cached list, then
	 * re-open this window. Registration at the Lua layer is irreversible until restart, so the mod's
	 * registered items may linger in-game — but the manifest entry (this list) reflects the deleted
	 * files immediately. The {@code hide()} + {@code GameScene.show(new WndModManager())} replace
	 * mirrors {@code WndUpgrade}'s self-re-show pattern and avoids in-place noosa Component teardown.
	 */
	private void refreshAfterModChange() {
		ModRegistry.scan();
		hide();
		GameScene.show(new WndModManager());
	}

	/**
	 * A {@link CheckBox} bound to one mod id. {@link CheckBox#onClick()} auto-toggles the checked
	 * state; this override runs that first, then persists the new state via {@link ModRegistry}.
	 * M13a: long-press on an {@link ModManifest.Origin#EXTERNAL} row opens an uninstall confirm
	 * (builtin rows get a "not uninstallable" notice instead). Non-static so it can reach
	 * {@link WndModManager#refreshAfterModChange} after a successful removal.
	 */
	private class ModCheckBox extends CheckBox {
		private final ModManifest mod;

		ModCheckBox(ModManifest mod) {
			super(mod.name + " v" + mod.version + " " + originTag(mod));
			this.mod = mod;
		}

		@Override
		protected void onClick() {
			super.onClick();
			ModRegistry.setEnabled(mod.id, checked());
		}

		@Override
		protected boolean onLongClick() {
			// Origin guard at the UI layer mirrors ModInstaller.removeMod's: only EXTERNAL rows
			// are deletable. Builtin rows explain why long-press does nothing (teaches the gesture).
			if (mod.origin != ModManifest.Origin.EXTERNAL) {
				GameScene.show(new WndMessage(txt("builtin_no_uninstall")));
				return true;
			}
			GameScene.show(new WndOptions(
					txt("uninstall_title"),
					txtf("uninstall_confirm", mod.name),
					txt("uninstall_btn"), txt("cancel")) {
				@Override
				protected void onSelect(int index) {
					if (index != 0) return;   // cancel
					// removeMod is synchronous and invokes the callback on this (render) thread.
					ModInstaller.removeMod(mod.id, new ModImporter.ImportCallback() {
						@Override public void onSuccess(String id) {
							refreshAfterModChange();
						}
						@Override public void onError(String code) {
							GameScene.show(new WndMessage(txtf("err_uninstall", code)));
						}
						@Override public void onCancel() { /* no-op */ }
					});
				}
			});
			return true;
		}
	}
}
