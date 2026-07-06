-- M6c port of Remished scripts/buffs/ChaosShieldLeft.lua
-- Remished: defenceProc with random chaos effects (heal/damage/clone/buff/curse/
-- sheep). M6c has no defenceProc hook on a generic Lua buff and no shield
-- library, so this is degraded to a timed, recharging metadata buff.
register_buff{
    id = "chaos_shield_left",
    name = "ChaosShieldLeft",
    info = "ChaosShieldLeft (M6c degraded: defenceProc chaos effects not bridged)",
    icon = 47,
    degraded = true,
    degradation = "defenceProc block + random chaos effects need a Char defence hook + scripts/lib/shields, neither exposed in M6c. Recharge timing is preserved.",

    attachTo = function(targetId, state)
        state.ready = false
        return true
    end,

    act = function(selfId, targetId, state)
        state.ready = true
        -- recharge every 5 ticks; behave like a slow-cycling shield placeholder
        return 5
    end,
}
