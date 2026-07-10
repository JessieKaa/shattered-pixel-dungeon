-- M20d remixed_full buff: ManaShield.
-- Remished-style defensive buff: a mana shield that absorbs incoming hits from
-- the shared ShieldTracker pool (surfaced via RPD). declarative shieldAmount
-- seeds the pool on attach; defenseProc drains it via RPD.absorbShield, and the
-- buff self-detaches once the bearer's shield is exhausted. Distinct from
-- test_mod's mana_shield (different pack, larger pool, remixed flavor).
register_buff{
    id = "remixed_full_mana_shield",
    name = "ManaShield",
    info = "ManaShield (M20d: ShieldTracker pool; absorbs hits until depleted)",
    icon = 49,
    shieldAmount = 15,
    shieldType = "mana",

    attachTo = function(targetId, state)
        return true
    end,

    act = function(selfId, targetId, state)
        return false
    end,

    defenseProc = function(selfId, enemyId, damage)
        local left = RPD.absorbShield(selfId, damage)
        if RPD.charShield(selfId) <= 0 then
            RPD.detachBuff(selfId, "remixed_full_mana_shield")
        end
        return left
    end,
}
