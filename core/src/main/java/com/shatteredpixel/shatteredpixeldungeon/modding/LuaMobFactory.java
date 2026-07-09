package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;

/**
 * Spawn-pool placeholder for Lua mobs. Not a real mob — it is only ever placed
 * into a {@link com.shatteredpixel.shatteredpixeldungeon.actors.mobs.MobSpawner}
 * rotation so that {@link com.shatteredpixel.shatteredpixeldungeon.levels.Level#createMob()}
 * can detect it and swap the slot for a real {@link LuaMob} from the registry.
 *
 * <p>Never instantiated as a visible entity; if reflection ever creates one
 * outside the factory path it immediately dies so it cannot be observed by
 * players.
 */
public class LuaMobFactory extends Mob {

	/**
	 * Required for {@code Reflection.newInstance} in the unlikely event the
	 * factory escapes {@code Level.createMob}. Produces a dead placeholder.
	 */
	public LuaMobFactory() {
		super();
		HP = 0;
	}

	@Override
	public void die(Object cause) {
		// A factory should never be seen alive; if one is, clean up silently.
		destroy();
		super.die(cause);
	}
}
