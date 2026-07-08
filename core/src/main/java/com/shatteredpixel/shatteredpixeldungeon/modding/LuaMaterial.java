package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.watabou.utils.Bundle;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.ArrayList;

/**
 * M11b: Lua-defined material/food items. Adds the minimal action layer needed
 * to make scripted foods usable: defaultAction, actions, execute(EAT/USE),
 * onThrow, and burn/freeze transform. Action economy (detach/spend/busy) is
 * owned by Java, never exposed to Lua.
 */
public class LuaMaterial extends Item {

	private static final String LUA_ITEM_ID = "lua_item_id";

	private LuaTable table;
	private String luaItemId;
	private String nameStr = "???";
	private String descStr = "";
	private int priceValue = 0;

	private String defaultActionStr = null;
	private boolean explicitActions;
	private ArrayList<String> actionList;
	private boolean hasOnUse;

	private float energy = 0f;

	private String burnTransformId;
	private String freezeTransformId;
	private String poisonTransformId;

	public static final String AC_EAT = "EAT";
	public static final String AC_USE = "USE";

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
		this.table = tbl;
		luaItemId = tbl.get("id").checkjstring();
		nameStr = tbl.get("name").checkjstring();
		LuaValue desc = tbl.get("desc");
		if (desc.isnil()) desc = tbl.get("info");
		descStr = desc.optjstring("");
		image = tbl.get("image").optint(0);
		priceValue = Math.max(0, tbl.get("price").optint(0));
		stackable = tbl.get("stackable").optboolean(true);

		defaultActionStr = tbl.get("defaultAction").optjstring(null);
		if (defaultActionStr == null) defaultActionStr = tbl.get("default_action").optjstring(null);

		actionList = new ArrayList<>();
		LuaValue actions = tbl.get("actions");
		if (actions.istable()) {
			explicitActions = true;
			LuaValue key = LuaValue.NIL;
			while (true) {
				Varargs nv = actions.next(key);
				key = nv.arg1();
				LuaValue v = nv.arg(2);
				if (key.isnil()) break;
				if (v.isstring()) actionList.add(v.tojstring());
			}
		}

		LuaValue onUse = tbl.get("onUse");
		hasOnUse = onUse.isfunction() || (!onUse.isnil() && tbl.get("execute").isfunction());

		energy = (float) tbl.get("energy").optdouble(0d);

		burnTransformId = optString(tbl, "burnTransform", "burn_transform");
		freezeTransformId = optString(tbl, "freezeTransform", "freeze_transform");
		poisonTransformId = optString(tbl, "poisonTransform", "poison_transform");
	}

	private static String optString(LuaTable tbl, String camel, String snake) {
		LuaValue v = tbl.get(camel);
		if (!v.isnil() && v.isstring()) return v.tojstring();
		v = tbl.get(snake);
		if (!v.isnil() && v.isstring()) return v.tojstring();
		return null;
	}

	public String luaItemId() {
		return luaItemId;
	}

	public LuaTable table() {
		return table;
	}

	public float energy() {
		return energy;
	}

	@Override
	public String defaultAction() {
		if (defaultActionStr != null) return defaultActionStr;
		return super.defaultAction();
	}

	@Override
	public ArrayList<String> actions(Hero hero) {
		ArrayList<String> actions = super.actions(hero);
		if (explicitActions) {
			actions.addAll(actionList);
		} else if (AC_EAT.equals(defaultActionStr)) {
			actions.add(AC_EAT);
		} else if (AC_USE.equals(defaultActionStr)) {
			actions.add(AC_USE);
		} else if (hasOnUse) {
			actions.add(AC_USE);
		}
		return actions;
	}

	@Override
	public String actionName(String action, Hero hero) {
		if (AC_EAT.equals(action)) return "Eat";
		if (AC_USE.equals(action)) return "Use";
		return super.actionName(action, hero);
	}

	@Override
	public void execute(Hero hero, String action) {
		if (action == null) return;

		// Base class sets curUser/curItem and dispatches AC_DROP/AC_THROW.
		// GameScene.cancel() throws in headless tests (no scene), so swallow that
		// case without affecting real gameplay where the scene is always present.
		try {
			super.execute(hero, action);
		} catch (NullPointerException e) {
			if (Dungeon.level == null) {
				// headless test path: continue with LuaMaterial-specific dispatch
			} else {
				throw e;
			}
		}

		if (action.equals(AC_EAT)) {
			Item consumed = detach(hero.belongings.backpack);
			if (consumed == null) return;

			if (table != null) {
				LuaItemCallbacks.callOpt(table, "onEat",
						LuaItemCallbacks.arg(hero.id()),
						LuaValue.valueOf(luaItemId));
				LuaItemCallbacks.callOpt(table, "onUse",
						LuaItemCallbacks.arg(hero.id()),
						LuaValue.valueOf(luaItemId),
						LuaValue.valueOf(AC_EAT));
			}

			if (hero.sprite != null) hero.sprite.operate(hero.pos);
			hero.busy();
			// Hunger satisfaction is Java-owned to prevent Lua from breaking turn economy
			if (energy > 0) {
				Buff.affect(hero, Hunger.class).satisfy(energy);
			}
			// Minimal Food FX if the item claims to be food
			if (energy > 0 && hero.sprite != null) {
				com.shatteredpixel.shatteredpixeldungeon.effects.SpellSprite.show(hero, com.shatteredpixel.shatteredpixeldungeon.effects.SpellSprite.FOOD);
			}
			hero.spend(Food.TIME_TO_EAT);

		} else if (action.equals(AC_USE)) {
			Item consumed = detach(hero.belongings.backpack);
			if (consumed == null) return;

			if (table != null) {
				LuaItemCallbacks.callOpt(table, "onUse",
						LuaItemCallbacks.arg(hero.id()),
						LuaValue.valueOf(luaItemId),
						LuaValue.valueOf(AC_USE));
			}

			hero.busy();
			hero.spend(1f);
		}
		// DROP/THROW are already dispatched by the super.execute call above.
	}

	@Override
	public void onThrow(int cell) {
		if (Dungeon.level != null) {
			super.onThrow(cell);
		}
		if (table != null) {
			LuaItemCallbacks.callOpt(table, "onThrow",
					LuaValue.valueOf(cell),
					LuaValue.valueOf(luaItemId));
		}
	}

	public Item burnTransform() {
		return transformTo(burnTransformId);
	}

	public Item freezeTransform() {
		return transformTo(freezeTransformId);
	}

	public String poisonTransformId() {
		return poisonTransformId;
	}

	private Item transformTo(String targetId) {
		if (targetId == null) return null;
		Item created = LuaItemRegistry.createItem(targetId);
		if (created == null) return null;
		created.quantity(quantity());
		return created;
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
	@com.shatteredpixel.shatteredpixeldungeon.modding.annotations.LuaInterface
	public String name() {
		return nameStr;
	}

	@Override
	@com.shatteredpixel.shatteredpixeldungeon.modding.annotations.LuaInterface
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
