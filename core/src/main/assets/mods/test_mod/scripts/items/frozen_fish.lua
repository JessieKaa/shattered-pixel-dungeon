-- M11b material item: Remished FrozenFish。可堆叠冻鱼。
-- 最小可用 eat: 较低饱食 + Chill; burn 环境转换接 Java LuaMaterial transform。
-- 原件: ../remished-dungeon/scripts/items/FrozenFish.lua
register_item {
    id = "frozen_fish",
    type = "material",
    name = "冻鱼",
    desc = "一条冻得硬邦邦的鱼。烤一烤可以变成煎鱼。",
    image = 14,
    price = 15,
    stackable = true,
    defaultAction = "EAT",
    energy = 120,
    burnTransform = "fried_fish",
    onEat = function(heroId, itemId)
        RPD.affectBuff(heroId, "Chill", 10)
    end,
}
