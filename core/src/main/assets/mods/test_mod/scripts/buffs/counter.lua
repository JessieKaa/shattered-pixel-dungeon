-- M7b port of Remished scripts/buffs/Counter.lua
-- Remished: act ticks damage + level decrement + detach; charAct increments a
-- per-instance counter and shows a status. M7b bridges both: act keeps the
-- damage/level/detach lifecycle, charAct (fired by Actor.process every Char
-- turn) increments state.counter — the per-Char-turn active behaviour that
-- M6c could not express and left degraded.
register_buff{
    id = "counter",
    name = "Counter",
    info = "Counter (M7b: act damage/lifecycle + charAct per-turn counter)",
    icon = 46,

    attachTo = function(targetId, state)
        state.counter = 0
        return true
    end,

    act = function(selfId, targetId, state)
        RPD.damageChar(targetId, 1)
        local lvl = RPD.buffLevel(targetId, "counter") or 1
        lvl = lvl - 1
        if lvl <= 0 then
            RPD.detachBuff(targetId, "counter")
            return true
        end
        RPD.setBuffLevel(targetId, "counter", lvl)
        return 1
    end,

    charAct = function(selfId, targetId, state)
        state.counter = (state.counter or 0) + 1
    end,
}
