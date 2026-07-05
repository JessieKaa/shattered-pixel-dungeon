package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.RatKing;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.watabou.utils.Bundle;
import com.watabou.utils.Random;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link Level} whose map/mobs/items come from a JSON document instead of procedural
 * generation. This is the M4a runtime foundation for town hubs, Lua-defined levels, and
 * Tiled-imported maps — every custom-map feature consumes this primitive.
 *
 * <h3>Level lifecycle (where DataDrivenLevel injects)</h3>
 *
 * <p>Upstream {@link Level#create()} does:
 * <ol>
 *   <li>{@code Random.pushGenerator(Dungeon.seedCurDepth())}</li>
 *   <li><b>depth-driven random pre-spawn</b> (food/potions/scrolls + feeling roll) — only on
 *       non-boss branch-0 floors. A fixed map does not want this, so DataDrivenLevel
 *       overrides {@code create()} to skip it.</li>
 *   <li>{@code while (!build())} — {@link #build()} allocates the map and lays tiles.</li>
 *   <li>{@code buildFlagMaps()} / {@code cleanWalls()} — derived from {@code map[]}, run by
 *       the overridden create() exactly as upstream does.</li>
 *   <li>{@code createMobs()} / {@code createItems()} — populate from JSON.</li>
 * </ol>
 *
 * <p>{@code build()} <b>must</b> call {@link #setSize(int, int)} itself — {@code create()}
 * does not allocate {@code map[]} on its behalf.
 *
 * <h3>Entrance / exit — no {@link com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition}</h3>
 *
 * <p>Any {@code LevelTransition} is interactable: stepping on its cell fires
 * {@code activateTransition()} → {@code InterlevelScene}, and its constructor auto-fills
 * {@code destDepth/destBranch} from the live {@code Dungeon.depth} — which inside a SafeZone
 * is still a real floor, so the hero could be flung to a real level. To stay MVP-safe
 * (no {@code InterlevelScene} coupling, no depth pollution) DataDrivenLevel adds <b>no
 * transitions at all</b> and instead overrides {@link #entrance()} to return the JSON
 * entrance cell directly. {@code getTransition(cell)} returning null means
 * {@code activateTransition} can never fire — the player leaves only via the debug button.
 *
 * <h3>Persistence (R3)</h3>
 *
 * <p>Production never saves a DataDrivenLevel: {@link #isEphemeral()} returns true (when
 * {@code safe}) and {@code Dungeon.saveAll} early-returns on it, so the real depth's save
 * is never overwritten. The store/restore path is exercised by the unit test and stays
 * correct for the day a non-ephemeral data-driven level (e.g. a real Lua dungeon) needs it:
 * {@code lua_level_id} + {@code entranceCell} are persisted alongside {@link Level}'s own
 * map/width/height/mobs/heaps fields, and restore re-hydrates from {@link LuaLevelRegistry}.
 */
public class DataDrivenLevel extends Level {

	private static final String TAG = "DataDrivenLevel";
	private static final String LUA_LEVEL_ID = "lua_level_id";
	private static final String ENTRANCE_CELL = "entrance_cell";
	/** Mob-spec type prefix that routes a {@link LuaNpc} into a level via the registry. */
	private static final String LUA_NPC_PREFIX = "lua_npc:";

	private String luaLevelId;
	private int entranceCell;
	private boolean safe;

	// JSON-source fields consumed once during build()/createMobs()/createItems().
	// Not bundled — the bundle carries map[]/mobs/heaps, the runtime map is enough on restore.
	private int jsonWidth;
	private int jsonHeight;
	private String[] jsonTiles;
	private final List<MobSpec> mobSpecs = new ArrayList<>();
	private final List<ItemSpec> itemSpecs = new ArrayList<>();

	public DataDrivenLevel() {
		// no-arg ctor for Bundle restore (Reflection / newInstance).
	}

	/**
	 * Parse a JSON level definition into a fresh, un-{@code create()}-ed level. The caller
	 * (production: {@link LuaLevelService}; tests: direct) drives {@link #create()} after.
	 * Throws {@link IllegalArgumentException} on a structurally invalid definition so the
	 * caller can surface a clean error rather than crash mid-{@code create()}.
	 */
	public static DataDrivenLevel fromJsonValue(JsonValue root, String id) {
		if (root == null) {
			throw new IllegalArgumentException("level json is null");
		}
		int w = root.getInt("width");
		int h = root.getInt("height");
		if (w <= 0 || h <= 0) {
			throw new IllegalArgumentException("invalid level size: " + w + "x" + h);
		}
		DataDrivenLevel lvl = new DataDrivenLevel();
		lvl.luaLevelId = id;
		lvl.jsonWidth = w;
		lvl.jsonHeight = h;
		lvl.safe = root.getBoolean("safe", true);

		JsonValue tiles = root.require("tiles");
		if (tiles.size != w * h) {
			throw new IllegalArgumentException("tiles length " + tiles.size + " != width*height " + (w * h));
		}
		lvl.jsonTiles = new String[w * h];
		int i = 0;
		for (JsonValue t = tiles.child; t != null; t = t.next) {
			lvl.jsonTiles[i++] = t.asString();
		}

		lvl.entranceCell = root.getInt("entrance");
		if (lvl.entranceCell < 0 || lvl.entranceCell >= w * h) {
			throw new IllegalArgumentException("entrance cell out of bounds: " + lvl.entranceCell);
		}

		JsonValue mobsArr = root.get("mobs");
		for (JsonValue m = mobsArr != null ? mobsArr.child : null; m != null; m = m.next) {
			lvl.mobSpecs.add(new MobSpec(m.getString("type"), m.getInt("pos")));
		}
		JsonValue itemsArr = root.get("items");
		for (JsonValue it = itemsArr != null ? itemsArr.child : null; it != null; it = it.next) {
			lvl.itemSpecs.add(new ItemSpec(it.getString("type"), it.getInt("pos"), it.getInt("quantity", 1)));
		}
		return lvl;
	}

	/** Convenience: read a JSON file from {@code assets} and parse it. */
	public static DataDrivenLevel fromAsset(String path, String id) {
		try {
			String text = Gdx.files.internal(path).readString("UTF-8");
			JsonValue root = new JsonReader().parse(text);
			return fromJsonValue(root, id);
		} catch (Exception e) {
			Gdx.app.error(TAG, "failed to load level asset " + path, e);
			return null;
		}
	}

	@Override
	public boolean isEphemeral() {
		return safe;
	}

	/**
	 * Mirror of {@link Level#create()} with the depth-driven random pre-spawn / feeling roll
	 * stripped out (a fixed map neither needs nor wants random food/potions/scrolls dropped
	 * onto it). Keeps the Random push/pop + build/buildFlagMaps/cleanWalls/createMobs/
	 * createItems skeleton identical to upstream so the rest of the engine sees a fully
	 * initialised level.
	 */
	@Override
	public void create() {
		Random.pushGenerator(Dungeon.seedCurDepth());

		width = height = length = 0;
		transitions = new ArrayList<>();
		mobs = new java.util.HashSet<>();
		heaps = new com.watabou.utils.SparseArray<>();
		blobs = new java.util.HashMap<>();
		plants = new com.watabou.utils.SparseArray<>();
		traps = new com.watabou.utils.SparseArray<>();
		customTiles = new ArrayList<>();
		customWalls = new ArrayList<>();

		build();

		buildFlagMaps();
		cleanWalls();

		createMobs();
		createItems();

		Random.popGenerator();
	}

	@Override
	protected boolean build() {
		setSize(jsonWidth, jsonHeight);
		for (int i = 0; i < length(); i++) {
			map[i] = tileNameToId(jsonTiles[i]);
		}
		// No LevelTransition added — see class javadoc (entrance() override below).
		return true;
	}

	@Override
	public int entrance() {
		return entranceCell;
	}

	@Override
	protected void createMobs() {
		for (MobSpec spec : mobSpecs) {
			// M4b: lua_npc:<id> instantiates from LuaNpcRegistry. Handled as a
			// prefix branch BEFORE the MOB_TYPES lookup because the type carries a
			// dynamic id — it cannot live in the static class whitelist.
			if (spec.type != null && spec.type.startsWith(LUA_NPC_PREFIX)) {
				String npcId = spec.type.substring(LUA_NPC_PREFIX.length());
				LuaNpc npc = LuaNpcRegistry.create(npcId);
				if (npc == null) {
					Gdx.app.error(TAG, "unknown lua_npc id: " + npcId + " — skipping");
					continue;
				}
				if (spec.pos < 0 || spec.pos >= length() || !passable[spec.pos]) {
					Gdx.app.error(TAG, "lua_npc " + npcId + " pos " + spec.pos + " invalid — skipping");
					continue;
				}
				npc.pos = spec.pos;
				mobs.add(npc);
				continue;
			}
			Class<? extends Mob> cls = MOB_TYPES.get(spec.type);
			if (cls == null) {
				Gdx.app.error(TAG, "unknown mob type: " + spec.type + " — skipping");
				continue;
			}
			if (spec.pos < 0 || spec.pos >= length() || !passable[spec.pos]) {
				Gdx.app.error(TAG, "mob " + spec.type + " pos " + spec.pos + " invalid — skipping");
				continue;
			}
			Mob mob = (Mob) Reflection.newInstance(cls);
			mob.pos = spec.pos;
			mobs.add(mob);
		}
	}

	@Override
	protected void createItems() {
		for (ItemSpec spec : itemSpecs) {
			ItemFactory f = ITEM_TYPES.get(spec.type);
			if (f == null) {
				Gdx.app.error(TAG, "unknown item type: " + spec.type + " — skipping");
				continue;
			}
			if (spec.pos < 0 || spec.pos >= length()) {
				Gdx.app.error(TAG, "item " + spec.type + " pos " + spec.pos + " invalid — skipping");
				continue;
			}
			Item item = f.create(spec.quantity);
			if (item != null) drop(item, spec.pos);
		}
	}

	/** SafeZone: never spawn a respawner. */
	@Override
	public Actor addRespawner() {
		return null;
	}

	@Override
	public int mobLimit() {
		return 0;
	}

	@Override
	public String tilesTex() {
		return Assets.Environment.TILES_SEWERS;
	}

	@Override
	public String waterTex() {
		return Assets.Environment.WATER_SEWERS;
	}

	// ---- persistence ----

	@Override
	public void storeInBundle(Bundle bundle) {
		super.storeInBundle(bundle);
		if (luaLevelId != null) bundle.put(LUA_LEVEL_ID, luaLevelId);
		bundle.put(ENTRANCE_CELL, entranceCell);
	}

	@Override
	public void restoreFromBundle(Bundle bundle) {
		super.restoreFromBundle(bundle);
		if (bundle.contains(LUA_LEVEL_ID)) {
			luaLevelId = bundle.getString(LUA_LEVEL_ID);
			// Re-attach definition if registered (Lua-driven levels). JSON-file levels carry
			// no extra runtime state beyond what Level already bundled, so a missing entry
			// is non-fatal.
			LuaLevelRegistry.getTable(luaLevelId);
		}
		entranceCell = bundle.getInt(ENTRANCE_CELL);
	}

	public String luaLevelId() {
		return luaLevelId;
	}

	// ---- tile / mob / item name maps ----

	private static final Map<String, Integer> TILE_NAMES = new HashMap<>();
	static {
		TILE_NAMES.put("chasm", Terrain.CHASM);
		TILE_NAMES.put("empty", Terrain.EMPTY);
		TILE_NAMES.put("floor", Terrain.EMPTY);   // friendly alias
		TILE_NAMES.put("grass", Terrain.GRASS);
		TILE_NAMES.put("empty_well", Terrain.EMPTY_WELL);
		TILE_NAMES.put("wall", Terrain.WALL);
		TILE_NAMES.put("door", Terrain.DOOR);
		TILE_NAMES.put("open_door", Terrain.OPEN_DOOR);
		TILE_NAMES.put("entrance", Terrain.ENTRANCE);
		TILE_NAMES.put("entrance_sp", Terrain.ENTRANCE_SP);
		TILE_NAMES.put("exit", Terrain.EXIT);
		TILE_NAMES.put("embers", Terrain.EMBERS);
		TILE_NAMES.put("locked_door", Terrain.LOCKED_DOOR);
		TILE_NAMES.put("pedestal", Terrain.PEDESTAL);
		TILE_NAMES.put("wall_deco", Terrain.WALL_DECO);
		TILE_NAMES.put("barricade", Terrain.BARRICADE);
		TILE_NAMES.put("empty_sp", Terrain.EMPTY_SP);
		TILE_NAMES.put("high_grass", Terrain.HIGH_GRASS);
		TILE_NAMES.put("furrowed_grass", Terrain.FURROWED_GRASS);
		TILE_NAMES.put("secret_door", Terrain.SECRET_DOOR);
		TILE_NAMES.put("secret_trap", Terrain.SECRET_TRAP);
		TILE_NAMES.put("trap", Terrain.TRAP);
		TILE_NAMES.put("inactive_trap", Terrain.INACTIVE_TRAP);
		TILE_NAMES.put("empty_deco", Terrain.EMPTY_DECO);
		TILE_NAMES.put("locked_exit", Terrain.LOCKED_EXIT);
		TILE_NAMES.put("unlocked_exit", Terrain.UNLOCKED_EXIT);
		TILE_NAMES.put("well", Terrain.WELL);
		TILE_NAMES.put("bookshelf", Terrain.BOOKSHELF);
		TILE_NAMES.put("alchemy", Terrain.ALCHEMY);
		TILE_NAMES.put("water", Terrain.WATER);
		TILE_NAMES.put("statue", Terrain.STATUE);
		TILE_NAMES.put("statue_sp", Terrain.STATUE_SP);
		TILE_NAMES.put("custom_deco", Terrain.CUSTOM_DECO);
		TILE_NAMES.put("custom_deco_empty", Terrain.CUSTOM_DECO_EMPTY);
		TILE_NAMES.put("crystal_door", Terrain.CRYSTAL_DOOR);
		TILE_NAMES.put("region_deco", Terrain.REGION_DECO);
		TILE_NAMES.put("region_deco_alt", Terrain.REGION_DECO_ALT);
		TILE_NAMES.put("mine_crystal", Terrain.MINE_CRYSTAL);
		TILE_NAMES.put("mine_boulder", Terrain.MINE_BOULDER);
		TILE_NAMES.put("hero_lkd_dr", Terrain.HERO_LKD_DR);
	}

	private static int tileNameToId(String name) {
		if (name == null) return Terrain.WALL;
		Integer id = TILE_NAMES.get(name.toLowerCase());
		if (id == null) {
			Gdx.app.error(TAG, "unknown tile name '" + name + "' — defaulting to wall");
			return Terrain.WALL;
		}
		return id;
	}

	/** Test-visible tile mapping. */
	static int tileNameToIdForTest(String name) {
		return tileNameToId(name);
	}

	private static final Map<String, Class<? extends Mob>> MOB_TYPES = new HashMap<>();
	static {
		MOB_TYPES.put("rat_king", RatKing.class);
	}

	private interface ItemFactory {
		Item create(int quantity);
	}

	private static final Map<String, ItemFactory> ITEM_TYPES = new HashMap<>();
	static {
		ITEM_TYPES.put("gold", Gold::new);
	}

	// ---- specs ----

	private static final class MobSpec {
		final String type;
		final int pos;
		MobSpec(String type, int pos) {
			this.type = type;
			this.pos = pos;
		}
	}

	private static final class ItemSpec {
		final String type;
		final int pos;
		final int quantity;
		ItemSpec(String type, int pos, int quantity) {
			this.type = type;
			this.pos = pos;
			this.quantity = quantity;
		}
	}

	// test-only accessors
	static int mobTypeCount() { return MOB_TYPES.size(); }
	static int itemTypeCount() { return ITEM_TYPES.size(); }
}
