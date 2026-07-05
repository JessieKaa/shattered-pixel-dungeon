package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.modding.annotations.LuaInterface;
import com.watabou.utils.Bundle;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * A weapon whose attributes (name/desc/tier/image) come from a Lua table, and
 * whose behaviour hooks (attackProc/onEquip/onDeactivate) call back into that
 * table.
 *
 * <p>M2 adds three optional Lua callbacks. Each is dispatched through
 * {@link LuaItemCallbacks} and is best-effort: a missing function field, a Lua
 * error, or a bad return value falls back to upstream {@link MeleeWeapon}
 * behaviour so a broken script never breaks combat or equip.
 *
 * <ul>
 *   <li><b>attackProc(attackerId, defenderId, baseDamage)</b> — invoked after
 *       {@code super.proc} has already run the upstream enchant/HolyWeapon/Smite
 *       /ID chain. Lua may return a number to override the damage; nil or a
 *       non-number keeps {@code base}. Returning {@code base} from Lua is a
 *       no-op.</li>
 *   <li><b>onEquip(heroId)</b> — invoked from {@link #activate} only on a real
 *       equip path, not during save restore (guarded by
 *       {@code Belongings.bundleRestoring}, which SPD also uses at Item.java:617
 *       to suppress side effects on load).</li>
 *   <li><b>onDeactivate(heroId)</b> — invoked from {@link #doUnequip} only when
 *       the upstream unequip actually succeeded (cursed items return false and
 *       do not fire the callback).</li>
 * </ul>
 *
 * <p>Lua never receives a {@link Char} object — only an int {@code id()}
 * (D3 option B), keeping the M1 sandbox boundary intact.
 *
 * <p>Persistence: the default {@code Item.storeInBundle} does not save
 * name/desc/tier/image, and Bundle restore needs a no-arg constructor. So we
 * stash the Lua id and re-hydrate the rest from {@link LuaItemRegistry} on
 * restore (the engine has already run by the time belongings are restored).
 */
public class LuaItem extends MeleeWeapon {

	private static final String LUA_ITEM_ID = "lua_item_id";

	private String luaItemId;
	private String nameStr = "???";
	private String descStr = "";

	/** Required for {@code Reflection.newInstance} during Bundle restore. */
	public LuaItem() {
		super();
	}

	public LuaItem(LuaTable tbl) {
		super();
		hydrate(tbl);
	}

	private void hydrate(LuaTable tbl) {
		luaItemId = tbl.get("id").checkjstring();
		nameStr = tbl.get("name").checkjstring();
		descStr = tbl.get("desc").optjstring("");
		tier = tbl.get("tier").checkint();
		// M2: image is optional (synced with LuaEngine.register_item, which no
		// longer requires it). A missing image falls back to 0 rather than
		// throwing at create-time.
		image = tbl.get("image").optint(0);
	}

	private LuaTable luaTable() {
		return luaItemId == null ? null : LuaItemRegistry.getTable(luaItemId);
	}

	@Override
	public int proc(Char attacker, Char defender, int damage) {
		// Run the upstream proc chain first (enchantments, HolyWeapon, Smite,
		// identification progress) so a Lua weapon keeps vanilla weapon semantics.
		int base = super.proc(attacker, defender, damage);
		LuaTable tbl = luaTable();
		if (tbl == null) return base;
		return LuaItemCallbacks.callOptInt(tbl, "attackProc", base,
				LuaValue.valueOf(attacker.id()),
				LuaValue.valueOf(defender.id()),
				LuaValue.valueOf(base));
	}

	@Override
	public void activate(Char ch) {
		super.activate(ch);
		// Belongings.restoreFromBundle re-invokes activate() on already-equipped
		// weapons during save load. Suppress the Lua callback there so onEquip
		// side effects (heal/buff/damage/GLog) don't fire every load.
		if (Belongings.bundleRestoring) return;
		if (!(ch instanceof Hero)) return;
		LuaTable tbl = luaTable();
		if (tbl == null) return;
		LuaItemCallbacks.callOpt(tbl, "onEquip", LuaValue.valueOf(ch.id()));
	}

	@Override
	public boolean doUnequip(Hero hero, boolean collect, boolean single) {
		boolean ok = super.doUnequip(hero, collect, single);
		if (!ok) return false; // cursed / blocked — upstream state unchanged
		LuaTable tbl = luaTable();
		if (tbl != null) {
			LuaItemCallbacks.callOpt(tbl, "onDeactivate", LuaValue.valueOf(hero.id()));
		}
		return true;
	}

	@Override
	@LuaInterface
	public String name() {
		return nameStr;
	}

	@Override
	@LuaInterface
	public String desc() {
		return descStr;
	}

	@Override
	public void storeInBundle(Bundle bundle) {
		super.storeInBundle(bundle);
		if (luaItemId != null) bundle.put(LUA_ITEM_ID, luaItemId);
	}

	@Override
	public void restoreFromBundle(Bundle bundle) {
		super.restoreFromBundle(bundle);
		if (bundle.contains(LUA_ITEM_ID)) {
			luaItemId = bundle.getString(LUA_ITEM_ID);
			LuaTable tbl = LuaItemRegistry.getTable(luaItemId);
			if (tbl != null) {
				hydrate(tbl);
			} else {
				// Engine init must have failed or the script was removed; the
				// item is in a degraded state but we avoid crashing the load.
				nameStr = "??? (" + luaItemId + ")";
			}
		}
	}
}
