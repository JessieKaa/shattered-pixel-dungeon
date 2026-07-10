-- M20e remixed_full painter: scorched-floor overlay for BurnedRoom.
-- BurnedRoom.paint already fills its interior with EMPTY, then randomizes a
-- patch subset to EMBERS/TRAP/etc. This overlay runs AFTER delegate paint
-- (LuaPainterAdapter calls delegate first, then the Lua paint callback) and
-- converts any interior cell that is STILL bare EMPTY to EMBERS — thickening
-- the scorch. Non-destructive: the guard checks tileAt==EMPTY, and the Java
-- setTile gate structurally rejects writes to protected terrains
-- (grass/doors/water/traps) and non-whitelisted targets, so residual
-- EMBERS/TRAP/GRASS cells are left untouched and coexist with the new embers.
register_painter {
    id = "BurnedRoom",
    paint = function(level, room)
        for i = 1, #room.cells do
            local cell = room.cells[i]
            if level.tileAt(cell) == RPD.Terrain.EMPTY then
                room.setTile(cell, RPD.Terrain.EMBERS)
            end
        end
    end,
}
