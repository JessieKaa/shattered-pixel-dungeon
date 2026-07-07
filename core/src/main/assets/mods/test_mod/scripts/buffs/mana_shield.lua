-- M8b: unified shield-points pool (ShieldTracker, surfaced via RPD). The buff
-- declares a declarative shieldAmount; Java seeds the shared pool on attach,
-- and this defenseProc drains it via RPD.absorbShield, self-detaching once the
-- bearer's shield is exhausted. Supersedes the M7a "block one hit, damage->0,
-- then detach" hardcode — the M8b contract is points-based.
register_buff{
    id = "mana_shield",
    name = "ManaShield",
    info = "ManaShield (M8b: ShieldTracker pool; absorbs hits until depleted)",
    icon = 49,
    shieldAmount = 10,
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
            RPD.detachBuff(selfId, "mana_shield")
        end
        return left
    end,
}
