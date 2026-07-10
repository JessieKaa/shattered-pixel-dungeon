-- M20a: remixed_full alarm trap (pure-trigger style, mirrors test_mod demo_trap).
-- On activate: GLog a flavor line, then sting the triggerer for a few HP.
-- `charId` is always a number (0 when no char is on the cell, e.g. searched
-- remotely); RPD.damageChar(charId, amt) routes through Char.damage so shields/
-- death/immunity still apply. The `charId ~= 0` guard documents intent and
-- skips a no-op call when the cell is empty.
register_trap {
    id = "remixed_full_alarm_trap",
    name = "Alarm Trap",
    onActivate = function(cell, charId)
        RPD.GLog("an alarm trap shrieks at cell " .. cell)
        if charId ~= 0 then
            RPD.damageChar(charId, 4)
        end
    end,
}
