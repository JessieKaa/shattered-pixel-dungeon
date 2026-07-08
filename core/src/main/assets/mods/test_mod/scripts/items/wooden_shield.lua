-- M11a: Remished WoodenShield(木盾)。走 LuaItem(weapon 占位 wrapper)。
-- 真实 DR/格挡由装备时挂的 LuaBuff `wooden_shield_guard` 提供。
-- 仍降级:无 left_hand 装备槽,走 weapon 槽。
local SHIELD_LEVEL = 1
local GUARD_BUFF = "wooden_shield_guard"

register_item {
    id = "wooden_shield",
    name = "木盾",
    desc = "一面简陋的木盾。装备时提供 1 点额外护甲(无左手槽,占用武器槽)。",
    image = 60,
    tier = 1,
    price = 20,
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
