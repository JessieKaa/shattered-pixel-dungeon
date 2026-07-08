-- M11a guard buff for royal_shield. Remished DR + encumbrance via M10c LuaBuff bridge.
local SHIELD_LEVEL = 4

register_buff {
    id = "royal_shield_guard",
    name = "皇家盾守护",
    info = "皇家盾提供的额外护甲与明显减速。",

    drBonus = function(selfId)
        return SHIELD_LEVEL
    end,

    speedMultiplier = function(selfId)
        return 0.85
    end,
}
