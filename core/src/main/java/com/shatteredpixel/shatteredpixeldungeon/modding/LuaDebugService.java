package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
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
 *   <li><b>Lua: 给 m58 调试包</b> — demo_m58 verification bundle: drop {@code m58_test_weapon}
 *       + attach combat_hook_demo/shield_demo/mana_demo/tint_demo to the hero (M6d/M7a-d/M8b-c).
 *       Requires {@code demo_m58} enabled so the Lua definitions are registered.</li>
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
	private static final String M58_PACK_BTN = "Lua: 给 m58 调试包 (debug)";

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

		// demo_m58 verification pack: weapon + 4 hero-side buffs.
		RedButton m58Pack = new RedButton(M58_PACK_BTN) {
			@Override
			protected void onClick() {
				wnd.hide();
				giveM58DebugPack(Dungeon.hero);
			}
		};
		wnd.addButton(m58Pack);
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

	/**
	 * demo_m58 verification pack: drop {@code m58_test_weapon} (M6d item) + attach the four
	 * hero-side demo buffs (combat_hook_demo M7a/b, shield_demo M8b/c, mana_demo M7d,
	 * tint_demo M8c). No-op log if {@code demo_m58} disabled (registries empty).
	 */
	public static void giveM58DebugPack(Hero hero) {
		if (hero == null) {
			Gdx.app.error(TAG, "giveM58DebugPack: hero is null (no active run?)");
			return;
		}
		Item weapon = LuaItemRegistry.createWeapon("m58_test_weapon");
		if (weapon == null) {
			Gdx.app.error(TAG, "giveM58DebugPack: 'm58_test_weapon' not registered "
					+ "(enable demo_m58 and restart)");
		} else if (!weapon.collect()) {
			Gdx.app.error(TAG, "giveM58DebugPack: weapon.collect() returned false (backpack full?)");
		}
		String[] buffIds = {"combat_hook_demo", "shield_demo", "mana_demo", "tint_demo"};
		for (String id : buffIds) {
			if (!LuaBuffRegistry.contains(id)) {
				Gdx.app.error(TAG, "giveM58DebugPack: buff '" + id + "' not registered (demo_m58 disabled?)");
				continue;
			}
			LuaBuff lb = LuaBuffRegistry.create(id);
			if (lb == null) {
				Gdx.app.error(TAG, "giveM58DebugPack: create('" + id + "') returned null");
				continue;
			}
			lb.attachTo(hero);
		}
	}
}
