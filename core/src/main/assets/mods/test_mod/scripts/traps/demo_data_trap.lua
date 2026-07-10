-- M19a PoC: a minimal Lua trap carrying per-instance `data`.
-- `data` is deep-copied from this spec at create time, persisted with the trap
-- instance across save/load, and passed as the third onActivate argument.
-- Safe subset only (string/number/boolean/table) — see LuaDataCodec.
register_trap {
    id = "demo_data_trap",
    name = "Data Trap",
    data = {
        message = "a data-driven trap snaps",
        damage = 5,
        loud = true,
    },
    onActivate = function(cell, charId, data)
        -- Legacy 2-arg traps ignore the extra `data` arg; here we use it.
        local msg = (data and data.message) or "(no data)"
        local dmg = (data and data.damage) or 0
        RPD.GLog(msg .. " @ cell " .. cell .. " (dmg=" .. dmg .. ")")
    end,
}
