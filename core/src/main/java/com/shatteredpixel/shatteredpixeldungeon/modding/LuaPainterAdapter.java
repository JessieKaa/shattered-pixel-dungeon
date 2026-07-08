package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.painters.Painter;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.watabou.utils.Point;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * M10b (route A): a {@link Painter} that wraps the upstream {@link RegularPainter}
 * and overlays Lua-defined painting on top. The upstream pipeline (doors,
 * per-room paint, water, grass, traps, decorate) runs unchanged via
 * {@code delegate.paint(...)}; then for each room whose class simple name is
 * registered in {@link LuaPainterRegistry}, the optional Lua {@code paint} /
 * {@code decorate} callbacks fire as a final overlay pass.
 *
 * <h3>Why extend {@link Painter}, not {@code RegularPainter}</h3>
 *
 * <p>{@link Painter} has a single abstract method ({@link Painter#paint}); the
 * adapter only needs to delegate + overlay. Extending {@code RegularPainter}
 * would force stubbing its abstract {@code decorate} (never reached, since we
 * call {@code delegate.paint} rather than {@code super.paint}).
 *
 * <h3>Sandbox + layout safety</h3>
 *
 * <p>{@code Dungeon.level} is still {@code null} during {@code build()}/{@code paint}
 * (assigned only at {@code Dungeon.java:490}, after {@code create()} at :408), so
 * Lua cannot use the {@code Dungeon.level}-bound {@code RPD.*} helpers. Instead the
 * adapter builds a per-call {@code level} table (read-only {@code tileAt}) and a
 * per-call {@code room} table whose {@code setTile} closure captures the real
 * {@link Level} and the room's interior cell set directly.
 *
 * <p>{@code setTile} is the single structural gate that enforces "don't touch
 * door/water/grass/trap": it rejects (a) cells outside the room's interior,
 * (b) cells whose current terrain is in the protected set, and (c) target
 * terrains outside the decorative whitelist. So a misbehaving script cannot
 * punch a door, drain water, pave grass, or drop a TRAP (TRAP requires a
 * {@code Level.traps} entry and must go through {@link LuaLevelService#injectLevelTraps}).
 */
public class LuaPainterAdapter extends Painter {

	private static final String TAG = "LuaPainterAdapter";

	private final Painter delegate;

	public LuaPainterAdapter(Painter delegate) {
		this.delegate = delegate;
	}

	@Override
	public boolean paint(Level level, ArrayList<Room> rooms) {
		boolean ok = delegate.paint(level, rooms);
		if (LuaPainterRegistry.hasAny()) {
			try {
				applyLua(level, rooms);
			} catch (Exception e) {
				// A broken Lua painter must never crash levelgen — delegate paint
				// already produced a valid level; just log and keep it.
				Gdx.app.error(TAG, "applyLua overlay failed, keeping upstream paint", e);
			}
		}
		return ok;
	}

	private static void applyLua(Level level, ArrayList<Room> rooms) {
		if (rooms == null) return;
		LuaTable levelTbl = buildLevelTable(level);
		for (Room room : rooms) {
			String key = room.getClass().getSimpleName();
			LuaTable painter = LuaPainterRegistry.getTable(key);
			if (painter == null) continue;
			LuaTable roomTbl = buildRoomTable(level, room);
			// paint first, then decorate — both optional, fire-and-forget.
			LuaItemCallbacks.callOpt(painter, "paint", levelTbl, roomTbl);
			LuaItemCallbacks.callOpt(painter, "decorate", levelTbl, roomTbl);
		}
	}

	private static LuaTable buildLevelTable(Level level) {
		LuaTable t = new LuaTable();
		t.set("width", LuaValue.valueOf(level.width()));
		t.set("height", LuaValue.valueOf(level.height()));
		t.set("length", LuaValue.valueOf(level.length()));
		t.set("tileAt", new OneArgFunction() {
			@Override
			public LuaValue call(LuaValue arg) {
				int cell = arg.checkint();
				if (cell < 0 || cell >= level.length()) return NIL;
				return LuaValue.valueOf(level.map[cell]);
			}
		});
		return t;
	}

	private static LuaTable buildRoomTable(Level level, Room room) {
		LuaTable t = new LuaTable();
		t.set("left", LuaValue.valueOf(room.left));
		t.set("top", LuaValue.valueOf(room.top));
		t.set("right", LuaValue.valueOf(room.right));
		t.set("bottom", LuaValue.valueOf(room.bottom));
		t.set("width", LuaValue.valueOf(room.width()));
		t.set("height", LuaValue.valueOf(room.height()));
		int cx = (room.left + room.right) / 2;
		int cy = (room.top + room.bottom) / 2;
		t.set("centerCell", LuaValue.valueOf(cx + cy * level.width()));

		// Interior cells only (room.inside excludes the 1-tile perimeter, so
		// doors/walls on the boundary are never offered to Lua). These double as
		// the setTile allow-list.
		Set<Integer> interior = new HashSet<>();
		LuaTable cells = new LuaTable();
		int idx = 1;
		for (Point p : room.getPoints()) {
			if (!room.inside(p)) continue;
			int cell = level.pointToCell(p);
			if (cell < 0 || cell >= level.length()) continue;
			interior.add(cell);
			cells.set(idx++, LuaValue.valueOf(cell));
		}
		t.set("cells", cells);

		t.set("randomCell", new ZeroArgFunction() {
			@Override
			public LuaValue call() {
				if (interior.isEmpty()) return NIL;
				return LuaValue.valueOf(new ArrayList<>(interior)
						.get(com.watabou.utils.Random.Int(interior.size())));
			}
		});

		t.set("setTile", new SetTileClosure(level, interior));
		return t;
	}

	/**
	 * The structural safety gate. Captures the real {@link Level} (not
	 * {@code Dungeon.level}, which is null during build) and the room's interior
	 * cell set. Rejects any write that would touch a protected terrain, leave the
	 * room interior, or set a non-decorative target terrain.
	 */
	private static final class SetTileClosure extends TwoArgFunction {
		private final Level level;
		private final Set<Integer> interior;

		SetTileClosure(Level level, Set<Integer> interior) {
			this.level = level;
			this.interior = interior;
		}

		@Override
		public LuaValue call(LuaValue cellArg, LuaValue terrainArg) {
			int cell = cellArg.checkint();
			int terrain = terrainArg.checkint();
			if (!interior.contains(cell)) {
				Gdx.app.error(TAG, "setTile rejected: cell " + cell + " not in room interior");
				return FALSE;
			}
			if (cell < 0 || cell >= level.length()) return FALSE;
			if (PROTECTED_CURRENT.contains(level.map[cell])) {
				Gdx.app.error(TAG, "setTile rejected: cell " + cell + " terrain "
						+ level.map[cell] + " is protected");
				return FALSE;
			}
			if (!TARGET_WHITELIST.contains(terrain)) {
				Gdx.app.error(TAG, "setTile rejected: target terrain " + terrain + " not whitelisted");
				return FALSE;
			}
			level.map[cell] = terrain;
			level.updateCellFlags(cell);
			return TRUE;
		}
	}

	/** Terrains a Lua painter may never overwrite (door/water/grass/trap/portal/exit family). */
	private static final Set<Integer> PROTECTED_CURRENT = Collections.unmodifiableSet(new HashSet<>(
			Arrays.asList(
					Terrain.DOOR, Terrain.OPEN_DOOR, Terrain.LOCKED_DOOR, Terrain.CRYSTAL_DOOR,
					Terrain.SECRET_DOOR, Terrain.WATER,
					Terrain.TRAP, Terrain.SECRET_TRAP, Terrain.INACTIVE_TRAP,
					Terrain.ENTRANCE, Terrain.ENTRANCE_SP, Terrain.EXIT, Terrain.LOCKED_EXIT,
					Terrain.UNLOCKED_EXIT, Terrain.EMPTY_WELL, Terrain.WELL,
					Terrain.BOOKSHELF, Terrain.BARRICADE,
					Terrain.GRASS, Terrain.HIGH_GRASS, Terrain.FURROWED_GRASS)));

	/** Terrains a Lua painter may set. All PASSABLE / pure decorative — no
	 * connectivity impact. WALL_DECO is excluded: despite the name its flags
	 * equal WALL (SOLID|LOS_BLOCKING), so allowing it would let a painter seal
	 * a room's path. */
	private static final Set<Integer> TARGET_WHITELIST = Collections.unmodifiableSet(new HashSet<>(
			Arrays.asList(
					Terrain.EMPTY, Terrain.EMPTY_DECO, Terrain.EMPTY_SP,
					Terrain.EMBERS)));
}
