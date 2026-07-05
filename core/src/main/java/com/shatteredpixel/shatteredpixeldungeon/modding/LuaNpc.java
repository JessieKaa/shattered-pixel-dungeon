package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.NPC;
import com.shatteredpixel.shatteredpixeldungeon.modding.annotations.LuaInterface;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GhostSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.BlacksmithSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ImpSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.MirrorSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.RatKingSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ShopkeeperSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.WandmakerSprite;
import com.watabou.utils.Bundle;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.HashMap;
import java.util.Map;

/**
 * An interactive NPC whose name/sprite come from a Lua table and whose
 * {@link #interact(Char)} dispatches a Lua {@code onInteract(selfId, heroId)}
 * callback (M4b). The M4b analogue of {@link LuaMob}/{@link LuaAlly} — same
 * persistence + callback-dispatch pattern, mapped onto {@link NPC}.
 *
 * <p><b>Passive and invincible.</b> Inherits {@code NEUTRAL}/{@code PASSIVE}
 * from NPC and clones RatKing's five invincibility overrides
 * ({@code defenseSkill=INFINITE_EVASION} / {@code chooseEnemy=null} /
 * {@code damage=no-op} / {@code add(Buff)=false} / {@code reset=true}), so the
 * NPC never joins combat, never dies, and never accepts buffs. This is what
 * makes it SafeZone-safe.
 *
 * <h3>interact routing (D2/D3)</h3>
 *
 * <p>Mirrors RatKing: {@code sprite.turnTo} first, then a hero-only guard — a
 * non-hero Char (ally/clone/champion) hitting the NPC must <b>not</b> fire Lua
 * dialog or open UI windows. Only {@link Dungeon#hero} triggers
 * {@code onInteract}. The callback is fire-and-forget via
 * {@link LuaItemCallbacks#callOpt}; a broken script never freezes the interact.
 *
 * <p>{@code act()} is intentionally <b>not</b> overridden: NPC's PASSIVE AI is
 * exactly what a dialog NPC wants, and M4b's scope is interaction, not Lua-driven
 * NPC AI. Lua never receives a {@link Char} object — only {@code int id()} (M1
 * sandbox boundary, same as {@link LuaMob}).
 *
 * <h3>Persistence (D4)</h3>
 *
 * <p>SafeZone is ephemeral ({@link DataDrivenLevel#isEphemeral()}), so production
 * never persists a LuaNpc. The store/restore path stays correct (and is exercised
 * by the unit test) by stashing {@code lua_npc_id} and re-hydrating the
 * definitional fields (name/sprite) from {@link LuaNpcRegistry} on restore.
 */
public class LuaNpc extends NPC {

	private static final String TAG = "LuaNpc";
	private static final String LUA_NPC_ID = "lua_npc_id";

	private String luaNpcId;
	private String nameStr = "???";

	/** Required for {@code Reflection.newInstance} during Bundle restore. */
	public LuaNpc() {
		super();
	}

	public LuaNpc(LuaTable tbl) {
		super();
		hydrate(tbl);
	}

	/**
	 * Re-applies the non-persisted Lua definition: name/sprite + the registry id.
	 * NPC's base HP/HT are fixed at 1 by {@link NPC}'s instance initialiser, so
	 * there is nothing else to hydrate (no combat stats — the NPC is invincible).
	 */
	private void hydrate(LuaTable tbl) {
		luaNpcId = tbl.get("id").checkjstring();
		nameStr = tbl.get("name").checkjstring();
		spriteClass = resolveSprite(tbl.get("sprite").optjstring("rat_king"));
	}

	/**
	 * M4b does not ship new NPC art. The optional {@code sprite} string maps to a
	 * small whitelist of existing NPC-themed sprite classes; an unknown name falls
	 * back to {@link RatKingSprite} (degraded but never crashes, and thematic for
	 * the SafeZone which already hosts a RatKing).
	 */
	private static final Map<String, Class<? extends CharSprite>> SPRITES = new HashMap<>();
	static {
		SPRITES.put("rat_king", RatKingSprite.class);
		SPRITES.put("shopkeeper", ShopkeeperSprite.class);
		SPRITES.put("mirror", MirrorSprite.class);
		SPRITES.put("ghost", GhostSprite.class);
		SPRITES.put("wandmaker", WandmakerSprite.class);
		SPRITES.put("blacksmith", BlacksmithSprite.class);
		SPRITES.put("imp", ImpSprite.class);
	}

	private static Class<? extends CharSprite> resolveSprite(String name) {
		Class<? extends CharSprite> c = name == null ? null : SPRITES.get(name.toLowerCase());
		return c != null ? c : RatKingSprite.class;
	}

	private LuaTable luaTable() {
		return luaNpcId == null ? null : LuaNpcRegistry.getTable(luaNpcId);
	}

	// ---- invincibility (RatKing clone) — SafeZone-safe ----

	@Override
	public int defenseSkill(Char enemy) {
		return INFINITE_EVASION;
	}

	@Override
	protected Char chooseEnemy() {
		return null;
	}

	@Override
	public void damage(int dmg, Object src) {
		// no-op — invincible
	}

	@Override
	public boolean add(Buff buff) {
		return false;
	}

	@Override
	public boolean reset() {
		return true;
	}

	// ---- interact routing ----

	@Override
	public boolean interact(Char c) {
		sprite.turnTo(pos, c.pos);
		dispatchOnInteract(c, Dungeon.hero);
		return true;
	}

	/**
	 * Pure-logic core of {@link #interact}: fires {@code onInteract(selfId, heroId)}
	 * iff {@code caller} is the hero. Extracted to a package-visible seam so the
	 * hero-guard is unit-testable headlessly — {@link #interact} itself needs a
	 * linked sprite and the {@link Dungeon#hero} global, neither of which exist in
	 * the JUnit harness. Production passes {@code (c, Dungeon.hero)}; tests pass
	 * mock chars to exercise both branches of the guard.
	 */
	void dispatchOnInteract(Char caller, Char hero) {
		if (caller != hero) {
			return;
		}
		LuaTable tbl = luaTable();
		if (tbl != null) {
			LuaItemCallbacks.callOpt(tbl, "onInteract",
					LuaValue.valueOf(id()),
					LuaValue.valueOf(caller.id()));
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
		if (luaNpcId != null) bundle.put(LUA_NPC_ID, luaNpcId);
	}

	@Override
	public void restoreFromBundle(Bundle bundle) {
		super.restoreFromBundle(bundle);
		if (bundle.contains(LUA_NPC_ID)) {
			luaNpcId = bundle.getString(LUA_NPC_ID);
			LuaTable tbl = LuaNpcRegistry.getTable(luaNpcId);
			if (tbl != null) {
				hydrate(tbl);
			} else {
				// Engine init failed or script removed — degrade gracefully
				// rather than crash the save load.
				nameStr = "??? (" + luaNpcId + ")";
			}
		}
	}
}
