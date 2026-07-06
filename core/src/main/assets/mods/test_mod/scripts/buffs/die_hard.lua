-- M6c port of Remished scripts/buffs/DieHard.lua
-- Remished: damage() random-detach + regenerationBonus + spend(20). M6c has no
-- per-damage hook on a generic buff, so the random-detach-on-hit is degraded;
-- the spend(20) recharge + regeneration-bonus-intent is preserved via the act
-- tick (spend 20 then keep the buff alive).
register_buff{
    id = "die_hard",
    name = "DieHard",
    info = "DieHard (M6c: recharge every 20 ticks; on-hit detach not bridged)",
    icon = 44,
    degraded = true,
    degradation = "Remished damage() callback (random detach on hit) needs a Char damage hook not exposed in M6c; regenerationBonus likewise. Recharge timing preserved.",

    act = function(selfId, targetId, state)
        return 20
    end,
}
