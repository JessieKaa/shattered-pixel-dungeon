package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;

import java.util.Locale;

/**
 * Single-point hook into {@link WndGame} for the M5b mod-toggle UI. Adds a "Mods" button that opens
 * {@link WndModManager}. Mirrors {@code SaveSlotService.addMenuButtons} (single-point fork hook in
 * the same constructor).
 *
 * <p>Ships in release as of M9 (modding is a core fork feature — players must be able to toggle
 * mods). M5 originally gated this debug-only (R7); M9 release opens it. Labels are hardcoded
 * ZH/EN because {@link com.shatteredpixel.shatteredpixeldungeon.messages.Messages#get}
 * does not reliably resolve keys for fork classes (see CLAUDE.md).
 */
public final class ModdingService {

	private static final String MENU_MODS_ZH = "模组管理";
	private static final String MENU_MODS_EN = "Mods";

	private static final boolean LANG_ZH =
			Locale.getDefault().getLanguage().equalsIgnoreCase("zh");

	private ModdingService() {}

	/** Hook called from {@link WndGame}; adds the Mods button (M9: ships in release). */
	public static void addMenuButtons(WndGame wnd) {
		RedButton mods = new RedButton(LANG_ZH ? MENU_MODS_ZH : MENU_MODS_EN) {
			@Override
			protected void onClick() {
				wnd.hide();
				GameScene.show(new WndModManager());
			}
		};
		wnd.addButton(mods);
	}
}
