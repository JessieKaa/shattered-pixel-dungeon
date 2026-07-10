-- M20a: remixed_full charged-dart trap (per-instance data, mirrors test_mod
-- demo_data_trap). Demonstrates M19a persistence: `data` is deep-copied from
-- this spec at create time so each trap instance owns an isolated copy, and
-- LuaTrap.activate passes that instance-owned table as the third onActivate
-- argument. Because Lua tables are reference-typed, mutating `data.charges`
-- here mutates the instance field in place, and LuaDataCodec round-trips it
-- across save/load.
-- Behavior: 3 darts. Each trigger fires one (small damage if a char triggered
-- it), decrements charges, and flips `depleted` once the magazine is empty.
-- Further triggers hit the spent branch (flavor only, no damage).
register_trap {
    id = "remixed_full_charged_dart_trap",
    name = "Charged Dart Trap",
    data = {
        charges = 3,
        depleted = false,
    },
    onActivate = function(cell, charId, data)
        if data and data.charges and data.charges > 0 then
            data.charges = data.charges - 1
            RPD.GLog("a dart snaps toward cell " .. cell
                    .. " (" .. data.charges .. " darts remain)")
            if charId ~= 0 then
                RPD.damageChar(charId, 6)
            end
            if data.charges == 0 then
                data.depleted = true
            end
        else
            RPD.GLog("the dart trap clicks hollowly — its magazine is spent")
        end
    end,
}
