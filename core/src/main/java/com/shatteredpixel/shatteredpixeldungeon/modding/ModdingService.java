package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.watabou.utils.DeviceCompat;

import java.util.Locale;

/**
 * Single-point hook into {@link WndGame} for the M5b mod-toggle UI. Adds a "Mods" button that opens
 * {@link WndModManager}. Mirrors {@code SaveSlotService.addMenuButtons} (single-point fork hook in
 * the same constructor) and {@code LuaDebugService.addMenuButton} (debug-only gating).
 *
 * <p>The whole entry is gated on {@link DeviceCompat#isDebug()} so nothing here ships in release —
 * the mod UI is M5-experimental and must not pollute the release in-game menu (R7). Labels are
 * hardcoded ZH/EN because {@link com.shatteredpixel.shatteredpixeldungeon.messages.Messages#get}
 * does not reliably resolve keys for fork classes (see CLAUDE.md).
 */
public final class ModdingService {

	private static final String MENU_MODS_ZH = "模组管理";
	private static final String MENU_MODS_EN = "Mods";

	private static final boolean LANG_ZH =
			Locale.getDefault().getLanguage().equalsIgnoreCase("zh");

	private ModdingService() {}

	/** Hook called from {@link WndGame}; no-op unless this is a debug build (R7). */
	public static void addMenuButtons(WndGame wnd) {
		if (!DeviceCompat.isDebug()) return;

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
