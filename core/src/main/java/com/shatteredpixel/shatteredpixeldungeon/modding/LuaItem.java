package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.modding.annotations.LuaInterface;
import com.watabou.utils.Bundle;

import org.luaj.vm2.LuaTable;

/**
 * A weapon whose attributes (name/desc/tier/image) come from a Lua table.
 *
 * M0 reuses an existing {@link MeleeWeapon} behaviour (min/max/STRReq are all
 * derived from {@code tier}) and only overrides display + persistence. Image
 * points at an existing ItemSpriteSheet entry — M0 does not paint new art.
 *
 * Persistence: the default {@code Item.storeInBundle} does not save
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
		image = tbl.get("image").checkint();
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
