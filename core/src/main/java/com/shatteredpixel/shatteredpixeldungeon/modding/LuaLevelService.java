package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.watabou.noosa.Game;
import com.watabou.utils.Callback;
import com.watabou.utils.DeviceCompat;

import java.io.IOException;

/**
 * Coordinates entering / leaving a {@link DataDrivenLevel} at runtime. M4a wires a debug
 * button ({@link LuaDebugService}); M4b+ will wire it into the level graph.
 *
 * <h3>Enter flow</h3>
 * <ol>
 *   <li>{@link Dungeon#saveAll()} — persist real progress so CONTINUE can restore it.</li>
 *   <li>Build the {@link DataDrivenLevel} from {@code mods/levels/<id>.json}.</li>
 *   <li>{@link Dungeon#switchLevel} — installs it as {@code Dungeon.level}, places the hero
 *       at {@code level.entrance()}, runs {@code Actor.init()}.</li>
 *   <li>{@link Game#switchScene}({@link GameScene}) — re-renders against the new level.</li>
 * </ol>
 *
 * <p>While a {@link DataDrivenLevel} is active, {@link Dungeon#saveAll} self-suppresses via
 * {@link Level#isEphemeral()} — the SafeZone is in-memory only, never written to disk.
 *
 * <h3>Leave flow</h3>
 *
 * <p>Reuses {@link InterlevelScene}'s CONTINUE mode (used, not modified — R5). CONTINUE does
 * a full {@code Dungeon.loadGame}: hero + level + depth/branch all come back from the save
 * taken on enter. Zero pollution of depth / Rankings / GamesInProgress.
 */
public final class LuaLevelService {

	private static final String TAG = "LuaLevelService";
	private static final String LEVELS_DIR = "mods/levels/";

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

			DataDrivenLevel level = DataDrivenLevel.fromAsset(LEVELS_DIR + id + ".json", id);
			if (level == null) {
				Gdx.app.error(TAG, "enterLevel: could not load level '" + id + "'");
				return;
			}
			level.create();

			Dungeon.switchLevel(level, level.entrance());
			Game.switchScene(GameScene.class);
		} catch (IOException e) {
			Gdx.app.error(TAG, "enterLevel save failed", e);
		} catch (Exception e) {
			Gdx.app.error(TAG, "enterLevel failed", e);
		}
	}

	/**
	 * Leave a data level and restore the real run via CONTINUE. No-op if not currently in a
	 * data level.
	 */
	public static void leaveLevel() {
		if (!inDataLevel()) {
			Gdx.app.error(TAG, "leaveLevel ignored: not in a data level");
			return;
		}
		InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
		Game.switchScene(InterlevelScene.class);
	}
}
