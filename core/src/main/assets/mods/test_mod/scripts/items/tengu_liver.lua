-- M11b material item: Remished TenguLiver(tengu 肝,mastery 食物降级)。
-- 最小可用 eat: 高饱食 + 小幅治疗。选子职业窗口留 follow-up。
-- 原件: ../remished-dungeon/scripts/items/TenguLiver.lua
register_item {
    id = "tengu_liver",
    type = "material",
    name = "tengu 之肝",
    desc = "传说中的 mastery 食物。吃下它可以恢复一些体力(选职窗口留后续实现)。",
    image = 51,
    price = 0,
    stackable = false,
    defaultAction = "EAT",
    energy = 400,
    onEat = function(heroId, itemId)
        RPD.healChar(heroId, 10)
    end,
}
