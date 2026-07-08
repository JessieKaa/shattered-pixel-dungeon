package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;

import java.util.List;
import java.util.Locale;

/**
 * M13b discovery window: lists every registered custom level and lets the player enter one.
 * Built on {@link WndOptions} so the vertical-button list, sizing, and click handling come for
 * free; the only behaviour added is a confirm-then-enter step — entering saves the run and swaps
 * the level, so a second tap is warranted before committing.
 *
 * <p>Only <em>registered</em> levels appear: {@link LuaLevelService#listEnterableLevels()} reads
 * the {@code register_level} set, so unvetted bare {@code mods/levels/<id>.json} assets stay
 * unreachable from this UI (C3). The {@code WndGame} button that opens this window is itself
 * hidden when the list is empty, so the constructor's empty-list branch is defensive only.
 *
 * <p>Labels are hardcoded ZH/EN. The fork's {@code Messages.get} is unreliable across the
 * modding package (see {@code WndSaveSlotSelect.txt()}), so this window follows the same
 * convention rather than depending on a properties file.
 */
public class WndCustomLevels extends WndOptions {

	private static final boolean LANG_ZH =
			Locale.getDefault().getLanguage().equalsIgnoreCase("zh");

	private final List<String> ids;

	public WndCustomLevels() {
		this(LuaLevelService.listEnterableLevels());
	}

	private WndCustomLevels(List<String> ids) {
		super(titleText(), messageText(ids), namesOf(ids));
		this.ids = ids;
	}

	@Override
	protected void onSelect(int index) {
		if (index < 0 || index >= ids.size()) return;
		final String id = ids.get(index);
		final String name = LuaLevelService.levelDisplayName(id);
		GameScene.show(new WndOptions(
				titleText(),
				LANG_ZH ? "进入「" + name + "」?" : "Enter \"" + name + "\"?",
				LANG_ZH ? "进入" : "Enter",
				LANG_ZH ? "取消" : "Cancel") {
			@Override
			protected void onSelect(int choice) {
				if (choice == 0) {
					LuaLevelService.enterLevel(id);
				}
			}
		});
	}

	private static String titleText() {
		return LANG_ZH ? "自定义关卡" : "Custom Levels";
	}

	private static String messageText(List<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return LANG_ZH ? "无自定义关卡" : "No custom levels";
		}
		return LANG_ZH ? "选择一个关卡进入" : "Choose a level to enter";
	}

	private static String[] namesOf(List<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return new String[] { LANG_ZH ? "（空）" : "(none)" };
		}
		String[] names = new String[ids.size()];
		for (int i = 0; i < ids.size(); i++) {
			names[i] = LuaLevelService.levelDisplayName(ids.get(i));
		}
		return names;
	}
}
