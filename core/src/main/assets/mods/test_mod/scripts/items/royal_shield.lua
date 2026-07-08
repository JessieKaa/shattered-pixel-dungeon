-- M11a: Remished RoyalShield(皇家盾)。走 LuaItem(weapon 占位 wrapper)。
local SHIELD_LEVEL = 4
local GUARD_BUFF = "royal_shield_guard"

register_item {
    id = "royal_shield",
    name = "皇家盾",
    desc = "饰有皇家纹章的精钢大盾。装备时提供 4 点额外护甲,但明显减速(无左手槽,占用武器槽)。",
    image = 63,
    tier = 4,
    price = 640,
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
