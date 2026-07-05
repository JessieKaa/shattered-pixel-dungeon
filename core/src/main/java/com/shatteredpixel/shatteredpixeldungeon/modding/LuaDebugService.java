package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.watabou.utils.DeviceCompat;

/**
 * Single-point hook into {@link WndGame} for the Lua/modding debug surface. Debug-only:
 * every entry is gated on {@link DeviceCompat#isDebug()} so nothing here ships in release,
 * and none of the actions touch the vanilla loot/spawn pool (C3).
 *
 * <p>Buttons:
 * <ul>
 *   <li><b>Lua: 给测试剑</b> — drop the registered test sword into the backpack (M1).</li>
 *   <li><b>Lua: 进入/离开 Test SafeZone</b> — enter/leave the M4a JSON level. Label flips
 *       based on whether the hero is currently inside a {@link DataDrivenLevel}.</li>
 * </ul>
 *
 * <p>Labels are hardcoded on purpose — Messages.get does not reliably resolve keys for fork
 * classes (see CLAUDE.md), and these buttons only show in INDEV builds.
 */
public final class LuaDebugService {

	private static final String TAG = "LuaDebugService";
	private static final String TEST_SWORD_ID = "test_sword";
	private static final String TEST_SAFEZONE_ID = "test_safezone";
	private static final String SWORD_BTN = "Lua: 给测试剑 (debug)";
	private static final String ENTER_SZ_BTN = "Lua: 进入 Test SafeZone (debug)";
	private static final String LEAVE_SZ_BTN = "Lua: 离开 SafeZone (debug)";

	private LuaDebugService() { }

	/** Hook called from {@link WndGame}; no-op unless this is an INDEV build. */
	public static void addMenuButton(WndGame wnd) {
		if (!DeviceCompat.isDebug()) return;

		RedButton sword = new RedButton(SWORD_BTN) {
			@Override
			protected void onClick() {
				wnd.hide();
				giveTestItem(Dungeon.hero);
			}
		};
		wnd.addButton(sword);

		// M4a: JSON SafeZone. Label depends on whether we're already inside one.
		final boolean inDataLevel = LuaLevelService.inDataLevel();
		RedButton safeZone = new RedButton(inDataLevel ? LEAVE_SZ_BTN : ENTER_SZ_BTN) {
			@Override
			protected void onClick() {
				wnd.hide();
				if (inDataLevel) {
					LuaLevelService.leaveLevel();
				} else {
					LuaLevelService.enterLevel(TEST_SAFEZONE_ID);
				}
			}
		};
		wnd.addButton(safeZone);
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
