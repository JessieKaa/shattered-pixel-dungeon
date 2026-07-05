package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ResourceFinder;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Minimal luaj engine for the M0 Lua modding PoC.
 *
 * M0 scope is intentionally tiny: bootstrap a luaj Globals, expose a single
 * {@code register_item(table)} global so Lua scripts can hand item definitions
 * to Java, and run {@code scripts/init.lua} once on game start. No sandbox,
 * no annotation processing — that is M1's job.
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

	private synchronized void initInternal() {
		if (initialized) return;
		try {
			globals = JsePlatform.standardGlobals();
			globals.finder = this;
			globals.set("register_item", new RegisterItemFunction());

			InputStream in = findResource(INIT_SCRIPT);
			if (in != null) {
				globals.load(new InputStreamReader(in, "UTF-8"), INIT_SCRIPT).call();
			} else {
				Gdx.app.error(TAG, INIT_SCRIPT + " not found in assets; no Lua items registered");
			}
			initialized = true;
		} catch (Exception e) {
			Gdx.app.error(TAG, "init failed", e);
		}
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
