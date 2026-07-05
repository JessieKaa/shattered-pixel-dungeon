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

			InputStream in = findResource(INIT_SCRIPT);
			if (in != null) {
				globals.load(new InputStreamReader(in, "UTF-8"), INIT_SCRIPT).call();
			} else {
				Gdx.app.error(TAG, INIT_SCRIPT + " not found in assets");
			}

			// dofile is stripped from the sandbox (N2), so the host loads each item
			// script itself rather than relying on Lua-side dofile.
			loadItemScripts();

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
		String[] names = listItemScriptNames();
		if (names.length == 0) {
			Gdx.app.error(TAG, ITEMS_DIR + " contains no .lua files; no Lua items registered");
			return;
		}
		java.util.Arrays.sort(names);
		for (String n : names) {
			String path = ITEMS_DIR + "/" + n;
			try (InputStream in = findResource(path)) {
				if (in == null) {
					Gdx.app.error(TAG, "Item script " + path + " could not be opened");
					continue;
				}
				globals.load(new InputStreamReader(in, "UTF-8"), path).call();
			} catch (Exception e) {
				Gdx.app.error(TAG, "Failed to load " + path, e);
			}
		}
		if (LuaItemRegistry.size() == 0) {
			Gdx.app.error(TAG, "No Lua items registered after scanning " + ITEMS_DIR);
		}
	}

	/**
	 * Lists {@code *.lua} filenames under {@link #ITEMS_DIR}.
	 * Stage 1: classpath-as-filesystem (works in tests + unpacked desktop runs).
	 * Stage 2: libgdx {@code FileHandle.list()} (Android AssetManager, packaged jars).
	 */
	private String[] listItemScriptNames() {
		try {
			java.net.URL dirUrl = LuaEngine.class.getClassLoader().getResource(ITEMS_DIR);
			if (dirUrl != null && "file".equals(dirUrl.getProtocol())) {
				java.io.File dir = new java.io.File(dirUrl.toURI());
				java.io.File[] files = dir.listFiles();
				if (files != null) {
					java.util.List<String> out = new java.util.ArrayList<>();
					for (java.io.File f : files) {
						if (f.isFile() && f.getName().endsWith(".lua")) out.add(f.getName());
					}
					if (!out.isEmpty()) return out.toArray(new String[0]);
				}
			}
		} catch (Exception e) {
			Gdx.app.error(TAG, "Classpath-FS enumeration of " + ITEMS_DIR + " failed, falling back", e);
		}
		// Fallback: libgdx FileHandle.list() (Android/packaged).
		try {
			FileHandle dir = Gdx.files.internal(ITEMS_DIR);
			if (dir != null && dir.exists()) {
				FileHandle[] kids = dir.list();
				java.util.List<String> out = new java.util.ArrayList<>();
				for (FileHandle k : kids) {
					if (k.name().endsWith(".lua")) out.add(k.name());
				}
				return out.toArray(new String[0]);
			}
		} catch (Exception e) {
			Gdx.app.error(TAG, "Gdx fallback list of " + ITEMS_DIR + " failed", e);
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
				tbl.get("image").checkint();
				LuaItemRegistry.register(id, tbl);
			} catch (Exception e) {
				Gdx.app.error(TAG, "register_item rejected a malformed definition", e);
			}
			return NIL;
		}
	}
}
