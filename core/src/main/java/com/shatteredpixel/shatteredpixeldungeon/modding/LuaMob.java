package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.modding.annotations.LuaInterface;
import com.shatteredpixel.shatteredpixeldungeon.sprites.BatSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.BruteSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CrabSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GnollSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.RatSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.SkeletonSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.SlimeSprite;
import com.watabou.utils.Bundle;
import com.watabou.utils.Random;
import com.watabou.utils.Reflection;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A hostile mob whose attributes (hp/ht/attack/defense/name/sprite) and AI
 * hooks (act/attackProc/defenseProc/die) come from a Lua table. The M3a
 * analogue of {@link LuaItem} — same persistence + callback-dispatch pattern,
 * mapped onto {@link Mob}.
 *
 * <p><b>Hostile by default.</b> {@link Mob}'s instance initialiser sets
 * {@code alignment = Alignment.ENEMY}, so a LuaMob is a normal enemy. Friendly
 * mobs (pets, {@code DirectableAlly}) are M3b.
 *
 * <h3>AI callbacks (D2)</h3>
 *
 * <p>Each override runs the upstream {@link Mob} chain first (so a Lua mob
 * keeps vanilla AI / proc semantics), then dispatches into the Lua table via
 * {@link LuaItemCallbacks}. Missing function fields / Lua errors fall back to
 * the upstream-computed value — a broken script never freezes combat.
 *
 * <ul>
 *   <li><b>act(selfId)</b> — special. If the Lua function returns {@code true},
 *       Lua fully takes over the tick and the upstream AI state machine is
 *       <i>skipped</i> (no {@code super.act()}). Returning false/nil/no-function
 *       runs the normal {@link Mob#act} AI. <b>Critical correctness:</b> the
 *       takeover path must still advance actor time, so Java unconditionally
 *       {@code spend(TICK)} on that path. Lua cannot call {@code Actor.spend}
 *       (protected) and the {@code RPD.*} surface exposes no spend primitive,
 *       so without this fallback the mob would pin the actor queue and freeze
 *       the whole game.</li>
 *   <li><b>attackProc(selfId, enemyId, baseDamage)</b> — super first, then Lua
 *       may return a number to override damage (nil/non-number keeps base).</li>
 *   <li><b>defenseProc(selfId, enemyId, baseDamage)</b> — same shape.</li>
 *   <li><b>die(selfId)</b> — super first (loot/XP/talents), then Lua notified.</li>
 * </ul>
 *
 * <p>Lua never receives a {@link Char} object — only {@code int id()} (M1
 * sandbox boundary, same as {@link LuaItem}).
 *
 * <h3>Persistence (D4)</h3>
 *
 * <p>{@link Mob#storeInBundle} already persists AI state / enemy id / max-lvl
 * but not the Lua definition. So we stash {@code lua_mob_id} and re-hydrate the
 * rest from {@link LuaMobRegistry} on restore (the engine has run by the time
 * mobs are restored). Bundle restore needs a no-arg constructor.
 */
public class LuaMob extends Mob {

	private static final String TAG = "LuaMob";
	private static final String LUA_MOB_ID = "lua_mob_id";
	private static final String LUA_SPAWNED = "lua_spawned";
	private static final String LUA_IMMUNITY_CLASSES = "lua_immunity_classes";

	private String luaMobId;
	private String nameStr = "???";
	private int attackStat = 1;

	/**
	 * One-shot latch for the Lua {@code spawn} callback: fires on the first
	 * {@link #act()} (so the mob has an id and is in the actor queue), then never
	 * again. Persisted across save/load so a restore does not re-trigger spawn
	 * (which would double-apply buffs/immunities). A LuaMob with no {@code spawn}
	 * field simply skips the dispatch ({@link LuaItemCallbacks#callOpt}).
	 */
	private boolean spawned = false;

	/**
	 * FQCNs of immunities added via {@link RpdApi}'s {@code addImmunity}. These
	 * are NOT persisted by {@link Char#storeInBundle} (only pos/HP/HT/buffs are),
	 * so we persist them ourselves and rebuild {@code immunities} on restore —
	 * otherwise a gas-emitting mob would lose its self-immunity after save/load
	 * and poison itself to death.
	 */
	private final List<String> luaImmunityClassNames = new ArrayList<>();

	/** Required for {@code Reflection.newInstance} during Bundle restore. */
	public LuaMob() {
		super();
	}

	public LuaMob(LuaTable tbl) {
		super();
		hydrate(tbl);
		// Fresh mob: current/max HP come from the Lua definition. Done in the
		// ctor (NOT in hydrate) so save restore can re-hydrate the definitional
		// fields without clobbering the saved, possibly-wounded HP/HT.
		int hp = tbl.get("hp").checkint();
		HT = tbl.get("ht").optint(hp);
		HP = Math.min(hp, HT);
	}

	/**
	 * Re-applies the non-persisted Lua definition: name/attack/defense/sprite +
	 * the registry id. Crucially this does <b>not</b> touch {@code HP}/{@code HT}
	 * — those are {@link Char} bundle fields and must round-trip a wounded mob's
	 * runtime state. Only the fresh-create ctor sets HP/HT from Lua.
	 */
	private void hydrate(LuaTable tbl) {
		luaMobId = tbl.get("id").checkjstring();
		nameStr = tbl.get("name").checkjstring();
		attackStat = Math.max(1, tbl.get("attack").checkint());
		defenseSkill = tbl.get("defense").checkint();
		spriteClass = resolveSprite(tbl.get("sprite").optjstring("crab"));
	}

	/**
	 * M3a does not ship new mob art. The optional {@code sprite} string maps to
	 * a small whitelist of existing sprite classes; an unknown name falls back
	 * to {@link CrabSprite} (degraded but never crashes). M3b can replace this
	 * with a real sprite registry.
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
		return luaMobId == null ? null : LuaMobRegistry.getTable(luaMobId);
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

	// ---- AI hooks (D2) ----

	@Override
	protected boolean act() {
		LuaTable tbl = luaTable();
		// One-shot spawn callback: fires before any act logic on the mob's first
		// tick. Lua typically uses it to add self-immunity (RPD.addImmunity) or
		// pick a kind. callOpt is fault-tolerant: no spawn fn / Lua error → skip.
		if (!spawned) {
			spawned = true;
			if (tbl != null) {
				LuaItemCallbacks.callOpt(tbl, "spawn", LuaValue.valueOf(id()));
			}
		}
		LuaValue fn = (tbl != null) ? tbl.get("act") : LuaValue.NIL;
		if (fn.isfunction()) {
			try {
				LuaValue res = fn.call(LuaValue.valueOf(id()));
				if (res.isboolean() && res.toboolean()) {
					// Lua takes over the tick: skip Mob.act()'s AI state machine.
					// CRITICAL: must advance time. Lua cannot call Actor.spend
					// (protected) and RPD offers no spend primitive, so Java
					// spends a default TICK — otherwise this mob pins the actor
					// queue and the whole game loop freezes on it. Char.act()'s
					// FOV housekeeping is also skipped; Lua drives via RPD.* by
					// charId (Actor.findById), which does not need fieldOfView.
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

	// ---- Lua-driven immunity (M6a) ----

	/**
	 * Register a whitelisted Class in this mob's {@code immunities} (the FetidRat
	 * pattern: a gas-emitting mob must be immune to its own gas). Called only by
	 * {@link RpdApi}'s {@code addImmunity}, which has already resolved the Lua id
	 * through the Blob/Buff whitelist — so an arbitrary Class cannot reach this
	 * method. Records the FQCN so the immunity survives save/load (see
	 * {@link #luaImmunityClassNames}). {@code immunities} is {@code protected} in
	 * {@link Char}; access via inheritance ({@code this.immunities}) is legal for
	 * a cross-package subclass.
	 */
	public void addLuaImmunity(String id, Class<?> type) {
		immunities.add(type);
		luaImmunityClassNames.add(type.getName());
	}

	// ---- persistence (D4) ----

	@Override
	public void storeInBundle(Bundle bundle) {
		super.storeInBundle(bundle);
		if (luaMobId != null) bundle.put(LUA_MOB_ID, luaMobId);
		bundle.put(LUA_SPAWNED, spawned);
		if (!luaImmunityClassNames.isEmpty()) {
			bundle.put(LUA_IMMUNITY_CLASSES, luaImmunityClassNames.toArray(new String[0]));
		}
	}

	@Override
	public void restoreFromBundle(Bundle bundle) {
		super.restoreFromBundle(bundle);
		if (bundle.contains(LUA_MOB_ID)) {
			luaMobId = bundle.getString(LUA_MOB_ID);
			LuaTable tbl = LuaMobRegistry.getTable(luaMobId);
			if (tbl != null) {
				hydrate(tbl);
			} else {
				// Engine init failed or script removed — degrade gracefully
				// rather than crash the save load.
				nameStr = "??? (" + luaMobId + ")";
			}
		}
		// spawn latch round-trips; on restore it stays true so spawn does not
		// re-fire (and re-apply buffs/immunities).
		spawned = bundle.contains(LUA_SPAWNED) && bundle.getBoolean(LUA_SPAWNED);
		// Rebuild immunities that addLuaImmunity added pre-save. Reflection.forName
		// only resolves FQCNs we ourselves persisted (already whitelist-bound at
		// add time); it does not parse fresh Lua input. Same pattern as SPD's
		// existing Reflection-based bundle restore.
		if (bundle.contains(LUA_IMMUNITY_CLASSES)) {
			String[] names = bundle.getStringArray(LUA_IMMUNITY_CLASSES);
			for (String fqcn : names) {
				try {
					Class<?> c = Reflection.forName(fqcn);
					if (c != null) {
						immunities.add(c);
						luaImmunityClassNames.add(fqcn);
					}
				} catch (Exception e) {
					Gdx.app.error(TAG, "restore lua immunity " + fqcn + " failed", e);
				}
			}
		}
	}
}
