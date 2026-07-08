-- M10b PoC: a minimal Lua painter overlay.
-- Registers for ShopRoom. LuaPainterAdapter calls the upstream ShopPainter
-- FIRST (delegate.paint runs the full pipeline: doors, room paint, water,
-- grass, traps, decorate), THEN fires this overlay. This decorate only flips
-- one bare-floor interior cell to EMPTY_DECO — non-destructive: it checks
-- tileAt==EMPTY first, and the Java setTile gate structurally rejects any
-- write to door/water/grass/trap/pedestal-family cells anyway.
--
-- Lua never receives a Java Level/Room object — only the per-call level/room
-- tables built by LuaPainterAdapter (M1 sandbox boundary, same as LuaMob/Npc).
register_painter {
    id = "ShopRoom",
    decorate = function(level, room)
        local cell = room.cells[1]
        if cell and level.tileAt(cell) == RPD.Terrain.EMPTY then
            room.setTile(cell, RPD.Terrain.EMPTY_DECO)
        end
    end,
}
