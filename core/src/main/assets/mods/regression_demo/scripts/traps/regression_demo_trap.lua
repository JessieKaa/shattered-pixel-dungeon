-- M10b trap representative: register_trap + onActivate(cell, charId). On trigger,
-- Trap.trigger() -> LuaTrap.activate() dispatches onActivate. Lua only receives int
-- ids (M1 sandbox boundary).
register_trap {
    id = "regression_demo_trap",
    name = "RegTrap",
    onActivate = function(cell, charId)
        RPD.GLog("[regression_demo] trap snaps at cell " .. cell)
    end,
}
