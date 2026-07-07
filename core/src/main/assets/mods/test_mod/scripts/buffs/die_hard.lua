-- M7a port of Remished scripts/buffs/DieHard.lua
-- Remished: damage() random-detach + regenerationBonus + spend(20). M7a wires
-- the on-hit random-detach via defenseProc (50% chance to detach when hit),
-- preserving the "die hard then finally give out" flavour; the spend(20) act
-- recharge stays. regenerationBonus (HP/tick regen) needs a hook not in this
-- feature set and stays deferred to M7b.
register_buff{
    id = "die_hard",
    name = "DieHard",
    info = "DieHard (M7a: 50% on-hit detach via defenseProc; regenBonus M7b)",
    icon = 44,

    act = function(selfId, targetId, state)
        return 20
    end,

    defenseProc = function(selfId, enemyId, damage)
        if math.random() < 0.5 then
            RPD.detachBuff(selfId, "die_hard")
        end
        return damage
    end,
}
