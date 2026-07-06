-- M6c port of Remished scripts/buffs/ShieldLeft.lua
-- Remished: defenceProc block chance + recharge timing via scripts/lib/shields.
-- M6c has no defenceProc hook and no shields lib; degraded to a recharging
-- metadata buff (state.ready toggles each act, exercising per-instance state).
register_buff{
    id = "shield_left",
    name = "ShieldLeft",
    info = "ShieldLeft (M6c degraded: block chance not bridged; recharge timing preserved)",
    icon = 48,
    degraded = true,
    degradation = "Remished defenceProc block + scripts/lib/shields not exposed in M6c; state.ready toggles on act to exercise per-instance state + restore.",

    attachTo = function(targetId, state)
        state.ready = false
        return true
    end,

    act = function(selfId, targetId, state)
        state.ready = not state.ready
        return 5
    end,
}
