-- M11b material item: Remished FriedFish。可堆叠熟食。
-- 最小可用 eat: 较高饱食; poison 环境转换字段保留(Heap 无现成 poison hook,降级)。
-- 原件: ../remished-dungeon/scripts/items/FriedFish.lua
register_item {
    id = "fried_fish",
    type = "material",
    name = "煎鱼",
    desc = "一条煎熟的鱼,香气四溢。放置久了会腐烂。",
    image = 13,
    price = 30,
    stackable = true,
    defaultAction = "EAT",
    energy = 350,
    poisonTransform = "rotten_fish",
    onEat = function(heroId, itemId)
        -- 煎鱼只由 Java energy 处理饱食,这里只做非饥饿效果(目前无额外 debuff)
    end,
}
