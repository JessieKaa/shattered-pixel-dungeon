-- M20e remixed_full painter: scattered-debris overlay for LibraryRoom.
-- LibraryRoom.paint fills its interior with EMPTY_SP (special empty, valid
-- spawn) plus a BOOKSHELF row, and drops scrolls onto EMPTY_SP cells. This
-- overlay runs AFTER delegate paint (LuaPainterAdapter fires the Lua decorate
-- callback after the upstream room paint + delegate), scattering up to 2
-- EMPTY_DECO cells across the interior for visual clutter.
--
-- Guard accepts BOTH EMPTY_SP and EMPTY: LibraryRoom's real floor is EMPTY_SP
-- (LibraryRoom.java), so guarding only EMPTY would never match (dead code).
-- Accepting EMPTY too makes the overlay robust if the floor is ever a plain
-- EMPTY variant. Non-destructive: the Java setTile gate whitelists EMPTY_DECO
-- as a target and rejects writes to protected terrains (doors/grass/water/
-- traps), and BOOKSHELF cells fail the guard so shelves are never overwritten.
register_painter {
    id = "LibraryRoom",
    decorate = function(level, room)
        local placed = 0
        for i = 1, #room.cells do
            if placed >= 2 then break end
            local cell = room.cells[i]
            local cur = level.tileAt(cell)
            if cur == RPD.Terrain.EMPTY_SP or cur == RPD.Terrain.EMPTY then
                room.setTile(cell, RPD.Terrain.EMPTY_DECO)
                placed = placed + 1
            end
        end
    end,
}
