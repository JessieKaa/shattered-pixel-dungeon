package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.QuickSlot;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.DeviceCompat;
import com.watabou.utils.Random;
import org.luaj.vm2.LuaTable;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Coordinates entering / leaving a {@link DataDrivenLevel} at runtime, and (M4d)
 * injecting town-portal NPCs into main-line levels. M4a wired the enter/leave
 * primitives + a debug button; M4b added Lua NPCs; M4c added the shop; M4d wires
 * the hub into the level graph + fixes the R4 state-preservation defect.
 *
 * <h3>Enter flow</h3>
 * <ol>
 *   <li>{@link Dungeon#saveAll()} — persist real progress so the leave path can
 *       restore it.</li>
 *   <li>Build the {@link DataDrivenLevel} from {@code mods/levels/<id>.json}.</li>
 *   <li>{@link Dungeon#switchLevel} — installs it as {@code Dungeon.level}, places
 *       the hero at {@code level.entrance()}, runs {@code Actor.init()}.</li>
 *   <li>{@link Game#switchScene}({@link GameScene}) — re-renders against the new
 *       level.</li>
 * </ol>
 *
 * <p>While a {@link DataDrivenLevel} is active, {@link Dungeon#saveAll}
 * self-suppresses via {@link Level#isEphemeral()} — the SafeZone is in-memory
 * only, never written to disk.
 *
 * <h3>Leave flow (M4d, R4 state preservation)</h3>
 *
 * <p>The live {@link Dungeon#hero} object IS the hero with all SafeZone changes
 * (purchased items in {@code belongings}, deducted {@link Dungeon#gold}, HP/buff
 * deltas). The fix preserves that object reference across the level swap instead
 * of letting {@code Dungeon.loadGame} replace it from the pre-SafeZone save.
 * See {@link #leaveLevel()} for the full mechanism and the explicit MVP scope
 * boundary.
 *
 * <h3>Inject flow (M4d)</h3>
 *
 * <p>{@link #injectLevelNpcs(Level)} is called from
 * {@code RegularLevel.createMobs} (single upstream hook). Debug-gated; routes by
 * {@link Dungeon#depth} to decide which Lua NPC to spawn near the level entrance.
 */
public final class LuaLevelService {

	private static final String TAG = "LuaLevelService";
	private static final String LEVELS_DIR = "mods/levels/";
	/** Registry id of the town-portal NPC spawned on main-line depth 1 (M4d MVP). */
	private static final String TOWN_PORTAL_ID = "town_portal";

	private LuaLevelService() { }

	/**
	 * Death guard for ephemeral levels (R4). A hero can still die inside a SafeZone from a
	 * debuff carried in (hunger/burning/poison/bleed), and the upstream death chain
	 * ({@code Hero.reallyDie} → {@code Dungeon.deleteGame} + {@code Dungeon.fail} →
	 * {@code Rankings.submit}) would corrupt the real run. Mirrors the save-slot
	 * {@code interceptDeath} hook: short-circuit {@link Hero#die} and restore the real run
	 * via CONTINUE (the save taken on enter is pristine — no save fires while ephemeral).
	 * The scene switch runs on the render thread per the save-slot precedent.
	 *
	 * <p>Placed <b>before</b> the save-slot intercept in {@link Hero#die}: a SafeZone death
	 * is a debug anomaly and auto-restoring the real level is cleaner than offering a
	 * save-slot reload inside an ephemeral level.
	 *
	 * @return true if the death was intercepted (SafeZone active); false otherwise.
	 */
	public static boolean interceptDeath(Hero hero, Object cause) {
		if (!inDataLevel()) return false;
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				leaveLevel();
			}
		});
		return true;
	}

	/** True when the live {@link Dungeon#level} is a {@link DataDrivenLevel}. */
	public static boolean inDataLevel() {
		return Dungeon.level != null && Dungeon.level instanceof DataDrivenLevel;
	}

	/**
	 * Build and enter the named JSON level. Debug-gated; never throws — on any failure it
	 * logs and leaves the player on the current level rather than half-switching.
	 */
	public static void enterLevel(String id) {
		if (!DeviceCompat.isDebug()) {
			Gdx.app.error(TAG, "enterLevel ignored: not a debug build");
			return;
		}
		if (Dungeon.hero == null || !Dungeon.hero.isAlive()) {
			Gdx.app.error(TAG, "enterLevel ignored: hero missing or dead");
			return;
		}
		if (inDataLevel()) {
			Gdx.app.error(TAG, "enterLevel ignored: already in a data level");
			return;
		}
		try {
			Dungeon.saveAll();

			DataDrivenLevel level = loadLevelById(id);
			if (level == null) {
				Gdx.app.error(TAG, "enterLevel: could not load level '" + id + "'");
				return;
			}
			level.create();

			// FIX(M4a): Actor.init() (called inside switchLevel) only adds — it does not
			// clear the previous actor set. Standard level switches clear first via
			// InterlevelScene; enterLevel must too, otherwise mainline mobs (whose pos
			// indexes the much larger mainline map) leak into the smaller SafeZone and
			// overflow findPassable's vis/passable arrays (length=256; index=284/902).
			Actor.clear();

			Dungeon.switchLevel(level, level.entrance());
			Game.switchScene(GameScene.class);
		} catch (IOException e) {
			Gdx.app.error(TAG, "enterLevel save failed", e);
		} catch (Exception e) {
			Gdx.app.error(TAG, "enterLevel failed", e);
		}
	}

	/**
	 * Resolve a level id to a {@link DataDrivenLevel} by origin (M12d). An <em>external</em> mod
	 * registration (origin == EXTERNAL with a live {@code baseDir}) reads the json from
	 * {@code baseDir.child(...)} via {@link DataDrivenLevel#fromFileHandle}; anything else — an
	 * unregistered id (the builtin {@code test_safezone} path), a builtin mod registration, or a
	 * {@code register_level} fired outside any mod context — falls through to the original
	 * classpath {@link DataDrivenLevel#fromAsset} lookup, so builtin behaviour is unchanged.
	 *
	 * <p>Discriminating on {@code origin == EXTERNAL} (not bare {@code baseDir != null}) is load-
	 * bearing: builtin mods also carry a non-null {@code baseDir} (a classpath handle), and routing
	 * them through {@code baseDir.child()} would change how builtin levels resolve. The extra
	 * {@code baseDir != null} check is NPE defence. An explicit {@code path} (when present)
	 * overrides the per-origin default ({@code levels/<id>.json} for external, the shared
	 * {@code mods/levels/<id>.json} classpath path for builtin).
	 *
	 * <p>Returns null on any load failure (missing file, bad json) — {@link #enterLevel}'s null
	 * check logs and stays on the current level, matching the pre-M12d builtin failure behaviour.
	 */
	private static DataDrivenLevel loadLevelById(String id) {
		LuaLevelRegistry.Entry entry = LuaLevelRegistry.get(id);
		if (entry != null
				&& entry.origin == ModManifest.Origin.EXTERNAL
				&& entry.baseDir != null) {
			String rel = entry.path != null ? entry.path : "levels/" + id + ".json";
			return DataDrivenLevel.fromFileHandle(entry.baseDir.child(rel), id);
		}
		String classpath = (entry != null && entry.path != null)
				? entry.path : LEVELS_DIR + id + ".json";
		return DataDrivenLevel.fromAsset(classpath, id);
	}

	/**
	 * Leave a data level and restore the main run while preserving the hero's in-memory
	 * state (M4d, R4). No-op if not currently in a data level.
	 *
	 * <h3>Option C — synchronous swap + live hero reference preservation</h3>
	 *
	 * <p>{@link Dungeon#hero} at this moment is the same Java object that accumulated
	 * SafeZone changes (purchased items in {@code belongings}, deducted {@link Dungeon#gold},
	 * HP/buff deltas). The pre-SafeZone save taken on enter is pristine (no save fires
	 * while ephemeral), so {@link Dungeon#loadGame} restores the main run cleanly — except
	 * it also replaces {@code Dungeon.hero}/{@code gold}/{@code quickslot}/{@code nextID}
	 * from that pre-SafeZone snapshot, which would silently discard those SafeZone changes.
	 *
	 * <p>So: capture the four live values, let {@code loadGame}+{@code loadLevel} rebuild
	 * everything (hero/gold/quickslot/nextID/depth/branch + the main level), grab the main
	 * level position off the restored throwaway hero, then write the live values back and
	 * {@code switchLevel} onto the main level with the live hero. {@link Actor#init()}
	 * (called by {@code switchLevel}) re-queues the live hero + main-level mobs.
	 *
	 * <h3>Dead-hero branch</h3>
	 *
	 * <p>If the hero is dead (only reachable via {@link #interceptDeath}), there is nothing
	 * worth preserving — take the original CONTINUE path (full restore), which is the m4a
	 * escape hatch. {@code interceptDeath} runs on the render thread, so this branch can
	 * {@code switchScene(InterlevelScene)} directly.
	 *
	 * <h3>MVP scope boundary (R4, codex round-1)</h3>
	 *
	 * <p>Preserved: {@code hero.belongings} (items), {@link Dungeon#gold},
	 * {@code hero.HP}/buffs (object identity), {@link Dungeon#quickslot} slots,
	 * {@link Actor}'s {@code nextID}. NOT preserved (acceptably lost): item
	 * identification, {@code LimitedDrops}, quest state, {@code Statistics},
	 * {@code Badges}, {@code Notes}, {@code Generator} — none of these change
	 * meaningfully inside a SafeZone shop hub (no enemies, no quest NPCs that
	 * mutate state, players do not consume/identify there).
	 */
	public static void leaveLevel() {
		if (!inDataLevel()) {
			Gdx.app.error(TAG, "leaveLevel ignored: not in a data level");
			return;
		}
		if (Dungeon.hero == null) {
			Gdx.app.error(TAG, "leaveLevel ignored: hero missing");
			return;
		}
		if (!Dungeon.hero.isAlive()) {
			// Death escape: nothing to preserve, full restore via CONTINUE.
			InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
			Game.switchScene(InterlevelScene.class);
			return;
		}
		LiveHeroState live = captureLiveState();
		try {
			// Throwaway quickslot absorbs the reset()+restorePlaceholders() that loadGame
			// performs; the live quickslot is swapped back afterwards, untouched.
			Dungeon.quickslot = new QuickSlot();
			Dungeon.loadGame(GamesInProgress.curSlot);
			Level lvl = Dungeon.loadLevel(GamesInProgress.curSlot);
			// The just-restored hero sits on a valid main-level cell; take it before we
			// discard that throwaway hero for the live one.
			int mainPos = Dungeon.hero.pos;
			applyLiveState(live);
			Dungeon.switchLevel(lvl, mainPos);
			Game.switchScene(GameScene.class);
		} catch (IOException e) {
			Gdx.app.error(TAG, "leaveLevel load failed, falling back to CONTINUE", e);
			// Re-apply live state before CONTINUE so the fallback does not half-reset.
			applyLiveState(live);
			InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
			Game.switchScene(InterlevelScene.class);
		}
	}

	// ---- M4d R4 testable seam: live state capture / re-apply ----

	/**
	 * Snapshot of the four live globals that {@link Dungeon#loadGame} overwrites from
	 * the pre-SafeZone save. Captured before load, re-applied after. Package-visible so
	 * the headless harness can exercise the round-trip without running real I/O.
	 */
	static final class LiveHeroState {
		final Hero hero;
		final int gold;
		final QuickSlot quickslot;
		final Bundle nextId;

		LiveHeroState(Hero hero, int gold, QuickSlot quickslot, Bundle nextId) {
			this.hero = hero;
			this.gold = gold;
			this.quickslot = quickslot;
			this.nextId = nextId;
		}
	}

	/**
	 * Capture the live hero/gold/quickslot/nextID. The hero reference is the load-bearing
	 * piece — its {@code belongings} carry the SafeZone purchases and its identity
	 * preserves HP/buffs without a bundle round-trip.
	 */
	static LiveHeroState captureLiveState() {
		Bundle nextId = new Bundle();
		Actor.storeNextID(nextId);
		return new LiveHeroState(Dungeon.hero, Dungeon.gold, Dungeon.quickslot, nextId);
	}

	/**
	 * Re-apply a previously captured {@link LiveHeroState} over the current
	 * {@code Dungeon}/{@code Actor} globals. Used after {@link Dungeon#loadGame} to undo
	 * the four pre-SafeZone overwrites.
	 */
	static void applyLiveState(LiveHeroState state) {
		Dungeon.hero = state.hero;
		Dungeon.gold = state.gold;
		Dungeon.quickslot = state.quickslot;
		Actor.restoreNextID(state.nextId);
	}

	// ---- M4d: main-line NPC injection ----

	/**
	 * Inject Lua-defined NPCs into a freshly-built level. Called once from
	 * {@code RegularLevel.createMobs} (the single upstream hook). Debug-gated so release
	 * builds see zero behavioural change — the method returns before touching anything.
	 * Delegates the routing to {@link #spawnForDepth(Level, int)} so tests can exercise
	 * the routing directly without flipping {@link DeviceCompat#isDebug()}.
	 */
	public static void injectLevelNpcs(Level level) {
		if (!DeviceCompat.isDebug()) return;
		if (level == null) return;
		// Never inject into a DataDrivenLevel — those own their createMobs and would
		// double-spawn if a subclass ever called super. Belt-and-suspenders.
		if (level instanceof DataDrivenLevel) return;
		spawnForDepth(level, Dungeon.depth);
	}

	/**
	 * Route by depth and spawn the matching Lua NPC near the level entrance. MVP: depth 1
	 * spawns the {@code town_portal} NPC. Other depths spawn nothing. Package-visible for
	 * direct headless testing (the {@link #injectLevelNpcs(Level)} isDebug guard is
	 * exercised separately).
	 */
	static void spawnForDepth(Level level, int depth) {
		if (depth != 1) return;
		LuaNpc npc = LuaNpcRegistry.create(TOWN_PORTAL_ID);
		if (npc == null) {
			Gdx.app.error(TAG, "spawnForDepth: registry has no '" + TOWN_PORTAL_ID + "' — skipping");
			return;
		}
		int pos = findSpawnPosNearEntrance(level);
		if (pos < 0) {
			Gdx.app.error(TAG, "spawnForDepth: no passable unoccupied cell near entrance — skipping");
			return;
		}
		npc.pos = pos;
		level.mobs.add(npc);
	}

	/**
	 * Find a passable, non-solid, unoccupied cell adjacent (or within radius 2) to
	 * {@link Level#entrance()}, excluding the entrance cell itself. Returns -1 if none
	 * qualifies — the caller logs and skips spawn rather than falling back to the
	 * entrance (which would overlap the hero on switchLevel). Computed from
	 * {@link Level#width()}/{@link Level#height()} directly (NOT {@code PathFinder} — its
	 * NEIGHBOURS arrays are width-cached and may be stale during createMobs, before
	 * {@code switchLevel} runs {@code setMapSize}).
	 */
	static int findSpawnPosNearEntrance(Level level) {
		int entrance = level.entrance();
		int w = level.width();
		int h = level.height();
		int ex = entrance % w;
		int ey = entrance / w;
		for (int r = 1; r <= 2; r++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dx = -r; dx <= r; dx++) {
					if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue; // ring at radius r
					int nx = ex + dx;
					int ny = ey + dy;
					if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
					int pos = nx + ny * w;
					if (pos == entrance) continue;
					if (!level.passable[pos] || level.solid[pos]) continue;
					if (level.findMob(pos) != null) continue;
					return pos;
				}
			}
		}
		return -1;
	}

	// ---- M10b: Lua trap injection ----

	/**
	 * Inject Lua-defined traps into a freshly-built level. Called once from
	 * {@code RegularLevel.createMobs} (the single upstream hook, right after
	 * {@link #injectLevelNpcs}). Not debug-gated — M9 opened modding to release,
	 * so the gate is purely {@link LuaTrapRegistry#hasAny()}: vanilla playthroughs
	 * (no trap scripts loaded) hit the early return and see zero behavioural
	 * change. Skips {@link DataDrivenLevel} (those own their trap layout).
	 *
	 * <p>Runs after {@code buildFlagMaps()} (Level.create: build→buildFlagMaps→
	 * createMobs), so each placed trap must call {@link Level#updateCellFlags} to
	 * refresh the {@code secret[]} flag hidden-trap search reads — writing
	 * {@code map[pos]=SECRET_TRAP} alone would leave stale EMPTY flags. Never
	 * calls 2-arg {@code Level.set(cell,terrain)}: that resolves {@code Dungeon.level},
	 * which is still null here (assigned only at Dungeon.java:490, after create()).
	 *
	 * <p>Candidates are EMPTY cells with no mob/heap/plant/trap and not the
	 * entrance/exit. Upstream paintTraps already flipped its trap cells to
	 * TRAP/SECRET_TRAP, so the EMPTY filter structurally avoids collisions.
	 */
	public static void injectLevelTraps(Level level) {
		if (level == null) return;
		if (level instanceof DataDrivenLevel) return;
		if (!LuaTrapRegistry.hasAny()) return;
		placeLuaTraps(level);
	}

	/**
	 * Place up to {@code min(registrySize, validCells/5)} Lua traps (density cap
	 * mirrors {@code RegularPainter.paintTraps}). Package-visible for direct
	 * headless testing without the isDebug/registry gate.
	 */
	static void placeLuaTraps(Level level) {
		ArrayList<Integer> valid = new ArrayList<>();
		int entrance = level.entrance();
		int exit = level.exit();
		for (int i = 0; i < level.length(); i++) {
			if (level.map[i] != Terrain.EMPTY) continue;
			if (i == entrance || i == exit) continue;
			if (level.findMob(i) != null) continue;
			if (level.heaps.get(i) != null) continue;
			if (level.plants.get(i) != null) continue;
			if (level.traps.get(i) != null) continue;
			valid.add(i);
		}
		if (valid.isEmpty()) return;
		int budget = Math.min(LuaTrapRegistry.size(), valid.size() / 5);
		if (budget <= 0) return;
		ArrayList<String> ids = new ArrayList<>(LuaTrapRegistry.ids());
		for (int i = 0; i < budget; i++) {
			String id = ids.get(i % ids.size());
			LuaTable tbl = LuaTrapRegistry.getTable(id);
			if (tbl == null) continue;
			LuaTrap trap = new LuaTrap(tbl);
			int pos = valid.remove(Random.Int(valid.size()));
			level.setTrap(trap, pos);      // GameScene.updateMap no-ops here (scene null)
			trap.hide();
			level.map[pos] = Terrain.SECRET_TRAP;
			level.updateCellFlags(pos);    // refresh secret/passable/solid for this cell
		}
	}
}
