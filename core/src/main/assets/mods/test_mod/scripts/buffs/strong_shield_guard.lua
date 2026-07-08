-- M11a guard buff for strong_shield. Remished DR + encumbrance via M10c LuaBuff bridge.
local SHIELD_LEVEL = 3

register_buff {
    id = "strong_shield_guard",
    name = "强力盾守护",
    info = "强力盾提供的额外护甲与减速。",

    drBonus = function(selfId)
        return SHIELD_LEVEL
    end,

    speedMultiplier = function(selfId)
        return 0.90
    end,
}
