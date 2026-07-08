-- M10a shield item: Remished StrongShield(强力盾)。走 LuaItem(weapon 占位 wrapper)。见 wooden_shield.lua 头注。
-- 降级(M10c):drBonus/格挡/left_hand 槽 待 M10c 桥接。
-- 原件: ../remished-dungeon/scripts/items/StrongShield.lua
register_item {
    id = "strong_shield",
    name = "强力盾",
    desc = "一面由坚固金属铸成的大盾。原版可左手装备并格挡伤害(降级:drBonus/格挡/left_hand 槽 待 M10c)。",
    image = 62,
    tier = 3,
    price = 240,
    shieldLevel = 3,

    -- DEGRADED: needs M10c. Inert until wired.
    drBonus = function(heroId)
        return 3
    end,
}
