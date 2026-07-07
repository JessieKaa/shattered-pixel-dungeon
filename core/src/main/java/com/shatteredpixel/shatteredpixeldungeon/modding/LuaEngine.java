package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ResourceFinder;

import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Minimal luaj engine for the Lua modding pipeline.
 *
 * <p>M1 change: globals now come from {@link LuaSandbox#exposedGlobals()} — a
 * curated set with every dangerous library/function stripped (io/os/luajava/load/
 * loadfile/dofile/loadstring/require/...). Because {@code dofile} is no longer
 * available to Lua, item-script loading moved to the Java side: this engine
 * enumerates {@code mods/<id>/scripts/items/*.lua} for each enabled mod and
 * compiles each via the host-side {@code Globals.load} (a Java method,
 * independent of the removed Lua global).
 *
 * <p>M5c: all Lua content (items/mobs/allies/heroes/spells/npcs/shops) is scoped
 * per enabled mod under {@code mods/<id>/scripts/<type>/}. A disabled mod
 * contributes zero Lua content — the C3 regression baseline (vanilla playthrough
 * loads no Lua content when every mod is {@code default_enabled=false}).
 *
 * <p>Exposes a single {@code register_item(table)} global so Lua scripts can hand
 * item definitions to Java, and runs {@code scripts/init.lua} once on game start
 * for any pure-Lua bootstrap (init.lua must not rely on dofile).
 */
public class LuaEngine implements ResourceFinder {

	private static final String TAG = "LuaEngine";
	static final String INIT_SCRIPT = "scripts/init.lua";

	private static LuaEngine instance;

	private Globals globals;
	private boolean initialized = false;

	private LuaEngine() { }

	public static synchronized LuaEngine instance() {
		if (instance == null) instance = new LuaEngine();
		return instance;
	}

	/** Bootstrap the engine and run {@code scripts/init.lua}. Safe to call once on game create(). */
	public static synchronized void init() {
		instance().initInternal();
	}

	/**
	 * Test-only: drops the singleton so the next {@link #init()} re-scans scripts.
	 * Production code never calls this — the engine is initialised once per game
	 * session. Needed because JUnit reuses the JVM across test classes and the
	 * {@code initialized} flag would otherwise short-circuit re-init after
	 * {@link LuaItemRegistry#clear()}.
	 */
	static synchronized void resetForTests() {
		instance = null;
	}

	private synchronized void initInternal() {
		if (initialized) return;
		try {
			globals = LuaSandbox.exposedGlobals();
			globals.finder = this;
			globals.set("register_item", new RegisterItemFunction());
			// M3a: register_mob global, mirroring register_item for hostile Lua mobs.
			globals.set("register_mob", new RegisterMobFunction());
			// M3b: register_ally global, mirroring register_mob for friendly Lua pets
			// (LuaAlly extends DirectableAlly — inherit follow/defend/attack + intelligentAlly).
			globals.set("register_ally", new RegisterAllyFunction());
			// M3c: register_hero global, mirroring register_mob/ally for Lua-defined hero
			// classes. The Java-side LuaHeroClass captures the metadata; hero.heroClass stays
			// equal to talentSource (host) and lua_class_id is the sidecar marker (D3).
			globals.set("register_hero", new RegisterHeroFunction());
			// M3d: register_spell global, mirroring register_item for Lua-defined consumable
			// spells (LuaSpell extends Item — detach-on-use + onUse(heroId) callback).
			globals.set("register_spell", new RegisterSpellFunction());
			// M4b: register_npc global, mirroring register_mob/ally for Lua-defined interactive
			// NPCs (LuaNpc extends NPC — passive/invincible + onInteract(heroId) callback). NPC
			// stats are fixed by NPC's base initialiser, so the Lua table only carries
			// id/name/sprite + the onInteract callback.
			globals.set("register_npc", new RegisterNpcFunction());
			// M4c: register_shop global, mirroring register_npc for Lua-defined shop NPCs
			// (LuaShopNpc extends LuaNpc — passive/invincible + custom purchase window driven
			// by the items pool in the Lua table). Stored as-is so LuaShopNpc can re-hydrate
			// name/sprite/items after a save/load cycle. Per-item id/price validation is
			// delegated to LuaShopNpc.hydrate (skips bad entries, never fails the whole shop).
			globals.set("register_shop", new RegisterShopFunction());
			// M4a: register_level global. Level geometry lives in mods/levels/<id>.json;
			// this registers the id so Bundle restore (lua_level_id) can re-attach and so a
			// future Lua level graph can reference it. Optional `path` overrides the default
			// mods/levels/<id>.json location (consumed by LuaLevelService in M4b).
			globals.set("register_level", new RegisterLevelFunction());
			// M6c: register_buff global, mirroring register_spell for Lua-defined buffs.
			// The table is stored as-is so LuaBuff can re-hydrate name/icon/act/attachTo/
			// detach/immunities/onRestore after a save/load cycle (the table is the single
			// source of truth, same pattern as LuaSpell/LuaItem).
			globals.set("register_buff", new RegisterBuffFunction());
			// M7e: register_talent_override global. Mirrors register_buff but targets a
			// Talent enum constant (id-resolved via Talent.valueOf) and captures only
			// maxPoints(lower-only)/desc into LuaTalentOverride. See LuaTalentOverride javadoc
			// for the lower-only rationale (raising breaks the [0, vanilla] formula domain).
			globals.set("register_talent_override", new RegisterTalentOverrideFunction());
			// M8d1: register_talent global. Activates a pre-declared MOD_-prefixed Talent
			// enum slot (e.g. MOD_EXAMPLE_TALENT, declared in Talent.java) into a class+tier
			// (D6(b) MVP). id/tier(1-2)/class are validated here; desc/maxPoints/title(name)
			// are forwarded to LuaTalentOverride so Talent.maxPoints()/desc()/title() reuse
			// the M7e fallback. LuaTalentRegistry owns the tier injection.
			globals.set("register_talent", new RegisterTalentFunction());
			// M2: inject the narrow RPD.* surface (affectBuff/damageChar/GLog/...).
			// Lua never gets a Char object — only int ids resolved via Actor.findById.
			globals.set("RPD", RpdApi.build());

			InputStream in = findResource(INIT_SCRIPT);
			if (in != null) {
				globals.load(new InputStreamReader(in, "UTF-8"), INIT_SCRIPT).call();
			} else {
				Gdx.app.error(TAG, INIT_SCRIPT + " not found in assets");
			}

			// M5c: each loader scans mods/<id>/scripts/<type>/ for every enabled mod. Disabled mods
			// contribute no Lua content; dofile is stripped from the sandbox (N2), so the host
			// compiles each script itself.
			loadItemScripts();
			loadMobScripts();
			loadAllyScripts();
			loadHeroScripts();
			loadSpellScripts();
			loadNpcScripts();
			loadShopScripts();
			loadBuffScripts();
			loadTalentScripts();

			// M5b: load each enabled mod's entry script (Remixed-style init.lua that calls
			// register_*). ModRegistry.all() lazy-scans on first use (production: triggers scan here;
			// tests: honors a pre-seeded scanDir so init never clobbers injected fixtures). Disabled
			// mods and mods without an `entry` manifest field are skipped. Per-mod failures are logged
			// and continue, never fatal — one broken entry cannot crash startup or block other mods.
			loadModEntryScripts();

			initialized = true;
		} catch (Exception e) {
			Gdx.app.error(TAG, "init failed", e);
		}
	}

	/**
	 * Enumerate {@code mods/<id>/scripts/items/*.lua} for each enabled mod and compile each in
	 * the sandbox. Errors per-file, never fatal.
	 *
	 * <p>M5c: item scripts (like all Lua content) are scoped per mod. {@code ModRegistry.all()}
	 * lazy-scans on first use; disabled mods are skipped so a disabled mod contributes zero Lua
	 * content (C3 regression baseline — vanilla playthrough loads no Lua items).
	 *
	 * <p>dofile is stripped from the sandbox (N2), so the host loads each item script itself.
	 * Enumeration is two-stage: the classpath URL is checked first because libgdx's headless/LWJGL3
	 * {@code FileHandle.list()} cannot list an {@code Internal} directory that only exists on the
	 * classpath (it returns an empty array); when the classpath entry is a real filesystem
	 * directory (tests and desktop dev runs) we list it directly. The libgdx fallback covers
	 * Android {@code AssetManager.list} and packaged-jar runs.
	 */
	private void loadItemScripts() {
		for (ModManifest mod : ModRegistry.all()) {
			if (!ModRegistry.isEnabled(mod.id)) continue;
			loadScriptsFrom("mods/" + mod.id + "/scripts/items", "Lua items (" + mod.id + ")", LuaItemRegistry::size);
		}
	}

	/**
	 * M3a: enumerate {@code mods/<id>/scripts/mobs/*.lua} for each enabled mod and compile each.
	 * Same enabled-mod iteration + two-stage listing as items (M5c). Lua mobs are not part of the
	 * vanilla spawn pool — they only enter a level via {@code RPD.spawnMob}.
	 */
	private void loadMobScripts() {
		for (ModManifest mod : ModRegistry.all()) {
			if (!ModRegistry.isEnabled(mod.id)) continue;
			loadScriptsFrom("mods/" + mod.id + "/scripts/mobs", "Lua mobs (" + mod.id + ")", LuaMobRegistry::size);
		}
	}

	/**
	 * M3b: enumerate {@code mods/<id>/scripts/allies/*.lua} for each enabled mod and compile each.
	 * Same enabled-mod iteration + two-stage listing as items/mobs (M5c). Lua allies are not part
	 * of the vanilla spawn pool — they only enter a level via {@code RPD.spawnAlly}.
	 */
	private void loadAllyScripts() {
		for (ModManifest mod : ModRegistry.all()) {
			if (!ModRegistry.isEnabled(mod.id)) continue;
			loadScriptsFrom("mods/" + mod.id + "/scripts/allies", "Lua allies (" + mod.id + ")", LuaAllyRegistry::size);
		}
	}

	/**
	 * M3c: enumerate {@code mods/<id>/scripts/heroes/*.lua} for each enabled mod and compile each.
	 * Same enabled-mod iteration + two-stage listing as items/mobs/allies (M5c). Lua heroes are
	 * not part of the vanilla {@code HeroClass.values()} roster — {@code HeroSelectScene} renders
	 * them as extra buttons and {@code Dungeon.init} routes to {@code Hero.initLuaHero} via
	 * {@link LuaHeroService} when one is selected.
	 */
	private void loadHeroScripts() {
		for (ModManifest mod : ModRegistry.all()) {
			if (!ModRegistry.isEnabled(mod.id)) continue;
			loadScriptsFrom("mods/" + mod.id + "/scripts/heroes", "Lua heroes (" + mod.id + ")", LuaHeroRegistry::size);
		}
	}

	/**
	 * M3d: enumerate {@code mods/<id>/scripts/spells/*.lua} for each enabled mod and compile each.
	 * Same enabled-mod iteration + two-stage listing as items/mobs/allies/heroes (M5c). LuaSpell
	 * is not part of the vanilla loot pool — scripts only define consumable behaviour; spawning
	 * them into inventory is left to mod/cheat console or future milestones.
	 */
	private void loadSpellScripts() {
		for (ModManifest mod : ModRegistry.all()) {
			if (!ModRegistry.isEnabled(mod.id)) continue;
			loadScriptsFrom("mods/" + mod.id + "/scripts/spells", "Lua spells (" + mod.id + ")", LuaSpellRegistry::size);
		}
	}

	/**
	 * M4b: enumerate {@code mods/<id>/scripts/npcs/*.lua} for each enabled mod and compile each.
	 * Same enabled-mod iteration + two-stage listing as the other registries (M5c). Lua NPCs are
	 * not part of the vanilla spawn pool — they only enter a level via a {@code lua_npc:<id>}
	 * entry in a {@link DataDrivenLevel} mob spec.
	 */
	private void loadNpcScripts() {
		for (ModManifest mod : ModRegistry.all()) {
			if (!ModRegistry.isEnabled(mod.id)) continue;
			loadScriptsFrom("mods/" + mod.id + "/scripts/npcs", "Lua npcs (" + mod.id + ")", LuaNpcRegistry::size);
		}
	}

	/**
	 * M4c: enumerate {@code mods/<id>/scripts/shops/*.lua} for each enabled mod and compile each.
	 * Same enabled-mod iteration + two-stage listing as the other registries (M5c). Lua shops are
	 * not part of the vanilla spawn pool — they only enter a level via a {@code lua_shop:<id>}
	 * entry in a {@link DataDrivenLevel} mob spec.
	 */
	private void loadShopScripts() {
		for (ModManifest mod : ModRegistry.all()) {
			if (!ModRegistry.isEnabled(mod.id)) continue;
			loadScriptsFrom("mods/" + mod.id + "/scripts/shops", "Lua shops (" + mod.id + ")", LuaShopRegistry::size);
		}
	}

	/**
	 * M6c: enumerate {@code mods/<id>/scripts/buffs/*.lua} for each enabled mod and compile each.
	 * Same enabled-mod iteration + two-stage listing as the other registries (M5c). Lua buffs are
	 * not part of the vanilla buff set — they only enter play via {@code RPD.affectBuff} (Lua id)
	 * / {@code RPD.permanentBuff} / {@code RPD.removeBuff}, or by Lua items/mobs attaching them.
	 * Disabled mods contribute zero Lua buffs (C3 regression baseline).
	 */
	private void loadBuffScripts() {
		for (ModManifest mod : ModRegistry.all()) {
			if (!ModRegistry.isEnabled(mod.id)) continue;
			loadScriptsFrom("mods/" + mod.id + "/scripts/buffs", "Lua buffs (" + mod.id + ")", LuaBuffRegistry::size);
		}
	}

	/**
	 * M7e: enumerate {@code mods/<id>/scripts/talents/*.lua} for each enabled mod and
	 * compile each. Same enabled-mod iteration + two-stage listing as the other loaders
	 * (M5c). Talent overrides are id-resolved against the {@link com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent}
	 * enum and stored in {@link LuaTalentOverride}; a disabled mod contributes zero
	 * overrides (C3 regression baseline — vanilla playthrough loads no talent overrides).
	 */
	private void loadTalentScripts() {
		for (ModManifest mod : ModRegistry.all()) {
			if (!ModRegistry.isEnabled(mod.id)) continue;
			loadScriptsFrom("mods/" + mod.id + "/scripts/talents", "Lua talent overrides (" + mod.id + ")", LuaTalentOverride::size);
		}
	}

	/**
	 * M5b: load each enabled mod's entry script. The entry path comes from {@link ModManifest#entry}
	 * (validated at parse time to be a relative, traversal-free {@code .lua} path). For every mod in
	 * {@link ModRegistry#all()} that is enabled and declares an entry, compile and run
	 * {@code mods/<id>/<entry>} against the same sandbox globals (so it can call register_*). Disabled
	 * mods, mods without an entry, and mods whose entry file is missing are skipped — the latter two
	 * log and continue so one bad entry cannot block other mods or crash startup.
	 *
	 * <p>{@link ModRegistry#all()} is the data source (not an explicit {@code scan()} call) because
	 * {@code all()} lazy-scans only when uninitialized: production gets a fresh scan here on first
	 * init, while headless tests can pre-seed the registry via the package-private
	 * {@code ModRegistry.scanDir} without init clobbering their fixture.
	 */
	private void loadModEntryScripts() {
		for (ModManifest mod : ModRegistry.all()) {
			if (!ModRegistry.isEnabled(mod.id)) continue;
			if (mod.entry == null || mod.entry.isEmpty()) continue;
			String path = "mods/" + mod.id + "/" + mod.entry;
			String chunkName = "mod:" + mod.id + ":" + mod.entry;
			try (InputStream in = findResource(path)) {
				if (in == null) {
					Gdx.app.error(TAG, "mod " + mod.id + " entry not found: " + path);
					continue;
				}
				globals.load(new InputStreamReader(in, "UTF-8"), chunkName).call();
			} catch (Exception e) {
				Gdx.app.error(TAG, "mod " + mod.id + " entry load failed: " + path, e);
			}
		}
	}

	/**
	 * Shared loader for item/mob script directories. Compiles each {@code *.lua}
	 * file in {@code dir} via the host-side {@code Globals.load} (sandbox-safe —
	 * Lua-side {@code dofile} is stripped). Per-file errors are logged, never
	 * fatal. {@code registrySize} is used only for the "nothing registered" warning.
	 */
	private void loadScriptsFrom(String dir, String label, java.util.function.IntSupplier registrySize) {
		String[] names = listScriptNames(dir);
		if (names.length == 0) {
			Gdx.app.error(TAG, dir + " contains no .lua files; no " + label + " registered");
			return;
		}
		java.util.Arrays.sort(names);
		for (String n : names) {
			String path = dir + "/" + n;
			try (InputStream in = findResource(path)) {
				if (in == null) {
					Gdx.app.error(TAG, "Script " + path + " could not be opened");
					continue;
				}
				globals.load(new InputStreamReader(in, "UTF-8"), path).call();
			} catch (Exception e) {
				Gdx.app.error(TAG, "Failed to load " + path, e);
			}
		}
		if (registrySize.getAsInt() == 0) {
			Gdx.app.error(TAG, "No " + label + " registered after scanning " + dir);
		}
	}

	/**
	 * Lists {@code *.lua} filenames under a scripts directory.
	 * Stage 1: classpath-as-filesystem (works in tests + unpacked desktop runs).
	 * Stage 2: libgdx {@code FileHandle.list()} (Android AssetManager, packaged jars).
	 */
	private String[] listScriptNames(String dir) {
		try {
			java.net.URL dirUrl = LuaEngine.class.getClassLoader().getResource(dir);
			if (dirUrl != null && "file".equals(dirUrl.getProtocol())) {
				java.io.File dirFile = new java.io.File(dirUrl.toURI());
				java.io.File[] files = dirFile.listFiles();
				if (files != null) {
					java.util.List<String> out = new java.util.ArrayList<>();
					for (java.io.File f : files) {
						if (f.isFile() && f.getName().endsWith(".lua")) out.add(f.getName());
					}
					if (!out.isEmpty()) return out.toArray(new String[0]);
				}
			}
		} catch (Exception e) {
			Gdx.app.error(TAG, "Classpath-FS enumeration of " + dir + " failed, falling back", e);
		}
		// Fallback: libgdx FileHandle.list() (Android/packaged).
		try {
			FileHandle dirHandle = Gdx.files.internal(dir);
			if (dirHandle != null && dirHandle.exists()) {
				FileHandle[] kids = dirHandle.list();
				java.util.List<String> out = new java.util.ArrayList<>();
				for (FileHandle k : kids) {
					if (k.name().endsWith(".lua")) out.add(k.name());
				}
				return out.toArray(new String[0]);
			}
		} catch (Exception e) {
			Gdx.app.error(TAG, "Gdx fallback list of " + dir + " failed", e);
		}
		return new String[0];
	}

	/** Evaluate a Lua source string. Returns NIL on any failure (errors are logged, never thrown). */
	public LuaValue eval(String source, String chunkName) {
		if (globals == null) return LuaValue.NIL;
		try {
			return globals.load(source, chunkName).call();
		} catch (Exception e) {
			Gdx.app.error(TAG, "eval " + chunkName + " failed", e);
			return LuaValue.NIL;
		}
	}

	public Globals globals() {
		return globals;
	}

	@Override
	public InputStream findResource(String filename) {
		try {
			FileHandle fh = Gdx.files.internal(filename);
			if (fh != null && fh.exists()) return fh.read();
		} catch (Exception e) {
			Gdx.app.error(TAG, "findResource " + filename + " failed", e);
		}
		return null;
	}

	/** Test-only: install the {@code register_*} globals + RPD onto an ad-hoc {@link Globals}
	 *  instance so a unit test can exercise {@code register_buff}/{@code register_item}
	 *  validation without driving a full {@link #init()} scan. Production wiring lives in
	 *  {@link #initInternal()}; this just mirrors the global setup half. */
	static void installGlobalsForTests(Globals g) {
		g.set("register_item", new RegisterItemFunction());
		g.set("register_mob", new RegisterMobFunction());
		g.set("register_ally", new RegisterAllyFunction());
		g.set("register_hero", new RegisterHeroFunction());
		g.set("register_spell", new RegisterSpellFunction());
		g.set("register_npc", new RegisterNpcFunction());
		g.set("register_shop", new RegisterShopFunction());
		g.set("register_level", new RegisterLevelFunction());
		g.set("register_buff", new RegisterBuffFunction());
		g.set("register_talent_override", new RegisterTalentOverrideFunction());
		g.set("RPD", RpdApi.build());
	}

	/** The {@code register_item(table)} global handed to Lua. Validates required fields, logs and skips on bad input. */
	private static class RegisterItemFunction extends OneArgFunction {
		@Override
		public LuaValue call(LuaValue arg) {
			try {
				if (!arg.istable()) {
					Gdx.app.error(TAG, "register_item: expected a table, got " + arg.typename());
					return NIL;
				}
				LuaTable tbl = arg.checktable();
				String id = tbl.get("id").checkjstring();
				tbl.get("name").checkjstring();
				// M6d: materials (type/kind = "material") are plain Items, not weapons,
				// so they have no tier. Everything else keeps the weapon contract.
				String kind = tbl.get("type").optjstring(tbl.get("kind").optjstring(""));
				if (!"material".equalsIgnoreCase(kind)) {
					tbl.get("tier").checkint();
				}
				// M2: image is optional (defaults to 0). Callback fields
				// (attackProc/onEquip/onDeactivate) are plain table entries and
				// need no validation here — LuaItemCallbacks handles missing ones.
				tbl.get("image").optint(0);
				LuaItemRegistry.register(id, tbl);
			} catch (Exception e) {
				Gdx.app.error(TAG, "register_item rejected a malformed definition", e);
			}
			return NIL;
		}
	}

	/**
	 * The {@code register_spell(table)} global handed to Lua (M3d). Mirrors
	 * {@link RegisterItemFunction}: validates required fields ({@code id/name})
	 * and skips on bad input. {@code image} is optional (defaults to 0); the
	 * {@code onUse} callback field is a plain table entry validated lazily by
	 * {@link LuaItemCallbacks}.
	 */
	private static class RegisterSpellFunction extends OneArgFunction {
		@Override
		public LuaValue call(LuaValue arg) {
			try {
				if (!arg.istable()) {
					Gdx.app.error(TAG, "register_spell: expected a table, got " + arg.typename());
					return NIL;
				}
				LuaTable tbl = arg.checktable();
				String id = tbl.get("id").checkjstring();
				tbl.get("name").checkjstring();
				// desc/image optional; onUse is a plain table entry, no validation here.
				tbl.get("image").optint(0);
				LuaSpellRegistry.register(id, tbl);
			} catch (Exception e) {
				Gdx.app.error(TAG, "register_spell rejected a malformed definition", e);
			}
			return NIL;
		}
	}

	/**
	 * The {@code register_npc(table)} global handed to Lua (M4b). Mirrors
	 * {@link RegisterSpellFunction}: validates required fields ({@code id/name})
	 * and skips on bad input. {@code sprite} is optional (defaults to
	 * {@code "rat_king"}, resolved by {@link LuaNpc}); the {@code onInteract}
	 * callback field is a plain table entry validated lazily by
	 * {@link LuaItemCallbacks}.
	 */
	private static class RegisterNpcFunction extends OneArgFunction {
		@Override
		public LuaValue call(LuaValue arg) {
			try {
				if (!arg.istable()) {
					Gdx.app.error(TAG, "register_npc: expected a table, got " + arg.typename());
					return NIL;
				}
				LuaTable tbl = arg.checktable();
				String id = tbl.get("id").checkjstring();
				tbl.get("name").checkjstring();
				// sprite optional; onInteract is a plain table entry, no validation here.
				tbl.get("sprite").optjstring("rat_king");
				LuaNpcRegistry.register(id, tbl);
			} catch (Exception e) {
				Gdx.app.error(TAG, "register_npc rejected a malformed definition", e);
			}
			return NIL;
		}
	}

	/**
	 * The {@code register_shop(table)} global handed to Lua (M4c). Mirrors
	 * {@link RegisterNpcFunction}: validates the required top-level fields
	 * ({@code id/name/items}) and skips on bad input. {@code sprite} is optional
	 * (defaults to {@code "shopkeeper"}, resolved by {@link LuaShopNpc}). Per-item
	 * {@code id}/{@code price}/{@code quantity} validation is delegated to
	 * {@link LuaShopNpc#hydrate}, which skips bad entries rather than rejecting the
	 * whole shop — one malformed item does not nuke an otherwise valid shop.
	 */
	private static class RegisterShopFunction extends OneArgFunction {
		@Override
		public LuaValue call(LuaValue arg) {
			try {
				if (!arg.istable()) {
					Gdx.app.error(TAG, "register_shop: expected a table, got " + arg.typename());
					return NIL;
				}
				LuaTable tbl = arg.checktable();
				String id = tbl.get("id").checkjstring();
				tbl.get("name").checkjstring();
				if (!tbl.get("items").istable()) {
					Gdx.app.error(TAG, "register_shop '" + id + "': items must be a table — rejected");
					return NIL;
				}
				// sprite optional; per-item validation happens in LuaShopNpc.hydrate.
				tbl.get("sprite").optjstring("shopkeeper");
				LuaShopRegistry.register(id, tbl);
			} catch (Exception e) {
				Gdx.app.error(TAG, "register_shop rejected a malformed definition", e);
			}
			return NIL;
		}
	}

	/**
	 * The {@code register_mob(table)} global handed to Lua (M3a). Mirrors
	 * {@link RegisterItemFunction}: validates required fields and skips on bad
	 * input. Required: {@code id/name/hp/ht/attack/defense}; {@code ht} defaults
	 * to {@code hp} (matched by {@link LuaMob#hydrate}). Optional: {@code sprite}
	 * (string whitelist key) and the AI-callback fields
	 * ({@code act/attackProc/defenseProc/die}) — plain table entries, validated
	 * lazily by {@link LuaItemCallbacks}.
	 */
	private static class RegisterMobFunction extends OneArgFunction {
		@Override
		public LuaValue call(LuaValue arg) {
			try {
				if (!arg.istable()) {
					Gdx.app.error(TAG, "register_mob: expected a table, got " + arg.typename());
					return NIL;
				}
				LuaTable tbl = arg.checktable();
				String id = tbl.get("id").checkjstring();
				tbl.get("name").checkjstring();
				tbl.get("hp").checkint();
				// ht optional in Lua (LuaMob hydrate falls back to hp), but if
				// present it must be an int — check via optint so a non-int ht is
				// still rejected rather than silently coerced.
				tbl.get("ht").optint(tbl.get("hp").checkint());
				tbl.get("attack").checkint();
				tbl.get("defense").checkint();
				// sprite + act/attackProc/defenseProc/die are optional; no validation here.
				LuaMobRegistry.register(id, tbl);
			} catch (Exception e) {
				Gdx.app.error(TAG, "register_mob rejected a malformed definition", e);
			}
			return NIL;
		}
	}

	/**
	 * The {@code register_ally(table)} global handed to Lua (M3b). Mirrors
	 * {@link RegisterMobFunction}: validates required fields and skips on bad
	 * input. Required: {@code id/name/hp/ht/attack/defense}; {@code ht} defaults
	 * to {@code hp} (matched by {@link LuaAlly#hydrate}). Optional: {@code sprite}
	 * (string whitelist key) and the AI/command-callback fields
	 * ({@code act/attackProc/defenseProc/die/onCommand}) — plain table entries,
	 * validated lazily by {@link LuaItemCallbacks}.
	 */
	private static class RegisterAllyFunction extends OneArgFunction {
		@Override
		public LuaValue call(LuaValue arg) {
			try {
				if (!arg.istable()) {
					Gdx.app.error(TAG, "register_ally: expected a table, got " + arg.typename());
					return NIL;
				}
				LuaTable tbl = arg.checktable();
				String id = tbl.get("id").checkjstring();
				tbl.get("name").checkjstring();
				tbl.get("hp").checkint();
				// ht optional in Lua (LuaAlly hydrate falls back to hp), but if
				// present it must be an int — check via optint so a non-int ht is
				// still rejected rather than silently coerced.
				tbl.get("ht").optint(tbl.get("hp").checkint());
				tbl.get("attack").checkint();
				tbl.get("defense").checkint();
				// sprite + act/attackProc/defenseProc/die/onCommand are optional; no validation here.
				LuaAllyRegistry.register(id, tbl);
			} catch (Exception e) {
				Gdx.app.error(TAG, "register_ally rejected a malformed definition", e);
			}
			return NIL;
		}
	}

	/**
	 * The {@code register_hero(table)} global handed to Lua (M3c). Mirrors
	 * {@link RegisterMobFunction}/{@link RegisterAllyFunction}: validates required
	 * fields and skips on bad input. Delegates field parsing to
	 * {@link LuaHeroClass#hydrate(LuaTable)} (which throws on missing required
	 * fields or an invalid {@code talentSource}); only registers if hydration
	 * succeeds. Required: {@code id/name/talentSource/hp}; optional:
	 * {@code defenseSkill/startingItems/spriteKey}.
	 */
	private static class RegisterHeroFunction extends OneArgFunction {
		@Override
		public LuaValue call(LuaValue arg) {
			try {
				if (!arg.istable()) {
					Gdx.app.error(TAG, "register_hero: expected a table, got " + arg.typename());
					return NIL;
				}
				LuaTable tbl = arg.checktable();
				LuaHeroClass hero = LuaHeroClass.hydrate(tbl);
				LuaHeroRegistry.register(hero, tbl);
			} catch (Exception e) {
				Gdx.app.error(TAG, "register_hero rejected a malformed definition", e);
			}
			return NIL;
		}
	}

	/**
	 * The {@code register_level(table)} global handed to Lua (M4a). Mirrors the other
	 * register_* globals but lighter: a level is a JSON-file-backed {@link DataDrivenLevel},
	 * so the Lua table only carries metadata — required {@code id}/{@code name}, optional
	 * {@code path} (defaults to {@code mods/levels/<id>.json}, consumed by LuaLevelService).
	 * The table is registered as-is so {@link DataDrivenLevel#restoreFromBundle} can
	 * re-hydrate via {@link LuaLevelRegistry} (R3).
	 */
	private static class RegisterLevelFunction extends OneArgFunction {
		@Override
		public LuaValue call(LuaValue arg) {
			try {
				if (!arg.istable()) {
					Gdx.app.error(TAG, "register_level: expected a table, got " + arg.typename());
					return NIL;
				}
				LuaTable tbl = arg.checktable();
				String id = tbl.get("id").checkjstring();
				tbl.get("name").checkjstring();
				// path optional; default resolved at load time.
				tbl.get("path").optjstring("mods/levels/" + id + ".json");
				LuaLevelRegistry.register(id, tbl);
			} catch (Exception e) {
				Gdx.app.error(TAG, "register_level rejected a malformed definition", e);
			}
			return NIL;
		}
	}

	/**
	 * The {@code register_buff(table)} global handed to Lua (M6c). Mirrors
	 * {@link RegisterSpellFunction}: validates required fields ({@code id/name})
	 * and skips on bad input. {@code icon/info/act/attachTo/detach/immunities/
	 * onRestore} are plain table entries validated lazily by {@link LuaBuff}.
	 */
	private static class RegisterBuffFunction extends OneArgFunction {
		@Override
		public LuaValue call(LuaValue arg) {
			try {
				if (!arg.istable()) {
					Gdx.app.error(TAG, "register_buff: expected a table, got " + arg.typename());
					return NIL;
				}
				LuaTable tbl = arg.checktable();
				String id = tbl.get("id").checkjstring();
				tbl.get("name").checkjstring();
				// icon/act/attachTo/detach/immunities/onRestore are optional; no validation here.
				LuaBuffRegistry.register(id, tbl);
			} catch (Exception e) {
				Gdx.app.error(TAG, "register_buff rejected a malformed definition", e);
			}
			return NIL;
		}
	}

	/**
	 * The {@code register_talent_override(table)} global handed to Lua (M7e). Lighter
	 * than the other register_* globals: the table carries an {@code id} (resolved to a
	 * {@link com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent} enum constant
	 * via {@code Talent.valueOf}) plus optional {@code maxPoints}/{@code desc}. A
	 * bad/unknown id logs and skips (never throws). Field-level validation (lower-only
	 * maxPoints, string desc, bad-value skip) is delegated to
	 * {@link LuaTalentOverride#register}; the entry is stored only if at least one
	 * field is valid.
	 */
	private static class RegisterTalentOverrideFunction extends OneArgFunction {
		@Override
		public LuaValue call(LuaValue arg) {
			try {
				if (!arg.istable()) {
					Gdx.app.error(TAG, "register_talent_override: expected a table, got " + arg.typename());
					return NIL;
				}
				LuaTable tbl = arg.checktable();
				String id = tbl.get("id").checkjstring();
				com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent talent =
						com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent.valueOf(id);
				LuaTalentOverride.register(talent, tbl);
			} catch (Exception e) {
				Gdx.app.error(TAG, "register_talent_override rejected a malformed definition", e);
			}
			return NIL;
		}
	}

	/**
	 * The {@code register_talent(table)} global handed to Lua (M8d1, D6(b)).
	 * Activates a pre-declared {@code MOD_}-prefixed {@link Talent} enum slot
	 * (e.g. {@code MOD_EXAMPLE_TALENT}, declared in {@code Talent.java}) and
	 * injects it into a tier via {@link LuaTalentRegistry}. Unlike
	 * {@link RegisterTalentOverrideFunction} (which retunes existing talents),
	 * this is for NEW talents entering a tier list.
	 *
	 * <p>Validation: {@code id} must start with {@code "MOD_"} and resolve to a
	 * declared enum constant (vanilla names are rejected — retuning vanilla is
	 * M7e's job); {@code tier} must be an int in {@code [1,4]}. The tier↔key
	 * dimension is exclusive: tier 1/2 take {@code class} ({@code HeroClass}
	 * name), tier 3 takes {@code subclass} ({@code HeroSubClass} name), tier 4
	 * takes {@code armor_ability} (the {@link com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility}
	 * simple class name — ArmorAbility is an abstract class, not an enum, so
	 * the name is matched at inject time rather than {@code valueOf}-ed here).
	 * {@code name}/{@code title}/{@code desc}/{@code maxPoints} are forwarded to
	 * {@link LuaTalentOverride#register} so the existing
	 * {@code Talent.title()}/{@code desc()}/{@code maxPoints()} fallback picks
	 * them up.
	 *
	 * <p>Bad input logs and skips (never throws), mirroring the other
	 * {@code register_*} globals.
	 */
	private static class RegisterTalentFunction extends OneArgFunction {
		@Override
		public LuaValue call(LuaValue arg) {
			try {
				if (!arg.istable()) {
					Gdx.app.error(TAG, "register_talent: expected a table, got " + arg.typename());
					return NIL;
				}
				LuaTable tbl = arg.checktable();
				String id = tbl.get("id").checkjstring();
				if (!id.startsWith("MOD_")) {
					Gdx.app.error(TAG, "register_talent: id must start with \"MOD_\" (got \""
							+ id + "\"), skipping; to retune a vanilla talent use register_talent_override");
					return NIL;
				}
				com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent talent;
				try {
					talent = com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent.valueOf(id);
				} catch (IllegalArgumentException e) {
					Gdx.app.error(TAG, "register_talent: id '" + id
							+ "' is not a declared Talent enum constant — declare a MOD_ slot in Talent.java first, skipping");
					return NIL;
				}
				LuaValue tierV = tbl.get("tier");
				if (!tierV.isint()) {
					Gdx.app.error(TAG, "register_talent '" + id + "': tier must be an int, skipping (got "
							+ tierV.typename() + ")");
					return NIL;
				}
				int tier = tierV.toint();
				if (tier < 1 || tier > 4) {
					Gdx.app.error(TAG, "register_talent '" + id + "': tier must be 1-4, got "
							+ tier + ", skipping");
					return NIL;
				}
				// M8d3: tier↔key is exclusive — exactly one of class/subclass/armor_ability
				// must be present, matching the tier. Presence is by genuine LuaString
				// (luaj's isstring() is true for numbers, which aren't real key values).
				LuaValue classV = tbl.get("class");
				LuaValue subClassV = tbl.get("subclass");
				LuaValue armorAbilV = tbl.get("armor_ability");
				boolean hasClass = classV instanceof org.luaj.vm2.LuaString;
				boolean hasSubClass = subClassV instanceof org.luaj.vm2.LuaString;
				boolean hasArmorAbil = armorAbilV instanceof org.luaj.vm2.LuaString;
				int keyCount = (hasClass ? 1 : 0) + (hasSubClass ? 1 : 0) + (hasArmorAbil ? 1 : 0);
				com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass cls = null;
				com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass subClass = null;
				String armorAbilityName = null;
				if (tier <= 2) {
					if (keyCount != 1 || !hasClass) {
						Gdx.app.error(TAG, "register_talent '" + id + "': tier " + tier
								+ " requires exactly 'class' (no subclass/armor_ability), skipping");
						return NIL;
					}
					String className = classV.tojstring();
					try {
						cls = com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass.valueOf(className);
					} catch (IllegalArgumentException e) {
						Gdx.app.error(TAG, "register_talent '" + id + "': unknown class '"
								+ className + "', skipping");
						return NIL;
					}
				} else if (tier == 3) {
					if (keyCount != 1 || !hasSubClass) {
						Gdx.app.error(TAG, "register_talent '" + id + "': tier 3 requires exactly 'subclass'"
								+ " (no class/armor_ability), skipping");
						return NIL;
					}
					String subName = subClassV.tojstring();
					try {
						subClass = com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass.valueOf(subName);
					} catch (IllegalArgumentException e) {
						Gdx.app.error(TAG, "register_talent '" + id + "': unknown subclass '"
								+ subName + "', skipping");
						return NIL;
					}
				} else { // tier == 4
					if (keyCount != 1 || !hasArmorAbil) {
						Gdx.app.error(TAG, "register_talent '" + id + "': tier 4 requires exactly 'armor_ability'"
								+ " (no class/subclass), skipping");
						return NIL;
					}
					armorAbilityName = armorAbilV.tojstring();
				}
				LuaValue nameV = tbl.get("name");
				LuaValue titleV = tbl.get("title");
				// MOD_ enum slots have no .title properties key, so a player-facing
				// name is mandatory — without it Talent.title() would render a
				// !!!MOD_*.title!!! placeholder. Accept either `name` or `title`
				// (both flow into LuaTalentOverride as the title override). Require
				// a genuine LuaString: luaj's isstring() is true for numbers, which
				// are not a real title.
				if (!(nameV instanceof org.luaj.vm2.LuaString) && !(titleV instanceof org.luaj.vm2.LuaString)) {
					Gdx.app.error(TAG, "register_talent '" + id
							+ "': missing/non-string 'name' (or 'title') — MOD_ talents have no .title properties key, skipping");
					return NIL;
				}
				// M8d2: on_upgrade callback (optional). Non-function values
				// (including missing) normalize to Java null so the dispatch
				// guard stays a plain == null check (LuaValue.NIL is a singleton
				// object and would otherwise bypass it). Forwarded to the
				// registry, fired by Talent.onTalentUpgraded on upgrade.
				LuaValue onUpgradeRaw = tbl.get("on_upgrade");
				LuaValue onUpgrade = onUpgradeRaw.isfunction() ? onUpgradeRaw : null;
				boolean registered = LuaTalentRegistry.register(talent, tier, cls, subClass, armorAbilityName, onUpgrade);
				if (!registered) return NIL;
				// Forward desc/maxPoints/title(name) to the M7e override path so
				// Talent.maxPoints()/desc()/title() reuse the existing fallback.
				LuaTalentOverride.register(talent, tbl);
			} catch (Exception e) {
				Gdx.app.error(TAG, "register_talent rejected a malformed definition", e);
			}
			return NIL;
		}
	}
}
