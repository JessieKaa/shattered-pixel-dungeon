package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility;
import org.luaj.vm2.LuaValue;

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
 * <p><b>Tier cap (M8d3)</b>: tier ∈ [1,4]. Tier 1/2 use the {@code MOD_EXAMPLE_TALENT}/
 * {@code MOD_SECOND_TALENT} slots (cap 2, two-arg constructor default). Tier 3/4 use
 * the {@code MOD_TIER3_TALENT}(cap 3)/{@code MOD_TIER4_TALENT}(cap 4) slots declared
 * with the {@code (icon, maxPoints)} constructor — M7e maxPoints is lowers-only, so
 * the cap must be baked into the enum up front rather than raised via override. Tier
 * 3 keys on {@link HeroSubClass}; tier 4 keys on the {@link ArmorAbility} simple
 * class name (ArmorAbility is an abstract class, not an enum, so no {@code valueOf}).
 *
 * <p><b>C3</b>: with every mod disabled the registry is empty and
 * {@link #injectClassTalents} / {@link #injectSubclassTalents} /
 * {@link #injectArmorTalents} / {@link #isKnownModTalent} are no-ops, so the
 * vanilla {@link Talent#initClassTalents} path is byte-for-byte unchanged.
 */
public final class LuaTalentRegistry {

	private static final String TAG = "LuaTalentRegistry";

	static final class ModTalentDef {
		final Talent talent;
		final int tier;        // 1-4 (M8d3 opens 3/4)
		final HeroClass heroClass;          // tier 1-2 key (null for 3/4)
		final HeroSubClass subClass;        // tier 3 key (null for 1/2/4)
		final String armorAbilityName;      // tier 4 key: ArmorAbility simple class name (null for 1/2/3)
		// M8d2: Lua on_upgrade callback, or null when the talent registered
		// without one. Java null (not LuaValue.NIL) so the dispatch guard is a
		// plain == null check — NIL is a singleton object and would otherwise
		// sail past the guard and throw on .call() every upgrade.
		final LuaValue onUpgrade;

		ModTalentDef(Talent talent, int tier, HeroClass heroClass,
				HeroSubClass subClass, String armorAbilityName, LuaValue onUpgrade) {
			this.talent = talent;
			this.tier = tier;
			this.heroClass = heroClass;
			this.subClass = subClass;
			this.armorAbilityName = armorAbilityName;
			this.onUpgrade = onUpgrade;
		}
	}

	private static final Map<Talent, ModTalentDef> byTalent = new HashMap<>();
	private static final Set<String> knownNames = new HashSet<>();

	private LuaTalentRegistry() { }

	/**
	 * Capture a {@code register_talent{...}} registration for {@code talent}.
	 * Upsert (last call wins per talent). Bad tier/cap/key logs and skips
	 * without throwing — mirrors {@link LuaTalentOverride#register}'s no-throw
	 * contract.
	 *
	 * <p>Three invariants are enforced (the PLAN's core caps):
	 * <ul>
	 *   <li><b>tier ∈ [1,4]</b>.</li>
	 *   <li><b>tier↔cap binding</b>: the slot's {@link Talent#baseMaxPoints()}
	 *       must equal the tier's expected cap (tier 1/2 → 2, tier 3 → 3,
	 *       tier 4 → 4). M7e maxPoints is lowers-only, so a cap-2 slot can never
	 *       serve tier 3/4 (and vice versa) — this is what the per-tier
	 *       {@code MOD_TIER*_TALENT} slots exist to satisfy.</li>
	 *   <li><b>tier↔key mutual exclusion</b>: exactly the right key for the tier,
	 *       all others null — tier 1/2 → {@code heroClass} only, tier 3 →
	 *       {@code subClass} only, tier 4 → {@code armorAbilityName} only
	 *       (ArmorAbility has no enum registry, so the name is resolved against
	 *       {@code abil.getClass().getSimpleName()} at inject time).</li>
	 * </ul>
	 *
	 * @return {@code true} if the registration was captured; {@code false} if it
	 *         was rejected (so the caller can skip downstream forwarding — e.g.
	 *         the M7e override path should not bind to a rejected talent).
	 *
	 * @param onUpgrade the {@code on_upgrade} Lua function to fire when this
	 *                  talent is upgraded, or {@code null} if the registration
	 *                  omitted one (non-function values are normalized to null
	 *                  by the caller). Stored as Java null so the dispatch guard
	 *                  is a plain {@code == null} check.
	 */
	static boolean register(Talent talent, int tier, HeroClass heroClass,
			HeroSubClass subClass, String armorAbilityName, LuaValue onUpgrade) {
		if (talent == null) {
			Gdx.app.error(TAG, "register: null talent, skipping");
			return false;
		}
		if (tier < 1 || tier > 4) {
			Gdx.app.error(TAG, "register '" + talent.name() + "': tier must be 1-4, got "
					+ tier + ", skipping");
			return false;
		}
		// tier↔cap binding: slot baseMaxPoints must match the tier's expected cap.
		// tier 1/2 → 2, tier 3 → 3, tier 4 → 4.
		int expectedCap = (tier <= 2) ? 2 : tier;
		if (talent.baseMaxPoints() != expectedCap) {
			Gdx.app.error(TAG, "register '" + talent.name() + "': tier " + tier
					+ " requires a slot with baseMaxPoints=" + expectedCap
					+ ", but " + talent.name() + " has baseMaxPoints=" + talent.baseMaxPoints()
					+ " — use the MOD_TIER* slot declared for this tier, skipping");
			return false;
		}
		// tier↔key mutual exclusion (defensive — LuaEngine also guards this at
		// the table level): exactly the right key for the tier, all others null.
		if (tier <= 2) {
			if (heroClass == null || subClass != null || armorAbilityName != null) {
				Gdx.app.error(TAG, "register '" + talent.name() + "': tier " + tier
						+ " requires 'class' only (no subclass/armor_ability), skipping");
				return false;
			}
		} else if (tier == 3) {
			if (subClass == null || heroClass != null || armorAbilityName != null) {
				Gdx.app.error(TAG, "register '" + talent.name() + "': tier 3 requires 'subclass' only"
						+ " (no class/armor_ability), skipping");
				return false;
			}
		} else { // tier == 4
			if (armorAbilityName == null || heroClass != null || subClass != null) {
				Gdx.app.error(TAG, "register '" + talent.name() + "': tier 4 requires 'armor_ability' only"
						+ " (no class/subclass), skipping");
				return false;
			}
		}
		byTalent.put(talent, new ModTalentDef(talent, tier, heroClass, subClass, armorAbilityName, onUpgrade));
		knownNames.add(talent.name());
		return true;
	}

	/**
	 * Fire the registered {@code on_upgrade} Lua callback for {@code talent}, if
	 * any. Called from the single-point hook at the tail of
	 * {@link Talent#onTalentUpgraded} (M8d2), so it runs on EVERY upgrade of
	 * EVERY talent — the guards therefore must be cheap:
	 * <ul>
	 *   <li>vanilla talents are not in {@code byTalent} → one HashMap miss returns.</li>
	 *   <li>a mod talent without {@code on_upgrade} → {@code def.onUpgrade == null} returns.</li>
	 *   <li>only a mod talent that registered an {@code on_upgrade} function enters Lua.</li>
	 * </ul>
	 *
	 * <p>The callback receives {@code (heroId:int, points:int)} — id-only across
	 * the sandbox, no Java handle (D5'-(a)). {@code points} is the post-upgrade
	 * count ({@link Hero#upgradeTalent} increments before invoking
	 * {@code onTalentUpgraded}). Any Lua-side exception is logged and swallowed
	 * so a buggy mod script never blocks a talent upgrade.
	 */
	public static void dispatchTalentUpgraded(Hero hero, Talent talent, int points) {
		if (hero == null || talent == null) return;
		ModTalentDef def = byTalent.get(talent);
		if (def == null || def.onUpgrade == null) return;		try {
			def.onUpgrade.call(LuaValue.valueOf(hero.id()), LuaValue.valueOf(points));
		} catch (Exception e) {
			Gdx.app.error(TAG, "on_upgrade threw for talent " + talent.name(), e);
		}
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

	/**
	 * Append every tier-3 mod talent registered for {@code cls} into its tier-3
	 * slot (index 2) at 0 points. Called once at the tail of
	 * {@link Talent#initSubclassTalents(HeroSubClass, ArrayList)} so vanilla
	 * talents stay first and the switch body is untouched (single-point hook).
	 * Idempotent: an already-present talent is left alone so a re-init does not
	 * clobber spent points.
	 */
	public static void injectSubclassTalents(HeroSubClass cls, ArrayList<LinkedHashMap<Talent, Integer>> talents) {
		if (cls == null || talents == null) return;
		for (ModTalentDef def : byTalent.values()) {
			if (def.tier != 3 || def.subClass != cls) continue;
			if (talents.size() <= 2) continue;
			LinkedHashMap<Talent, Integer> tier = talents.get(2);
			if (!tier.containsKey(def.talent)) {
				tier.put(def.talent, 0);
			}
		}
	}

	/**
	 * Append every tier-4 mod talent registered for {@code abil} into its tier-4
	 * slot (index 3) at 0 points. Called once at the tail of
	 * {@link Talent#initArmorTalents(ArmorAbility, ArrayList)} so vanilla
	 * talents stay first and the loop body is untouched (single-point hook).
	 * Matching is by {@code abil.getClass().getSimpleName()} because
	 * {@link ArmorAbility} is an abstract class, not an enum — a misnamed
	 * registration simply matches no ability and is a no-op. Idempotent.
	 */
	public static void injectArmorTalents(ArmorAbility abil, ArrayList<LinkedHashMap<Talent, Integer>> talents) {
		if (abil == null || talents == null) return;
		String abilName = abil.getClass().getSimpleName();
		for (ModTalentDef def : byTalent.values()) {
			if (def.tier != 4 || !abilName.equals(def.armorAbilityName)) continue;
			if (talents.size() <= 3) continue;
			LinkedHashMap<Talent, Integer> tier = talents.get(3);
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
