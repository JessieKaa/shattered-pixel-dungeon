-- M10c port of Remished scripts/buffs/BodyArmor.lua
-- Remished: drBonus + speedMultiplier + timed detach. M10c wires the canonical
-- callbacks: drBonus bridges additively into drRoll, speedMultiplier bridges
-- multiplicatively into speed (0.9 = encumbered). The timed detach (act) stays.
register_buff{
    id = "body_armor",
    name = "BodyArmor",
    info = "BodyArmor (M10c: drBonus + speedMultiplier; timed detach)",
    icon = 45,

    attachTo = function(targetId, state)
        state.armor = 1
        return true
    end,

    act = function(selfId, targetId, state)
        -- detach after the first act tick (Remished detach-on-act)
        return false
    end,

    drBonus = function(selfId)
        return 3
    end,

    speedMultiplier = function(selfId)
        return 0.9
    end,
}
