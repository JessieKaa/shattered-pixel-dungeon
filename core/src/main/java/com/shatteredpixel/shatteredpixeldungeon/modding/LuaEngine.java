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
 * enumerates {@code scripts/items/*.lua} and compiles each via the host-side
 * {@code Globals.load} (a Java method, independent of the removed Lua global).
 *
 * <p>Exposes a single {@code register_item(table)} global so Lua scripts can hand
 * item definitions to Java, and runs {@code scripts/init.lua} once on game start
 * for any pure-Lua bootstrap (init.lua must not rely on dofile).
 */
public class LuaEngine implements ResourceFinder {

	private static final String TAG = "LuaEngine";
	static final String INIT_SCRIPT = "scripts/init.lua";
	static final String ITEMS_DIR = "scripts/items";
	static final String MOBS_DIR = "scripts/mobs";
	static final String ALLIES_DIR = "scripts/allies";
	static final String HEROES_DIR = "scripts/heroes";

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
			// M2: inject the narrow RPD.* surface (affectBuff/damageChar/GLog/...).
			// Lua never gets a Char object — only int ids resolved via Actor.findById.
			globals.set("RPD", RpdApi.build());

			InputStream in = findResource(INIT_SCRIPT);
			if (in != null) {
				globals.load(new InputStreamReader(in, "UTF-8"), INIT_SCRIPT).call();
			} else {
				Gdx.app.error(TAG, INIT_SCRIPT + " not found in assets");
			}

			// dofile is stripped from the sandbox (N2), so the host loads each item
			// script itself rather than relying on Lua-side dofile.
			loadItemScripts();

			// M3a: same host-side enumeration for mob scripts under scripts/mobs/.
			loadMobScripts();

			// M3b: same host-side enumeration for ally scripts under scripts/allies/.
			loadAllyScripts();

			// M3c: same host-side enumeration for hero scripts under scripts/heroes/.
			loadHeroScripts();

			initialized = true;
		} catch (Exception e) {
			Gdx.app.error(TAG, "init failed", e);
		}
	}

	/**
	 * Enumerate {@code scripts/items/*.lua} and compile each in the sandbox. Errors
	 * per-file, never fatal.
	 *
	 * <p>dofile is stripped from the sandbox (N2), so the host loads each item script
	 * itself. Enumeration is two-stage: the classpath URL is checked first because
	 * libgdx's headless/LWJGL3 {@code FileHandle.list()} cannot list an
	 * {@code Internal} directory that only exists on the classpath (it returns an
	 * empty array); when the classpath entry is a real filesystem directory (tests
	 * and desktop dev runs) we list it directly. The libgdx fallback covers Android
	 * {@code AssetManager.list} and packaged-jar runs.
	 */
	private void loadItemScripts() {
		loadScriptsFrom(ITEMS_DIR, "Lua items", LuaItemRegistry::size);
	}

	/**
	 * M3a: enumerate {@code scripts/mobs/*.lua} and compile each. Same host-side
	 * enumeration + two-stage listing as items (dofile is stripped from the
	 * sandbox). Lua mobs are not part of the vanilla spawn pool — they only enter
	 * a level via {@code RPD.spawnMob}.
	 */
	private void loadMobScripts() {
		loadScriptsFrom(MOBS_DIR, "Lua mobs", LuaMobRegistry::size);
	}

	/**
	 * M3b: enumerate {@code scripts/allies/*.lua} and compile each. Same host-side
	 * enumeration + two-stage listing as items/mobs (dofile is stripped from the
	 * sandbox). Lua allies are not part of the vanilla spawn pool — they only
	 * enter a level via {@code RPD.spawnAlly}.
	 */
	private void loadAllyScripts() {
		loadScriptsFrom(ALLIES_DIR, "Lua allies", LuaAllyRegistry::size);
	}

	/**
	 * M3c: enumerate {@code scripts/heroes/*.lua} and compile each. Same host-side
	 * enumeration + two-stage listing as items/mobs/allies (dofile is stripped
	 * from the sandbox). Lua heroes are not part of the vanilla
	 * {@code HeroClass.values()} roster — {@code HeroSelectScene} renders them as
	 * extra buttons and {@code Dungeon.init} routes to {@code Hero.initLuaHero}
	 * via {@link LuaHeroService} when one is selected.
	 */
	private void loadHeroScripts() {
		loadScriptsFrom(HEROES_DIR, "Lua heroes", LuaHeroRegistry::size);
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
				tbl.get("tier").checkint();
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
}
