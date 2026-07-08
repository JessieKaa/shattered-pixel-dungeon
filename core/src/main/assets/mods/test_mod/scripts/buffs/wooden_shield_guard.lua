-- M11a guard buff for wooden_shield. Remished DR placeholder via M10c LuaBuff bridge.
-- No left-hand slot: attached permanently by the item's onEquip/onDeactivate.
local SHIELD_LEVEL = 1

register_buff {
    id = "wooden_shield_guard",
    name = "木盾守护",
    info = "木盾提供的额外护甲。",

    drBonus = function(selfId)
        return SHIELD_LEVEL
    end,
}
