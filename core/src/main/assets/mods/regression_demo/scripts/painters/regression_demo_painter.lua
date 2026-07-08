-- M10b painter representative: register_painter overlay for ShopRoom. The Java
-- adapter runs the upstream ShopPainter FIRST, then fires this decorate, which
-- flips one bare interior EMPTY cell to EMPTY_DECO (non-destructive — the setTile
-- gate rejects door/water/grass/trap/pedestal cells anyway). Lua receives only the
-- per-call level/room tables built by LuaPainterAdapter (M1 sandbox).
register_painter {
    id = "ShopRoom",
    decorate = function(level, room)
        local cell = room.cells[1]
        if cell and level.tileAt(cell) == RPD.Terrain.EMPTY then
            room.setTile(cell, RPD.Terrain.EMPTY_DECO)
        end
    end,
}
