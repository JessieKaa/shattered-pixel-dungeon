package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.watabou.utils.DeviceCompat;

/**
 * Single-point hook into {@link WndGame} that hands the player the Lua-defined
 * test sword. Debug-only: the {@link DeviceCompat#isDebug()} guard keeps the
 * button out of release builds, and the item is never registered with
 * {@code Generator.Category}, so the original drop pool is untouched (C3).
 *
 * Label is hardcoded on purpose — Messages.get does not reliably resolve keys
 * for fork classes (see CLAUDE.md), and this button only shows in INDEV builds.
 */
public final class LuaDebugService {

	private static final String TAG = "LuaDebugService";
	private static final String TEST_SWORD_ID = "test_sword";
	private static final String BUTTON_LABEL = "Lua: 给测试剑 (debug)";

	private LuaDebugService() { }

	/** Hook called from {@link WndGame}; no-op unless this is an INDEV build. */
	public static void addMenuButton(WndGame wnd) {
		if (!DeviceCompat.isDebug()) return;

		RedButton btn = new RedButton(BUTTON_LABEL) {
			@Override
			protected void onClick() {
				wnd.hide();
				giveTestItem(Dungeon.hero);
			}
		};
		wnd.addButton(btn);
	}

	/** Create the registered test sword and drop it into the hero's backpack. Defensive: never throws. */
	public static void giveTestItem(Hero hero) {
		if (hero == null) {
			Gdx.app.error(TAG, "giveTestItem: hero is null (no active run?)");
			return;
		}
		LuaItem item = LuaItemRegistry.create(TEST_SWORD_ID);
		if (item == null) {
			Gdx.app.error(TAG, "giveTestItem: '" + TEST_SWORD_ID
					+ "' not registered (Lua init failed or script missing)");
			return;
		}
		if (!item.collect()) {
			Gdx.app.error(TAG, "giveTestItem: collect() returned false (backpack full?)");
		}
	}
}
