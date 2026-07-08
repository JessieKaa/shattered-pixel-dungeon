-- M10a shield item: Remished WoodenShield(木盾)。走 LuaItem(weapon 占位 wrapper —— 无 LuaArmor/left_hand 槽)。
-- supervisor 指示:基础属性先写(tier=level/name/desc/image/price),drBonus 回调标注降级。
-- 降级(M10c):drBonus 格挡、blockChance/blockDamage/recharge、left_hand 槽、shields.makeShield 桥接 ——
--   drBonus 函数字段当前不被 Java 调用(惰性无害),M10c armor 桥接落地后直接读取。
-- 原件: ../remished-dungeon/scripts/items/WoodenShield.lua
register_item {
    id = "wooden_shield",
    name = "木盾",
    desc = "一面简陋的木盾。原版可左手装备并格挡伤害(降级:drBonus/格挡/left_hand 槽 待 M10c)。",
    image = 60,
    tier = 1,
    price = 20,
    shieldLevel = 1,

    -- DEGRADED: needs M10c (LuaArmor/left_hand + drBonus dispatch). Inert until wired.
    drBonus = function(heroId)
        return 1
    end,
}
