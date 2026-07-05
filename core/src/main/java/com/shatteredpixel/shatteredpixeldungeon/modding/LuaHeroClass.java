package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Metadata for a Lua-defined hero class (M3c). The Java-side counterpart of a
 * {@code register_hero{...}} table: a plain data holder captured at registration
 * time so the rest of the engine never has to touch the Lua table directly.
 *
 * <p><b>Not</b> a {@link HeroClass} enum constant — M3c deliberately keeps the
 * enum untouched (C4: adding an enum value would require patching 40+ switch
 * statements). A Lua hero's {@code hero.heroClass} stays equal to its
 * {@link #talentSource} (the host class), and {@link #id} is carried alongside
 * via the {@code lua_class_id} bundle sidecar (D3) so the save knows it is a
 * Lua hero.
 *
 * <p>Required Lua fields: {@code id/name/talentSource/hp}. Optional:
 * {@code defenseSkill} (defaults to {@link #DEFAULT_DEFENSE_SKILL}),
 * {@code startingItems} (list of LuaItem ids, defaults to empty),
 * {@code spriteKey} (defaults to null → host sprite).
 */
public final class LuaHeroClass {

	/** Default defense skill when the Lua definition omits it (matches Hero's own default). */
	public static final int DEFAULT_DEFENSE_SKILL = 5;

	private final String id;
	private final String name;
	private final HeroClass talentSource;
	private final int hp;
	private final int defenseSkill;
	private final List<String> startingItems;
	private final String spriteKey;

	private LuaHeroClass(String id, String name, HeroClass talentSource, int hp,
	                     int defenseSkill, List<String> startingItems, String spriteKey) {
		this.id = id;
		this.name = name;
		this.talentSource = talentSource;
		this.hp = hp;
		this.defenseSkill = defenseSkill;
		this.startingItems = startingItems;
		this.spriteKey = spriteKey;
	}

	public String id() { return id; }
	public String name() { return name; }
	public HeroClass talentSource() { return talentSource; }
	public int hp() { return hp; }
	public int defenseSkill() { return defenseSkill; }
	public List<String> startingItems() { return startingItems; }
	public String spriteKey() { return spriteKey; }

	/**
	 * Build a {@link LuaHeroClass} from a {@code register_hero{...}} table.
	 * Throws {@link IllegalArgumentException} if a required field is missing or
	 * {@code talentSource} is not one of the six {@link HeroClass} names; the
	 * caller ({@link LuaEngine}) logs and skips on failure rather than crashing.
	 */
	public static LuaHeroClass hydrate(LuaTable tbl) {
		String id = tbl.get("id").checkjstring();
		String name = tbl.get("name").checkjstring();
		int hp = tbl.get("hp").checkint();
		HeroClass host = parseTalentSource(tbl.get("talentSource").checkjstring());
		int defense = tbl.get("defenseSkill").optint(DEFAULT_DEFENSE_SKILL);
		List<String> items = parseStartingItems(tbl.get("startingItems"));
		String sprite = tbl.get("spriteKey").optjstring(null);
		return new LuaHeroClass(id, name, host, hp, defense, items, sprite);
	}

	private static HeroClass parseTalentSource(String raw) {
		if (raw == null) {
			throw new IllegalArgumentException("talentSource is required (one of WARRIOR/MAGE/ROGUE/HUNTRESS/DUELIST/CLERIC)");
		}
		try {
			return HeroClass.valueOf(raw.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(
					"talentSource '" + raw + "' is not a HeroClass (must be WARRIOR/MAGE/ROGUE/HUNTRESS/DUELIST/CLERIC)", e);
		}
	}

	private static List<String> parseStartingItems(LuaValue v) {
		if (!v.istable()) return Collections.emptyList();
		LuaTable t = (LuaTable) v;
		List<String> out = new ArrayList<>();
		for (LuaValue key : t.keys()) {
			LuaValue item = t.get(key);
			if (item.isstring()) out.add(item.checkjstring());
		}
		return Collections.unmodifiableList(out);
	}
}
