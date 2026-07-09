package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.modding.annotations.LuaInterface;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.watabou.utils.Bundle;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A weapon whose attributes (name/desc/tier/image) come from a Lua table, and
 * whose behaviour hooks (attackProc/onEquip/onDeactivate/execute/onUse) call
 * back into that table.
 *
 * <p>M2 adds three optional Lua callbacks. Each is dispatched through
 * {@link LuaItemCallbacks} and is best-effort: a missing function field, a Lua
 * error, or a bad return value falls back to upstream {@link MeleeWeapon}
 * behaviour so a broken script never breaks combat or equip.
 *
 * <ul>
 *   <li><b>attackProc(attackerId, defenderId, baseDamage, state)</b> — invoked
 *       after {@code super.proc} has already run the upstream enchant/HolyWeapon
 *       /Smite/ID chain. Lua may return a number to override the damage; nil or
 *       a non-number keeps {@code base}. Returning {@code base} from Lua is a
 *       no-op.</li>
 *   <li><b>onEquip(heroId, state)</b> — invoked from {@link #activate} only on
 *       a real equip path, not during save restore (guarded by
 *       {@code Belongings.bundleRestoring}).</li>
 *   <li><b>onDeactivate(heroId, state)</b> — invoked from {@link #doUnequip}
 *       only when the upstream unequip actually succeeded (cursed items return
 *       false and do not fire the callback).</li>
 *   <li><b>execute(heroId, action, state)</b> / <b>onUse(heroId, action,
 *       state)</b> — M11c: dispatched for non-upstream actions. {@code execute}
 *       takes precedence; if it is absent, {@code onUse} is tried for backward
 *       compatibility with the M11b material naming convention.</li>
 *   <li><b>glowing(state)</b> — M11c: optional callback returning a
 *       {@code {color=0xRRGGBB, period=seconds}} table to drive
 *       {@link ItemSprite.Glowing}; nil or unparseable returns keep the item
 *       un-glowing.</li>
 * </ul>
 *
 * <p>Lua never receives a {@link Char} object — only an int {@code id()}
 * (D3 option B), keeping the M1 sandbox boundary intact.
 *
 * <p>Persistence: the default {@code Item.storeInBundle} does not save
 * name/desc/tier/image, and Bundle restore needs a no-arg constructor. So we
 * stash the Lua id and re-hydrate the rest from {@link LuaItemRegistry} on
 * restore (the engine has already run by the time belongings are restored).
 * M11c additionally persists the per-instance {@link #state} table using the
 * same Base64 row encoding as {@link LuaBuff}.
 */
public class LuaItem extends MeleeWeapon {

	private static final String TAG = "LuaItem";
	private static final String LUA_ITEM_ID = "lua_item_id";
	private static final String LUA_ITEM_STATE = "lua_item_state";
	private static final String LUA_ITEM_DEFAULT_ACTION = "lua_item_default_action";
	private static final String LUA_ITEM_ACTIONS = "lua_item_actions";

	private String luaItemId;
	private String nameStr = "???";
	private String descStr = "";

	private String defaultActionStr = null;
	private boolean explicitActions;
	private ArrayList<String> actionList;

	/** M16a: optional standalone sprite file path relative to the owning mod dir. */
	private String spriteFile;
	/** M16a: mod id that registered this item, used to resolve spriteFile. */
	private String ownerModId;

	/** M11c: per-instance mutable state passed to Lua callbacks and persisted. */
	private LuaTable state;

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
		// M16a: optional standalone sprite file path. Stored as-is; rendering code
		// resolves it against ownerModId via ModSpriteCache.
		spriteFile = tbl.get("spriteFile").optjstring(null);
		ownerModId = tbl.get("__mod_id").optjstring(null);

		LuaValue defaultAction = tbl.get("defaultAction");
		if (defaultAction.isnil()) defaultAction = tbl.get("default_action");
		defaultActionStr = defaultAction.isstring() ? defaultAction.tojstring() : null;

		actionList = new ArrayList<>();
		LuaValue actions = tbl.get("actions");
		if (actions.istable()) {
			explicitActions = true;
			LuaValue key = LuaValue.NIL;
			while (true) {
				org.luaj.vm2.Varargs nv = ((LuaTable) actions).next(key);
				key = nv.arg1();
				LuaValue v = nv.arg(2);
				if (key.isnil()) break;
				if (v.isstring()) actionList.add(v.tojstring());
			}
		}
		state = new LuaTable();
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
				LuaValue.valueOf(base),
				state);
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
		LuaItemCallbacks.callOpt(tbl, "onEquip", LuaValue.valueOf(ch.id()), state);
	}

	@Override
	public boolean doUnequip(Hero hero, boolean collect, boolean single) {
		boolean ok = super.doUnequip(hero, collect, single);
		if (!ok) return false; // cursed / blocked — upstream state unchanged
		LuaTable tbl = luaTable();
		if (tbl != null) {
			LuaItemCallbacks.callOpt(tbl, "onDeactivate", LuaValue.valueOf(hero.id()), state);
		}
		return true;
	}

	// ---- M11c action layer ----

	@Override
	public String defaultAction() {
		LuaTable tbl = luaTable();
		if (tbl != null) {
			LuaValue field = tbl.get("defaultAction");
			if (field.isnil()) field = tbl.get("default_action");
			if (field.isfunction()) {
				try {
					LuaValue res = field.call(state);
					if (res.isstring()) return res.tojstring();
				} catch (Exception e) {
					Gdx.app.error(TAG, "defaultAction callback threw", e);
				}
			} else if (field.isstring()) {
				return field.tojstring();
			}
		}
		String parent = super.defaultAction();
		return parent != null ? parent : AC_EQUIP;
	}

	@Override
	public ArrayList<String> actions(Hero hero) {
		ArrayList<String> actions = super.actions(hero);
		if (explicitActions) {
			// M11c: Lua only contributes non-built-in actions. Every built-in
			// (DROP/THROW/EQUIP/UNEQUIP/ABILITY) is already provided by
			// super.actions when applicable to the current equip/class state, so
			// a script re-declaring one would only add a stale/duplicate entry.
			for (String a : actionList) {
				if (isLuaAction(a) && !actions.contains(a)) actions.add(a);
			}
		}
		return actions;
	}

	@Override
	public String actionName(String action, Hero hero) {
		if (explicitActions) {
			LuaTable tbl = luaTable();
			if (tbl != null) {
				LuaValue names = tbl.get("actionNames");
				if (names.istable()) {
					LuaValue name = names.get(action);
					if (name.isstring()) return name.tojstring();
				}
			}
		}
		return super.actionName(action, hero);
	}

	@Override
	public void execute(Hero hero, String action) {
		if (action == null) return;

		if (isLuaAction(action)) {
			// Lua-only action: Item.execute would only call GameScene.cancel()
			// and set curUser/curItem here (no DROP/THROW/equip branch fires).
			// cancel() NPEs without a live GameScene — and a Lua action never
			// needs the cell-selector dismissal upstream uses, so skip super
			// entirely and dispatch straight to Lua. curUser/curItem are still
			// set for upstream parity.
			curUser = hero;
			curItem = this;
			LuaTable tbl = luaTable();
			if (tbl != null) {
				LuaValue fn = tbl.get("execute");
				if (fn.isfunction()) {
					LuaItemCallbacks.callOpt(tbl, "execute",
							LuaValue.valueOf(hero.id()),
							LuaValue.valueOf(action),
							state);
				} else {
					LuaItemCallbacks.callOpt(tbl, "onUse",
							LuaValue.valueOf(hero.id()),
							LuaValue.valueOf(action),
							state);
				}
			}
			return;
		}

		// Built-in action (DROP/THROW/EQUIP/UNEQUIP/ABILITY): upstream owns it.
		// GameScene.cancel() inside super.execute throws in headless tests; the
		// Dungeon.level==null guard mirrors LuaMaterial's headless handling.
		try {
			super.execute(hero, action);
		} catch (NullPointerException e) {
			if (Dungeon.level == null) {
				// headless test path: continue
			} else {
				throw e;
			}
		}
	}

	/** M11c: whether an action string is owned by Lua rather than Item/EquipableItem/MeleeWeapon. */
	private boolean isLuaAction(String action) {
		return !AC_DROP.equals(action)
				&& !AC_THROW.equals(action)
				&& !AC_EQUIP.equals(action)
				&& !AC_UNEQUIP.equals(action)
				&& !AC_ABILITY.equals(action);
	}

	// ---- M11c glowing hook ----

	@Override
	public ItemSprite.Glowing glowing() {
		LuaTable tbl = luaTable();
		if (tbl == null) return null;
		LuaValue field = tbl.get("glowing");
		if (field.isnil()) return null;
		LuaValue res = field;
		if (field.isfunction()) {
			try {
				res = field.call(state);
			} catch (Exception e) {
				Gdx.app.error(TAG, "glowing callback threw", e);
				return null;
			}
		}
		if (res == null || res.isnil()) return null;
		if (res.istable()) {
			LuaValue color = res.get("color");
			LuaValue period = res.get("period");
			if (color.isnumber()) {
				return new ItemSprite.Glowing(color.toint(),
						period.isnumber() ? (float) period.todouble() : 1f);
			}
		} else if (res.isnumber()) {
			return new ItemSprite.Glowing(res.toint());
		}
		return null;
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

	/** M16a: optional standalone sprite file path relative to the owning mod dir. */
	public String spriteFile() {
		return spriteFile;
	}

	/** M16a: id of the mod that registered this item, used to resolve spriteFile. */
	public String ownerModId() {
		return ownerModId;
	}

	@Override
	public void storeInBundle(Bundle bundle) {
		super.storeInBundle(bundle);
		if (luaItemId != null) bundle.put(LUA_ITEM_ID, luaItemId);
		if (state != null) bundle.put(LUA_ITEM_STATE, serializeState(state));
		if (defaultActionStr != null) bundle.put(LUA_ITEM_DEFAULT_ACTION, defaultActionStr);
		if (explicitActions) bundle.put(LUA_ITEM_ACTIONS, actionList.toArray(new String[0]));
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
		if (bundle.contains(LUA_ITEM_STATE)) {
			loadState(bundle.getStringArray(LUA_ITEM_STATE));
		} else if (state == null) {
			state = new LuaTable();
		}
		if (bundle.contains(LUA_ITEM_DEFAULT_ACTION)) {
			defaultActionStr = bundle.getString(LUA_ITEM_DEFAULT_ACTION);
		}
		if (bundle.contains(LUA_ITEM_ACTIONS)) {
			explicitActions = true;
			actionList = new ArrayList<>();
			for (String s : bundle.getStringArray(LUA_ITEM_ACTIONS)) actionList.add(s);
		}
	}

	// ---- state persistence (mirrors LuaBuff, but key-typed so numeric keys
	//      round-trip as numbers instead of collapsing to string "1") ----

	private static String[] serializeState(LuaTable t) {
		List<String> out = new ArrayList<>();
		writeStateRows(t, new ArrayList<>(), out);
		return out.toArray(new String[0]);
	}

	private static void writeStateRows(LuaTable table, List<String> path, List<String> out) {
		for (org.luaj.vm2.Varargs k = table.next(LuaValue.NIL); !k.arg1().isnil(); k = table.next(k.arg1())) {
			LuaValue key = k.arg1();
			LuaValue val = k.arg(2);
			String seg = encodeKey(key);
			if (seg == null) continue;
			path.add(seg);
			if (val.istable()) {
				writeStateRows((LuaTable) val, path, out);
			} else {
				String enc = encodeScalar(val);
				if (enc != null) out.add(String.join("/", path) + "=" + enc);
			}
			path.remove(path.size() - 1);
		}
	}

	/** Encode a table key as a typed, "/"-safe segment: {@code n:<double>} or {@code s:<b64>}. */
	private static String encodeKey(LuaValue key) {
		if (key.isnumber()) return "n:" + key.todouble();
		if (key.isstring()) return "s:" + b64(key.tojstring());
		return null; // boolean/function/table keys are not persistable as map keys here
	}

	/** Inverse of {@link #encodeKey}; returns null on a malformed segment. */
	private static LuaValue decodeKey(String seg) {
		if (seg == null || seg.length() < 3 || seg.charAt(1) != ':') return null;
		char tag = seg.charAt(0);
		String body = seg.substring(2);
		switch (tag) {
			case 'n': try { return LuaValue.valueOf(Double.parseDouble(body)); } catch (Exception e) { return null; }
			case 's': { String s = unb64(body); return s == null ? null : LuaValue.valueOf(s); }
			default: return null;
		}
	}

	private static String encodeScalar(LuaValue v) {
		if (v.isnumber()) return "n:" + v.todouble();
		if (v.isstring()) return "s:" + b64(v.tojstring());
		if (v.isboolean()) return "b:" + v.toboolean();
		return null;
	}

	private void loadState(String[] rows) {
		if (rows == null) return;
		if (state == null) state = new LuaTable();
		for (String row : rows) {
			if (row == null) continue;
			int eq = row.indexOf('=');
			if (eq < 0) continue;
			String[] parts = row.substring(0, eq).split("/");
			LuaValue val = decodeScalar(row.substring(eq + 1));
			if (val == null || parts.length == 0) continue;
			LuaTable cur = state;
			for (int i = 0; i < parts.length - 1; i++) {
				LuaValue k = decodeKey(parts[i]);
				if (k == null) { cur = null; break; }
				LuaValue child = cur.get(k);
				LuaTable sub;
				if (child.istable()) {
					sub = (LuaTable) child;
				} else {
					sub = new LuaTable();
					cur.set(k, sub);
				}
				cur = sub;
			}
			if (cur == null) continue;
			LuaValue leaf = decodeKey(parts[parts.length - 1]);
			if (leaf != null) cur.set(leaf, val);
		}
	}

	private static LuaValue decodeScalar(String encoded) {
		if (encoded == null || encoded.length() < 2) return null;
		char tag = encoded.charAt(0);
		String body = encoded.substring(2);
		switch (tag) {
			case 'n': try { return LuaValue.valueOf(Double.parseDouble(body)); } catch (Exception e) { return null; }
			case 's': {
				String s = unb64(body);
				return s == null ? null : LuaValue.valueOf(s);
			}
			case 'b': return LuaValue.valueOf("true".equals(body));
			default: return null;
		}
	}

	private static String b64(String s) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	private static String unb64(String s) {
		try {
			return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return null;
		}
	}
}
