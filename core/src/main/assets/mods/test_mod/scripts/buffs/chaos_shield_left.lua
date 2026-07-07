-- M8b: unified shield-points pool, same shape as shield_left. The Remished
-- chaos random-effect table (heal/damage/clone/buff/curse/sheep) still needs
-- scripts/lib/shields + a broader effect API and remains deferred; for now the
-- shield absorbs via the shared ShieldTracker pool like shield_left and
-- recharges on act. Supersedes the M7a "30% block coin-flip" hardcode.
register_buff{
    id = "chaos_shield_left",
    name = "ChaosShieldLeft",
    info = "ChaosShieldLeft (M8b: ShieldTracker pool; chaos table still deferred)",
    icon = 47,
    shieldAmount = 6,
    shieldType = "chaos",

    attachTo = function(targetId, state)
        state.ready = false
        return true
    end,

    act = function(selfId, targetId, state)
        state.ready = true
        RPD.addShield(targetId, 6)
        return 5
    end,

    defenseProc = function(selfId, enemyId, damage)
        return RPD.absorbShield(selfId, damage)
    end,
}
