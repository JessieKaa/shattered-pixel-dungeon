-- M8b: unified shield-points pool (ShieldTracker, surfaced via RPD). Declarative
-- shieldAmount seeds the shared pool on attach; defenseProc drains it via
-- RPD.absorbShield; act re-adds points so the shield cycles. Supersedes the M7a
-- "50% block coin-flip" hardcode. (Remished scripts/lib/shields recharge gating
-- still deferred; state.ready still toggles on act to keep per-instance state
-- + restore round-trip exercised.)
register_buff{
    id = "shield_left",
    name = "ShieldLeft",
    info = "ShieldLeft (M8b: ShieldTracker pool; absorbs + recharges on act)",
    icon = 48,
    shieldAmount = 8,
    shieldType = "physical",

    attachTo = function(targetId, state)
        state.ready = false
        return true
    end,

    act = function(selfId, targetId, state)
        state.ready = not state.ready
        RPD.addShield(targetId, 8)
        return 5
    end,

    defenseProc = function(selfId, enemyId, damage)
        return RPD.absorbShield(selfId, damage)
    end,
}
