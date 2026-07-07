-- M7a port of Remished scripts/buffs/ManaShield.lua
-- Remished: defenceProc blocks one hit (damage->0) then detaches. M7a wires
-- defenseProc via the new LuaBuff hook; the block fires once and the buff
-- detaches itself. Self-detach mid-dispatch is safe because Char iterates a
-- fresh buffs() snapshot.
register_buff{
    id = "mana_shield",
    name = "ManaShield",
    info = "ManaShield (M7a: blocks one hit, damage->0, then detaches)",
    icon = 49,

    attachTo = function(targetId, state)
        return true
    end,

    act = function(selfId, targetId, state)
        return false
    end,

    defenseProc = function(selfId, enemyId, damage)
        -- absorb one hit completely, then consume the shield
        RPD.detachBuff(selfId, "mana_shield")
        return 0
    end,
}
