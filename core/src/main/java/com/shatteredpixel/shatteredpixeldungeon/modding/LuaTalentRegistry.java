package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * M8d1 (D6(b) MVP): Lua-driven registration of <b>new</b> talents that inject
 * into a vanilla class+tier slot. Complements {@link LuaTalentOverride} (M7e),
 * which only retunes existing talents — this registry puts a pre-declared
 * {@code MOD_}-prefixed {@link Talent} enum constant into a class's tier list
 * so the player can spend points on it.
 *
 * <p><b>Why enum-backed</b>: {@code Hero.talents} is a
 * {@code LinkedHashMap<Talent, Integer>}, so a mod talent <b>must</b> be a Java
 * enum constant to enter a tier at all. Dynamic non-enum names cannot be stored
 * as tier keys. Hence the {@code MOD_EXAMPLE_TALENT}/{@code MOD_SECOND_TALENT}
 * placeholder slots declared in {@link Talent} — Lua activates one by id, it
 * does not mint entirely new ids at runtime.
 *
 * <p><b>Scope split vs {@link LuaTalentOverride}</b>: this registry owns
 * "which enum goes into which class's which tier" plus the known-mod-name set
 * used by the save-loader's defensive skip. {@code desc}/{@code maxPoints}/
 * {@code title} overrides stay in {@link LuaTalentOverride} —
 * {@code RegisterTalentFunction} forwards the same Lua table there, so
 * {@link Talent#maxPoints()}/{@code desc()}/{@code title()} pick mod values up
 * via the existing M7e fallback path (no second override source).
 *
 * <p><b>MVP tier cap = [1,2]</b>: the declared {@code MOD_*} slots use the
 * two-arg constructor default cap 2 ({@code baseMaxPoints=2}). M7e lowers-only
 * against {@code baseMaxPoints}, so a tier-3/4 registration wanting cap 3/4
 * would be silently rejected. MVP therefore accepts {@code tier ∈ [1,2]} only
 * (matching vanilla T1/T2 cap 2). Tier-3/4 slots need pre-declared per-tier
 * caps and are deferred to M8d2.
 *
 * <p><b>C3</b>: with every mod disabled the registry is empty and
 * {@link #injectClassTalents} / {@link #isKnownModTalent} are no-ops, so the
 * vanilla {@link Talent#initClassTalents} path is byte-for-byte unchanged.
 */
public final class LuaTalentRegistry {

	private static final String TAG = "LuaTalentRegistry";

	static final class ModTalentDef {
		final Talent talent;
		final int tier;        // 1-2 (MVP)
		final HeroClass heroClass;

		ModTalentDef(Talent talent, int tier, HeroClass heroClass) {
			this.talent = talent;
			this.tier = tier;
			this.heroClass = heroClass;
		}
	}

	private static final Map<Talent, ModTalentDef> byTalent = new HashMap<>();
	private static final Set<String> knownNames = new HashSet<>();

	private LuaTalentRegistry() { }

	/**
	 * Capture a {@code register_talent{...}} registration for {@code talent}.
	 * Upsert (last call wins per talent). Bad tier/class logs and skips without
	 * throwing — mirrors {@link LuaTalentOverride#register}'s no-throw contract.
	 *
	 * <p>{@code tier ∈ [1,2]} is the MVP domain (see class javadoc).
	 */
	static void register(Talent talent, int tier, HeroClass heroClass) {
		if (talent == null || heroClass == null) {
			Gdx.app.error(TAG, "register: null talent/class, skipping");
			return;
		}
		if (tier < 1 || tier > 2) {
			Gdx.app.error(TAG, "register '" + talent.name() + "': tier must be 1-2 (MVP), got "
					+ tier + ", skipping");
			return;
		}
		byTalent.put(talent, new ModTalentDef(talent, tier, heroClass));
		knownNames.add(talent.name());
	}

	/**
	 * Does {@code name} correspond to a mod-registered talent? Called from
	 * {@link Talent#restoreTalentsFromBundle}'s {@code Talent.valueOf} catch
	 * branch as a defensive skip: a name that isn't any enum constant but is a
	 * known mod id is treated as "mod removed since save" and silently dropped
	 * rather than reported. In MVP (mod talents are enum constants) this rarely
	 * fires — mod-removal is normally absorbed earlier by the
	 * {@code tier.containsKey} guard — but the path stays correct for corrupted
	 * saves and future non-enum mod ids.
	 */
	public static boolean isKnownModTalent(String name) {
		return name != null && knownNames.contains(name);
	}

	/**
	 * Append every mod talent registered for {@code cls} into its tier slot at
	 * 0 points. Called once at the tail of
	 * {@link Talent#initClassTalents(HeroClass, ArrayList, LinkedHashMap)} so
	 * vanilla talents stay first and the switch body is untouched (single-point
	 * hook). Idempotent within a tier: an already-present talent is left alone
	 * so a re-init does not clobber spent points.
	 */
	public static void injectClassTalents(HeroClass cls, ArrayList<LinkedHashMap<Talent, Integer>> talents) {
		if (cls == null || talents == null) return;
		for (ModTalentDef def : byTalent.values()) {
			if (def.heroClass != cls) continue;
			int idx = def.tier - 1;
			if (idx < 0 || idx >= talents.size()) continue;
			LinkedHashMap<Talent, Integer> tier = talents.get(idx);
			if (!tier.containsKey(def.talent)) {
				tier.put(def.talent, 0);
			}
		}
	}

	/** Number of registered mod talents (for tests/diagnostics). */
	public static int size() {
		return byTalent.size();
	}

	/** Test/reset hook — clears every registration so the next scan starts clean. */
	public static void clear() {
		byTalent.clear();
		knownNames.clear();
	}

	/** Read-only view of registered defs (for tests/diagnostics). */
	static Set<ModTalentDef> defs() {
		return Collections.unmodifiableSet(new HashSet<>(byTalent.values()));
	}
}
