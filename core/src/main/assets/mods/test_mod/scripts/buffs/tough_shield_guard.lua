-- M11a guard buff for tough_shield. Remished DR + light encumbrance via M10c LuaBuff bridge.
local SHIELD_LEVEL = 2

register_buff {
    id = "tough_shield_guard",
    name = "坚韧盾守护",
    info = "坚韧盾提供的额外护甲与轻微减速。",

    drBonus = function(selfId)
        return SHIELD_LEVEL
    end,

    speedMultiplier = function(selfId)
        return 0.95
    end,
}
