-- M10c port of Remished scripts/buffs/DieHard.lua
-- Remished: damage() random-detach + regenerationBonus + spend(20). M10c wires
-- the canonical callbacks: damage (return-consuming, fires in Char.damage
-- pre-shield) random-detaches the bearer on a hit; regenerationBonus feeds
-- Regeneration.act (healRate=1.2^sum). The spend(20) act recharge stays.
register_buff{
    id = "die_hard",
    name = "DieHard",
    info = "DieHard (M10c: on-hit random detach via damage; regenerationBonus)",
    icon = 44,

    act = function(selfId, targetId, state)
        return 20
    end,

    -- Remished: chance to finally give out on each hit taken. Returns dmg
    -- unchanged (does not modify damage), detaches as a side effect.
    damage = function(selfId, srcId, dmg)
        if math.random() < 0.5 then
            RPD.detachBuff(selfId, "die_hard")
        end
        return dmg
    end,

    regenerationBonus = function(selfId)
        return RPD.buffLevel(selfId, "die_hard") or 1
    end,
}
