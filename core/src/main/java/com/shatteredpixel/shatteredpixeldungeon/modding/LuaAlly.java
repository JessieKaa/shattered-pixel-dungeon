package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.DirectableAlly;
import com.shatteredpixel.shatteredpixeldungeon.modding.annotations.LuaInterface;
import com.shatteredpixel.shatteredpixeldungeon.sprites.BatSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.BruteSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CrabSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GnollSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.RatSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.SkeletonSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.SlimeSprite;
import com.watabou.utils.Random;
import com.watabou.utils.Bundle;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.HashMap;
import java.util.Map;

/**
 * A friendly, commandable pet whose attributes (hp/ht/attack/defense/name/sprite)
 * and AI hooks (act/attackProc/defenseProc/die + onCommand) come from a Lua table.
 * The M3b analogue of {@link LuaMob} — same persistence + callback-dispatch
 * pattern, mapped onto {@link DirectableAlly} so we inherit the follow/defend/attack
 * state machine, {@code intelligentAlly}, and the {@code defend_pos}/
 * {@code moving_to_defend} command fields for free.
 *
 * <p><b>Friendly by default.</b> {@link DirectableAlly}'s instance initialiser
 * sets {@code alignment = ALLY} and {@code intelligentAlly = true}; LuaAlly keeps
 * both. Hostile Lua mobs are {@link LuaMob} (M3a).
 *
 * <h3>AI callbacks (D5)</h3>
 *
 * <p>Each override runs the upstream {@link DirectableAlly}/{@link com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob}
 * chain first (so a Lua ally keeps vanilla pet AI / proc semantics), then
 * dispatches into the Lua table via {@link LuaItemCallbacks}. Missing function
 * fields / Lua errors fall back to the upstream-computed value — a broken script
 * never freezes combat.
 *
 * <ul>
 *   <li><b>act(selfId)</b> — special. If the Lua function returns {@code true},
 *       Lua fully takes over the tick and the upstream AI state machine is
 *       <i>skipped</i> (no {@code super.act()}). Returning false/nil/no-function
 *       runs the normal {@link DirectableAlly} AI. <b>Critical correctness:</b>
 *       the takeover path must still advance actor time, so Java unconditionally
 *       {@code spend(TICK)} on that path. Lua cannot call {@code Actor.spend}
 *       (protected) and the {@code RPD.*} surface exposes no spend primitive,
 *       so without this fallback the ally would pin the actor queue and freeze
 *       the whole game (M3a lesson — same fix as {@link LuaMob#act}).</li>
 *   <li><b>attackProc(selfId, enemyId, baseDamage)</b> — super first, then Lua
 *       may return a number to override damage (nil/non-number keeps base).</li>
 *   <li><b>defenseProc(selfId, enemyId, baseDamage)</b> — same shape.</li>
 *   <li><b>die(selfId)</b> — super first (loot/XP/talents), then Lua notified.</li>
 * </ul>
 *
 * <p>Lua never receives a {@link Char} object — only {@code int id()} (M1
 * sandbox boundary, same as {@link LuaItem}/{@link LuaMob}).
 *
 * <h3>Persistence (D4)</h3>
 *
 * <p>{@link DirectableAlly#storeInBundle} persists {@code defend_pos}/
 * {@code moving_to_defend} (command state) and {@link Char} persists {@code HP}/
 * {@code HT} (runtime wounds), but neither persists the Lua definition. So we
 * stash {@code lua_ally_id} and re-hydrate the rest from
 * {@link LuaAllyRegistry} on restore (the engine has run by the time mobs are
 * restored). Bundle restore needs a no-arg constructor.
 *
 * <p><b>HP/HT correctness (M3a codex must-fix):</b> the fresh-create ctor sets
 * {@code HP}/{@code HT} from the Lua {@code hp}/{@code ht} fields. {@link #hydrate}
 * only re-applies definitional fields (name/attack/defense/sprite) and
 * <b>never</b> touches {@code HP}/{@code HT}, so a wounded ally's saved HP
 * round-trips through {@link Char#restoreFromBundle} intact instead of being
 * reset to full.
 */
public class LuaAlly extends DirectableAlly {

	private static final String TAG = "LuaAlly";
	private static final String LUA_ALLY_ID = "lua_ally_id";

	private String luaAllyId;
	private String nameStr = "???";
	private int attackStat = 1;

	/** Required for {@code Reflection.newInstance} during Bundle restore. */
	public LuaAlly() {
		super();
	}

	public LuaAlly(LuaTable tbl) {
		super();
		hydrate(tbl);
		// Fresh ally: current/max HP come from the Lua definition. Done in the
		// ctor (NOT in hydrate) so save restore can re-hydrate the definitional
		// fields without clobbering the saved, possibly-wounded HP/HT.
		int hp = tbl.get("hp").checkint();
		HT = tbl.get("ht").optint(hp);
		HP = Math.min(hp, HT);
	}

	/**
	 * Re-applies the non-persisted Lua definition: name/attack/defense/sprite +
	 * the registry id. Crucially this does <b>not</b> touch {@code HP}/{@code HT}
	 * — those are {@link Char} bundle fields and must round-trip a wounded ally's
	 * runtime state. Only the fresh-create ctor sets HP/HT from Lua.
	 */
	private void hydrate(LuaTable tbl) {
		luaAllyId = tbl.get("id").checkjstring();
		nameStr = tbl.get("name").checkjstring();
		attackStat = Math.max(1, tbl.get("attack").checkint());
		defenseSkill = tbl.get("defense").checkint();
		spriteClass = resolveSprite(tbl.get("sprite").optjstring("crab"));
	}

	/**
	 * Same small whitelist as {@link LuaMob}: M3b does not ship new ally art.
	 * Unknown names fall back to {@link CrabSprite} (degraded but never crashes).
	 */
	private static final Map<String, Class<? extends CharSprite>> SPRITES = new HashMap<>();
	static {
		SPRITES.put("crab", CrabSprite.class);
		SPRITES.put("rat", RatSprite.class);
		SPRITES.put("slime", SlimeSprite.class);
		SPRITES.put("gnoll", GnollSprite.class);
		SPRITES.put("brute", BruteSprite.class);
		SPRITES.put("skeleton", SkeletonSprite.class);
		SPRITES.put("bat", BatSprite.class);
	}

	private static Class<? extends CharSprite> resolveSprite(String name) {
		Class<? extends CharSprite> c = name == null ? null : SPRITES.get(name.toLowerCase());
		return c != null ? c : CrabSprite.class;
	}

	private LuaTable luaTable() {
		return luaAllyId == null ? null : LuaAllyRegistry.getTable(luaAllyId);
	}

	String luaAllyId() {
		return luaAllyId;
	}

	// ---- combat: honour the Lua attack/defense fields ----

	@Override
	public int damageRoll() {
		// Small variance around attackStat keeps combat readable; floored at 1
		// so a misconfigured attack field never produces non-positive damage.
		return Random.NormalIntRange(Math.max(1, attackStat - 2), attackStat + 2);
	}

	@Override
	public int attackSkill(Char target) {
		return attackStat;
	}

	// defenseSkill is a public Mob field set in hydrate; Mob.defenseSkill(enemy)
	// already returns it (when not surprised), so no override needed.

	// ---- AI hooks (D5) ----

	@Override
	protected boolean act() {
		LuaTable tbl = luaTable();
		LuaValue fn = (tbl != null) ? tbl.get("act") : LuaValue.NIL;
		if (fn.isfunction()) {
			try {
				LuaValue res = fn.call(LuaValue.valueOf(id()));
				if (res.isboolean() && res.toboolean()) {
					// Lua takes over the tick: skip DirectableAlly's AI state
					// machine. CRITICAL: must advance time. Lua cannot call
					// Actor.spend (protected) and RPD offers no spend primitive,
					// so Java spends a default TICK — otherwise this ally pins the
					// actor queue and the whole game loop freezes on it (M3a fix).
					spend(TICK);
					return true;
				}
			} catch (Exception e) {
				Gdx.app.error(TAG, "act callback threw", e);
				// fall through to upstream AI — never freeze on a Lua error
			}
		}
		return super.act();
	}

	@Override
	public int attackProc(Char target, int damage) {
		int base = super.attackProc(target, damage);
		LuaTable tbl = luaTable();
		if (tbl == null) return base;
		return LuaItemCallbacks.callOptInt(tbl, "attackProc", base,
				LuaValue.valueOf(id()),
				LuaValue.valueOf(target.id()),
				LuaValue.valueOf(base));
	}

	@Override
	public int defenseProc(Char enemy, int damage) {
		int base = super.defenseProc(enemy, damage);
		LuaTable tbl = luaTable();
		if (tbl == null) return base;
		return LuaItemCallbacks.callOptInt(tbl, "defenseProc", base,
				LuaValue.valueOf(id()),
				LuaValue.valueOf(enemy.id()),
				LuaValue.valueOf(base));
	}

	@Override
	public void die(Object cause) {
		super.die(cause);
		LuaTable tbl = luaTable();
		if (tbl != null) {
			LuaItemCallbacks.callOpt(tbl, "die", LuaValue.valueOf(id()));
		}
	}

	@Override
	@LuaInterface
	public String name() {
		return nameStr;
	}

	@Override
	public String description() {
		return nameStr;
	}

	// ---- persistence (D4) ----

	@Override
	public void storeInBundle(Bundle bundle) {
		super.storeInBundle(bundle);
		if (luaAllyId != null) bundle.put(LUA_ALLY_ID, luaAllyId);
	}

	@Override
	public void restoreFromBundle(Bundle bundle) {
		super.restoreFromBundle(bundle);
		if (bundle.contains(LUA_ALLY_ID)) {
			luaAllyId = bundle.getString(LUA_ALLY_ID);
			LuaTable tbl = LuaAllyRegistry.getTable(luaAllyId);
			if (tbl != null) {
				hydrate(tbl);
			} else {
				// Engine init failed or script removed — degrade gracefully
				// rather than crash the save load.
				nameStr = "??? (" + luaAllyId + ")";
			}
		}
	}
}
