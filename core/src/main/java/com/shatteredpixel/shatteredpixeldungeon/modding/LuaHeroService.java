package com.shatteredpixel.shatteredpixeldungeon.modding;

/**
 * Owns the "pending Lua hero" selection state that flows from
 * {@code HeroSelectScene} (where the player picks a class) into
 * {@code Dungeon.init} (where the hero is actually constructed, one scene
 * later via {@code InterlevelScene}).
 *
 * <p><b>Why a service, not a Hero static field</b> (codex round-1 must-fix #1):
 * this is fork state, so it belongs in the {@code modding/} subpackage (C2);
 * and the {@link #consumePending()} idiom (capture-then-clear in one call)
 * guarantees the pending id is used at most once even if {@code Dungeon.init}
 * throws, so a stale Lua id can never leak into a later vanilla/daily start.
 *
 * <p><b>Clear points</b> — every vanilla selection entry must call
 * {@link #clearSelectedLuaHero()} so a previously-clicked Lua hero does not
 * poison a subsequent vanilla/random/daily start:
 * <ul>
 *   <li>{@code HeroSelectScene.HeroBtn.onClick} (vanilla class button)</li>
 *   <li>random-class paths ({@code WndRandomize} / no-victory randomize)</li>
 *   <li>daily entry ({@code WndOptions.onSelect}) — daily is a separate path
 *       that does NOT go through {@code setSelectedHero}, codex confirmed</li>
 *   <li>{@code Dungeon.init} via {@link #consumePending()} (single-shot)</li>
 * </ul>
 */
public final class LuaHeroService {

	private static String pendingId = null;

	private LuaHeroService() { }

	/** Record the Lua hero the player just selected. */
	public static void selectLuaHero(String id) {
		pendingId = id;
	}

	/** Forget any pending Lua hero. Called by every vanilla/random/daily entry. */
	public static void clearSelectedLuaHero() {
		pendingId = null;
	}

	/**
	 * Atomically take and clear the pending id. {@code Dungeon.init} calls this:
	 * the local capture guarantees the id is consumed exactly once even if hero
	 * construction throws, so a stale value can never survive into the next game.
	 */
	public static String consumePending() {
		String p = pendingId;
		pendingId = null;
		return p;
	}

	/** Read without clearing. Used only by UI/tests to ask "is a Lua hero currently selected?". */
	public static String peekPending() {
		return pendingId;
	}
}
