-- M11a: Remished ToughShield(坚韧盾)。走 LuaItem(weapon 占位 wrapper)。
local SHIELD_LEVEL = 2
local GUARD_BUFF = "tough_shield_guard"

register_item {
    id = "tough_shield",
    name = "坚韧盾",
    desc = "一面加固过的盾牌。装备时提供 2 点额外护甲,但轻微减速(无左手槽,占用武器槽)。",
    image = 61,
    tier = 2,
    price = 80,
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
