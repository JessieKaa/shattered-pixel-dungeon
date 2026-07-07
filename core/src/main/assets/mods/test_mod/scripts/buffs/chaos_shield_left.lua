-- M7a port of Remished scripts/buffs/ChaosShieldLeft.lua
-- Remished: defenceProc with random chaos effects (heal/damage/clone/buff/curse/
-- sheep). M7a wires the defenceProc hook with a plain block chance (30%) so the
-- shield does something gameplay-visible on hit. The full chaos random-effect
-- table needs scripts/lib/shields + a broader effect API and is deferred to M7b.
register_buff{
    id = "chaos_shield_left",
    name = "ChaosShieldLeft",
    info = "ChaosShieldLeft (M7a: 30% block via defenseProc; full chaos table M7b)",
    icon = 47,

    attachTo = function(targetId, state)
        state.ready = false
        return true
    end,

    act = function(selfId, targetId, state)
        state.ready = true
        -- recharge every 5 ticks; behave like a slow-cycling shield placeholder
        return 5
    end,

    defenseProc = function(selfId, enemyId, damage)
        if math.random() < 0.3 then
            return 0
        end
        return damage
    end,
}
