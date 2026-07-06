package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.modding.annotations.LuaInterface;
import com.watabou.utils.Bundle;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

public class LuaMaterial extends Item {

	private static final String LUA_ITEM_ID = "lua_item_id";

	private String luaItemId;
	private String nameStr = "???";
	private String descStr = "";
	private int priceValue = 0;

	{
		stackable = true;
	}

	public LuaMaterial() {
		super();
	}

	public LuaMaterial(LuaTable tbl) {
		super();
		hydrate(tbl);
		quantity(Math.max(1, tbl.get("quantity").optint(1)));
	}

	private void hydrate(LuaTable tbl) {
		luaItemId = tbl.get("id").checkjstring();
		nameStr = tbl.get("name").checkjstring();
		LuaValue desc = tbl.get("desc");
		if (desc.isnil()) desc = tbl.get("info");
		descStr = desc.optjstring("");
		image = tbl.get("image").optint(0);
		priceValue = Math.max(0, tbl.get("price").optint(0));
		stackable = tbl.get("stackable").optboolean(true);
	}

	@Override
	public boolean isUpgradable() {
		return false;
	}

	@Override
	public boolean isIdentified() {
		return true;
	}

	@Override
	public boolean isSimilar(Item item) {
		if (!super.isSimilar(item) || !(item instanceof LuaMaterial)) return false;
		return luaItemId != null && luaItemId.equals(((LuaMaterial) item).luaItemId);
	}

	@Override
	public int value() {
		return priceValue * quantity();
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
				nameStr = "??? (" + luaItemId + ")";
			}
		}
	}
}
