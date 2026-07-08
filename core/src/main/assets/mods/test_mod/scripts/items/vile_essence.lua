-- M11b material item: Remished VileEssence(恶毒精华,炼金材料)。
-- 最小可用 USE: 恢复少量法力并消耗。发光(glowing)效果留 follow-up。
-- 原件: ../remished-dungeon/scripts/items/VileEssence.lua
register_item {
    id = "vile_essence",
    type = "material",
    name = "恶毒精华",
    desc = "从疫病采集系统获得的恶毒精华。使用它可以恢复少量法力(发光效果留后续实现)。",
    image = 50,
    price = 10,
    stackable = true,
    defaultAction = "USE",
    onUse = function(heroId, itemId)
        RPD.restoreMana(heroId, 5)
    end,
}
