-- M10a shield item: Remished RoyalShield(皇家盾)。走 LuaItem(weapon 占位 wrapper)。见 wooden_shield.lua 头注。
-- 降级(M10c):drBonus/格挡/left_hand 槽 待 M10c 桥接。
-- 原件: ../remished-dungeon/scripts/items/RoyalShield.lua
register_item {
    id = "royal_shield",
    name = "皇家盾",
    desc = "饰有皇家纹章的精钢大盾。原版可左手装备并格挡伤害(降级:drBonus/格挡/left_hand 槽 待 M10c)。",
    image = 63,
    tier = 4,
    price = 640,
    shieldLevel = 4,

    -- DEGRADED: needs M10c. Inert until wired.
    drBonus = function(heroId)
        return 4
    end,
}
