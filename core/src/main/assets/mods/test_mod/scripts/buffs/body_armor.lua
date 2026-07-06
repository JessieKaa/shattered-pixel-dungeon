-- M6c port of Remished scripts/buffs/BodyArmor.lua
-- Remished: drBonus + speedMultiplier + timed detach. M6c has no per-Char
-- drBonus/speedMultiplier hook bridged to Lua buffs, so DR/speed effects are
-- degraded; the timed detach (act) is preserved as the live behaviour.
register_buff{
    id = "body_armor",
    name = "BodyArmor",
    info = "BodyArmor (M6c: timed armor boost; DR/speed calc not bridged)",
    icon = 45,
    degraded = true,
    degradation = "drBonus/speedMultiplier need Char-side hooks not exposed in M6c; only the timed duration + detach lifecycle is active.",

    attachTo = function(targetId, state)
        state.armor = 1
        return true
    end,

    act = function(selfId, targetId, state)
        -- detach after the first act tick (Remished detach-on-act)
        return false
    end,
}
