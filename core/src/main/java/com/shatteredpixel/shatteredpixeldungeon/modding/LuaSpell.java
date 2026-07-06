package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.modding.annotations.LuaInterface;
import com.watabou.utils.Bundle;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;

/**
 * A consumable {@link Item} whose attributes (name/desc/image) come from a Lua
 * table and whose use hook ({@code onUse(heroId)}) calls back into that table.
 *
 * <p>M3d mirrors the M2 {@code LuaItem} pattern (super-then-Lua + charId) but
 * applies it to {@link Item#execute} instead of weapon {@code proc}. Using a
 * consumable calls {@link Item#detach} (which the base class finalises to
 * handle quantity itself — {@code quantity==1} removes the stack,
 * {@code quantity>1} splits one off), then fires {@code onUse(heroId)}.
 *
 * <ul>
 *   <li><b>onUse(heroId)</b> — best-effort via {@link LuaItemCallbacks#callOpt}.
 *       A missing field, Lua error, or bad arg never blocks consumption: the
 *       item is already detached by the time the callback runs (Food.java:76
 *       applies effects after detach for the same reason).</li>
 * </ul>
 *
 * <p>Lua never receives a {@link com.shatteredpixel.shatteredpixeldungeon.actors.Char}
 * object — only an int {@code id()} (D3 option B), keeping the M1 sandbox
 * boundary intact.
 *
 * <p>Persistence mirrors {@code LuaItem}: the default {@code Item.storeInBundle}
 * persists quantity/level/cursed but not name/desc/image, and Bundle restore
 * needs a no-arg constructor. So we stash the Lua id and re-hydrate the rest
 * from {@link LuaSpellRegistry} on restore.
 *
 * <p>{@link #isSimilar} is overridden because the base implementation only
 * compares {@code getClass()} — without this, two LuaSpells with different ids
 * (e.g. a heal potion and a fireball scroll) would merge into one stack and
 * silently corrupt both. We additionally require matching {@code luaItemId}.
 */
public class LuaSpell extends Item {

	private static final String LUA_SPELL_ID = "lua_spell_id";

	public static final String AC_USE = "USE";

	/** Time a spell consumes when used (matches Scroll.TIME_TO_READ). */
	public static final float TIME_TO_USE = 1f;

	private String luaSpellId;
	private String nameStr = "???";
	private String descStr = "";
	private float castTime = TIME_TO_USE;
	private int spellCost = 0;
	private String targeting = "self";

	{
		stackable = true;
		defaultAction = AC_USE;
	}

	/** Required for {@code Reflection.newInstance} during Bundle restore and {@link Item#split}. */
	public LuaSpell() {
		super();
	}

	public LuaSpell(LuaTable tbl) {
		super();
		hydrate(tbl);
	}

	private void hydrate(LuaTable tbl) {
		luaSpellId = tbl.get("id").checkjstring();
		nameStr = tbl.get("name").checkjstring();
		descStr = tbl.get("desc").optjstring("");
		image = tbl.get("image").optint(0);
		// M6d: optional Remished-style metadata. Cast time overrides the default
		// 1f spend; spellCost/targeting are stored for future UI and do not affect
		// consumption. Restored via the same Lua table after save/load (the table
		// is the source of truth, never the bundle).
		castTime = (float) tbl.get("castTime").optdouble(TIME_TO_USE);
		spellCost = tbl.get("spellCost").optint(0);
		targeting = tbl.get("targeting").optjstring("self");
	}

	private LuaTable luaTable() {
		return luaSpellId == null ? null : LuaSpellRegistry.getTable(luaSpellId);
	}

	@Override
	public ArrayList<String> actions(Hero hero) {
		ArrayList<String> actions = super.actions(hero);
		actions.add(AC_USE);
		return actions;
	}

	@Override
	public void execute(Hero hero, String action) {
		// Base class sets curUser/curItem and dispatches AC_DROP/AC_THROW.
		super.execute(hero, action);
		if (action.equals(AC_USE)) {
			// Detach first (Food.java:76): even if the Lua callback throws,
			// the item is consumed. detach() handles quantity internally
			// (quantity==1 → detachAll, quantity>1 → split(1)) — never hand-write quantity--.
			detach(hero.belongings.backpack);
			LuaTable tbl = luaTable();
			if (tbl != null) {
				LuaItemCallbacks.callOpt(tbl, "onUse", LuaValue.valueOf(hero.id()));
			}
			hero.spend(castTime);
			hero.busy();
		}
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
		if (!super.isSimilar(item) || !(item instanceof LuaSpell)) return false;
		return luaSpellId != null && luaSpellId.equals(((LuaSpell) item).luaSpellId);
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

	public float castTime() {
		return castTime;
	}

	public int spellCost() {
		return spellCost;
	}

	public String targeting() {
		return targeting;
	}

	@Override
	public void storeInBundle(Bundle bundle) {
		super.storeInBundle(bundle);
		if (luaSpellId != null) bundle.put(LUA_SPELL_ID, luaSpellId);
	}

	@Override
	public void restoreFromBundle(Bundle bundle) {
		super.restoreFromBundle(bundle);
		if (bundle.contains(LUA_SPELL_ID)) {
			luaSpellId = bundle.getString(LUA_SPELL_ID);
			LuaTable tbl = LuaSpellRegistry.getTable(luaSpellId);
			if (tbl != null) {
				hydrate(tbl);
			} else {
				nameStr = "??? (" + luaSpellId + ")";
			}
		}
	}
}
