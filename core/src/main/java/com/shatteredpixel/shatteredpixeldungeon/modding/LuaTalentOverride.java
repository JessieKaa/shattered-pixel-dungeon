package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * M7e (D6=(a)): Lua-driven overrides for {@link Talent} numeric/flavor data.
 * A mod script hands a {@code register_talent_override{...}} table to Lua,
 * which lands here keyed by the resolved {@link Talent} enum constant.
 *
 * <p><b>Scope (Option B, resolved with dispatcher):</b> only {@code desc}
 * (whole-string replacement) and {@code maxPoints} <b>lowering</b> are
 * supported. A Lua {@code maxPoints} greater than the talent's
 * {@linkplain Talent#baseMaxPoints() vanilla cap} is <b>rejected at register
 * time</b> (logged + the field skipped) rather than silently clamped — raising
 * the cap would let raw {@code pointsInTalent()} exceed the {@code [0, vanilla]}
 * domain that dozens of vanilla formulas assume (division by zero in
 * {@code Talent.java:629}, {@code Random.IntRange(points, 2)} with min&gt;max at
 * {@code :875/:882/:907}, 41 {@code ==N} capstone gates that silently never
 * fire). Safe raising needs the per-talent audit + {@code effectivePointsInTalent()}
 * migration deferred to M8.
 *
 * <p><b>Save behaviour (irreversible clamp — modder beware):</b> the override
 * record itself is never written to the Bundle; the points int is, and
 * {@code Talent.storeTalentsInBundle}/{@code restoreTalentsFromBundle} clamp it
 * via the (possibly lowered) {@code maxPoints()}. So once a save is written
 * under a mod that lowers a maxed talent, the trimmed points cannot be
 * recovered by disabling the mod — the same class of "mod-granted progression
 * vanishes when the mod is removed" behaviour. C3 holds: with every mod
 * disabled the registry is empty and {@code maxPoints()}/{@code desc()} return
 * vanilla byte-for-byte.
 *
 * <p>Registration is idempotent (last call wins per talent, whole-object
 * upsert); bad fields are skipped independently so a single malformed field
 * does not poison the valid ones.
 */
public final class LuaTalentOverride {

	private static final String TAG = "LuaTalentOverride";

	private static final Map<Talent, Override> overrides = new HashMap<>();

	private LuaTalentOverride() { }

	/**
	 * Captured-at-registration override for one talent. Both fields are
	 * nullable; {@code null} means "no override for this field, fall back to
	 * vanilla".
	 */
	static final class Override {
		final Integer maxPoints;
		final String desc;
		final String title;

		Override(Integer maxPoints, String desc, String title) {
			this.maxPoints = maxPoints;
			this.desc = desc;
			this.title = title;
		}
	}

	/**
	 * Read the override for a talent, or {@code null} if Lua registered none.
	 * Called from {@link Talent#maxPoints()} / {@link Talent#desc(boolean)} on
	 * a hot path (581 indirect callers); HashMap O(1), default-null pass-through.
	 */
	public static Override get(Talent talent) {
		return overrides.get(talent);
	}

	/** Override maxPoints for {@code talent}, or {@code null} → caller uses vanilla. */
	public static Integer getMaxPoints(Talent talent) {
		Override o = overrides.get(talent);
		return o == null ? null : o.maxPoints;
	}

	/** Override desc for {@code talent}, or {@code null} → caller uses vanilla. */
	public static String getDesc(Talent talent) {
		Override o = overrides.get(talent);
		return o == null ? null : o.desc;
	}

	/**
	 * Override title for {@code talent}, or {@code null} → caller uses vanilla
	 * {@code Messages.get}. M8d1 addition: a {@code MOD_}-prefixed enum has no
	 * {@code .title} properties key, so {@link Talent#title()} reads this first.
	 * M7e {@code register_talent_override} never sets title (no {@code name}
	 * field), so M7e talents keep the vanilla Messages title.
	 */
	public static String getTitle(Talent talent) {
		Override o = overrides.get(talent);
		return o == null ? null : o.title;
	}

	/**
	 * Capture a {@code register_talent_override{...}} table for {@code talent}.
	 * Field validation is independent per field:
	 * <ul>
	 *   <li>{@code maxPoints} — int, {@code 1 <= v <= talent.baseMaxPoints()}.
	 *       Out of range or wrong type: log + skip the field (keep vanilla; do
	 *       <b>not</b> clamp, so the mod author sees their override had no effect).</li>
	 *   <li>{@code desc} — string. Wrong type: log + skip the field.</li>
	 * </ul>
	 * If at least one field is valid the override is upserted (last call wins);
	 * if none are valid nothing is stored.
	 */
	static void register(Talent talent, LuaTable tbl) {
		Integer maxPoints = parseMaxPoints(talent, tbl.get("maxPoints"));
		String desc = parseDesc(tbl.get("desc"));
		// M8d1: title override. Reads "title" then "name" (RegisterTalentFunction
		// forwards the modder-facing `name` field this way for MOD_ enum slots,
		// which have no .title properties key). M7e register_talent_override
		// never sends either, so M7e talents keep the vanilla Messages title.
		String title = parseTitle(tbl.get("title"));
		if (title == null) title = parseTitle(tbl.get("name"));
		if (maxPoints == null && desc == null && title == null) {
			Gdx.app.error(TAG, "register_talent_override '" + talent.name()
					+ "': no valid maxPoints/desc/title fields, skipping");
			return;
		}
		overrides.put(talent, new Override(maxPoints, desc, title));
	}

	private static Integer parseMaxPoints(Talent talent, LuaValue v) {
		if (v.isnil()) return null;
		if (!v.isint()) {
			Gdx.app.error(TAG, "register_talent_override '" + talent.name()
					+ "': maxPoints must be an int, skipping field (got " + v.typename() + ")");
			return null;
		}
		int value = v.toint();
		int vanilla = talent.baseMaxPoints();
		if (value < 1 || value > vanilla) {
			// Reject rather than clamp: raising would break the [0, vanilla] domain
			// assumed by vanilla formulas (div-by-zero, IntRange min>max, missed ==N gates);
			// lowering below 1 would leave the talent un-upgradable. Keep vanilla.
			Gdx.app.error(TAG, "register_talent_override '" + talent.name()
					+ "': maxPoints=" + value + " out of [1, " + vanilla
					+ "] (vanilla cap), skipping field (M7e allows lowering only; "
					+ "raising is deferred to M8)");
			return null;
		}
		return value;
	}

	private static String parseDesc(LuaValue v) {
		if (v.isnil()) return null;
		// Require a genuine Lua string. luaj's isstring() returns true for numbers
		// (they are string-coercible), but a number/table/boolean is not a real desc —
		// skip it so a typo like desc=123 is noisy rather than silently "123".
		if (!(v instanceof org.luaj.vm2.LuaString)) {
			Gdx.app.error(TAG, "register_talent_override: desc must be a string, skipping field (got "
					+ v.typename() + ")");
			return null;
		}
		return v.tojstring();
	}

	private static String parseTitle(LuaValue v) {
		if (v.isnil()) return null;
		if (!(v instanceof org.luaj.vm2.LuaString)) {
			Gdx.app.error(TAG, "register_talent_override: title must be a string, skipping field (got "
					+ v.typename() + ")");
			return null;
		}
		return v.tojstring();
	}

	/** Number of talents with an override. Used by the C3 toggle test and LuaEngine empty-scan warning. */
	public static int size() {
		return overrides.size();
	}

	/** Test/reset hook — clears every override so the next scan starts clean. */
	public static void clear() {
		overrides.clear();
	}

	/** Read-only view of overridden talents (for tests/diagnostics). */
	static java.util.Set<Talent> talents() {
		return Collections.unmodifiableSet(overrides.keySet());
	}
}
