-- M7a port of Remished scripts/buffs/BodyArmor.lua
-- Remished: drBonus + speedMultiplier + timed detach. M7a wires drRoll (+flat
-- DR) and speed (*0.9 encumbered) via the new LuaBuff numerical hooks; the
-- timed detach (act) stays as the live lifecycle.
register_buff{
    id = "body_armor",
    name = "BodyArmor",
    info = "BodyArmor (M7a: +DR and -speed while active; timed detach)",
    icon = 45,

    attachTo = function(targetId, state)
        state.armor = 1
        return true
    end,

    act = function(selfId, targetId, state)
        -- detach after the first act tick (Remished detach-on-act)
        return false
    end,

    drRoll = function(selfId, dr)
        return dr + 3
    end,

    speed = function(selfId, spd)
        return spd * 0.9
    end,
}
