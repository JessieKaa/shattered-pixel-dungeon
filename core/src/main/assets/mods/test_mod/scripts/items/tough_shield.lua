-- M10a shield item: Remished ToughShield(坚韧盾)。走 LuaItem(weapon 占位 wrapper)。见 wooden_shield.lua 头注。
-- 降级(M10c):drBonus/格挡/left_hand 槽 待 M10c 桥接。
-- 原件: ../remished-dungeon/scripts/items/ToughShield.lua
register_item {
    id = "tough_shield",
    name = "坚韧盾",
    desc = "一面加固过的盾牌。原版可左手装备并格挡伤害(降级:drBonus/格挡/left_hand 槽 待 M10c)。",
    image = 61,
    tier = 2,
    price = 80,
    shieldLevel = 2,

    -- DEGRADED: needs M10c. Inert until wired.
    drBonus = function(heroId)
        return 2
    end,
}
