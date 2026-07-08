-- M11a: Remished StrongShield(强力盾)。走 LuaItem(weapon 占位 wrapper)。
local SHIELD_LEVEL = 3
local GUARD_BUFF = "strong_shield_guard"

register_item {
    id = "strong_shield",
    name = "强力盾",
    desc = "一面由坚固金属铸成的大盾。装备时提供 3 点额外护甲,但会减速(无左手槽,占用武器槽)。",
    image = 62,
    tier = 3,
    price = 240,
    shieldLevel = SHIELD_LEVEL,

    onEquip = function(heroId)
        if RPD and RPD.permanentBuff then
            RPD.permanentBuff(heroId, GUARD_BUFF, SHIELD_LEVEL)
        end
    end,

    onDeactivate = function(heroId)
        if RPD and RPD.removeBuff then
            RPD.removeBuff(heroId, GUARD_BUFF)
        end
    end,
}
