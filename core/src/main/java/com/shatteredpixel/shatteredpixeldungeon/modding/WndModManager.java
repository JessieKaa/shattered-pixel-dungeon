package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.IconTitle;
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
 */
public class WndModManager extends Window {

	private static final int WIDTH = 130;
	private static final int HEIGHT = 144;
	private static final int MARGIN = 2;
	private static final int CHECK_HEIGHT = 18;
	private static final int CHECK_GAP = 1;
	private static final int DESC_GAP = 4;
	private static final int HINT_HEIGHT = 18;

	private static final boolean LANG_ZH =
			Locale.getDefault().getLanguage().equalsIgnoreCase("zh")
			|| (Messages.lang() != null && Messages.lang().code() != null
			    && Messages.lang().code().toLowerCase(Locale.ENGLISH).startsWith("zh"));

	private static final Map<String, String> TXT_ZH = new HashMap<>();
	private static final Map<String, String> TXT_EN = new HashMap<>();
	static {
		TXT_ZH.put("title", "模组管理");
		TXT_ZH.put("empty", "未发现模组。");
		TXT_ZH.put("hint", "更改在重启游戏后生效。");
		TXT_ZH.put("origin_builtin", "[内建]");
		TXT_ZH.put("origin_external", "[外部]");
		TXT_EN.put("title", "Mods");
		TXT_EN.put("empty", "No mods found.");
		TXT_EN.put("hint", "Changes apply after restart.");
		TXT_EN.put("origin_builtin", "[built-in]");
		TXT_EN.put("origin_external", "[external]");
	}

	private static String txt(String key) {
		Map<String, String> m = LANG_ZH ? TXT_ZH : TXT_EN;
		String s = m.get(key);
		return s != null ? s : key;
	}

	public WndModManager() {
		super();
		resize(WIDTH, HEIGHT);

		IconTitle title = new IconTitle(Icons.get(Icons.SHPX), txt("title"));
		title.setRect(0, 0, WIDTH, 0);
		add(title);

		int hintTop = HEIGHT - HINT_HEIGHT;
		int listTop = (int) title.bottom() + MARGIN * 2;

		ScrollPane pane = new ScrollPane(new Component());
		add(pane);
		pane.setRect(0, listTop, WIDTH, hintTop - listTop);
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

		RenderedTextBlock hint = PixelScene.renderTextBlock(txt("hint"), 6);
		hint.maxWidth(WIDTH - MARGIN * 2);
		hint.hardlight(0xFFFF88);
		hint.setPos(MARGIN, hintTop);
		add(hint);
	}

	/**
	 * A {@link CheckBox} bound to one mod id. {@link CheckBox#onClick()} auto-toggles the checked
	 * state; this override runs that first, then persists the new state via {@link ModRegistry}.
	 */
	private static final class ModCheckBox extends CheckBox {
		private final String modId;

		ModCheckBox(ModManifest mod) {
			super(mod.name + " v" + mod.version + " " + originTag(mod));
			this.modId = mod.id;
		}

		/** M12a: localized origin badge so players can tell packaged mods from external ones.
		 *  Null origin (un-scanned manifest) renders as builtin — the safe default. */
		private static String originTag(ModManifest mod) {
			boolean external = mod.origin == ModManifest.Origin.EXTERNAL;
			return txt(external ? "origin_external" : "origin_builtin");
		}

		@Override
		protected void onClick() {
			super.onClick();
			ModRegistry.setEnabled(modId, checked());
		}
	}
}
