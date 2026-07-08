-- M10a shield item: Remished ChaosShield(混沌盾)。走 LuaItem(weapon 占位 wrapper)。见 wooden_shield.lua 头注。
-- 降级(M10c):drBonus/格挡/left_hand 槽 + ownerTakesDamage/ownerDoesDamage 充能升级/降级
--   (charges = 5*level^1.5,满则 upgrade、空则 degrade)+ 动态 image(level/3) 待 M10c 桥接。
-- 原件: ../remished-dungeon/scripts/items/ChaosShield.lua
register_item {
    id = "chaos_shield",
    name = "混沌盾",
    desc = "一面随战况自我进化的混沌之盾:命中蓄能可升级、格挡耗能会降级。原版还带混沌格挡特效(降级:drBonus/充能升降级/动态 image 待 M10c)。",
    image = 64,
    tier = 3,
    price = 60,
    shieldLevel = 3,

    -- DEGRADED: needs M10c. Inert until wired.
    drBonus = function(heroId)
        return 3
    end,
    -- DEGRADED: needs M10c + per-instance item state. chargeForLevel = 5 * level^1.5.
    ownerDoesDamage = function(heroId, damage)
    end,
    ownerTakesDamage = function(heroId, damage)
    end,
}
