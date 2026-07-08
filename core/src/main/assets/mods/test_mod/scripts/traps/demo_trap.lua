-- M10b PoC: a minimal Lua-defined trap.
-- register_trap stores the table; LuaLevelService.injectLevelTraps places
-- LuaTrap instances at EMPTY cells during RegularLevel.createMobs (after the
-- upstream paint pipeline). On trigger, Trap.trigger() -> LuaTrap.activate()
-- dispatches onActivate(cell, charId). Lua only receives int ids (M1 sandbox).
-- color/shape default to GREY/DOTS in LuaTrap.hydrate.
register_trap {
    id = "demo_trap",
    name = "Lua Trap",
    onActivate = function(cell, charId)
        RPD.GLog("a lua trap snaps at cell " .. cell)
    end,
}
