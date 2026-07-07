-- M7a port of Remished scripts/buffs/ShieldLeft.lua
-- Remished: defenceProc block chance + recharge timing via scripts/lib/shields.
-- M7a wires the block chance via defenseProc (flat 50% to nullify a hit). The
-- scripts/lib/shields recharge gating is deferred to M7b, so the block is a
-- simple per-hit coin-flip for now; state.ready still toggles on act to keep
-- the per-instance state + restore round-trip exercised.
register_buff{
    id = "shield_left",
    name = "ShieldLeft",
    info = "ShieldLeft (M7a: 50% block chance via defenseProc; recharge lib M7b)",
    icon = 48,

    attachTo = function(targetId, state)
        state.ready = false
        return true
    end,

    act = function(selfId, targetId, state)
        state.ready = not state.ready
        return 5
    end,

    defenseProc = function(selfId, enemyId, damage)
        if math.random() < 0.5 then
            return 0
        end
        return damage
    end,
}
