-- M11b material item: Remished RawFish(RawFish)。可堆叠食物。
-- 最小可用 eat: 少量饱食 + Poison; burn/freeze 环境转换接 Java LuaMaterial transform。
-- 原件: ../remished-dungeon/scripts/items/RawFish.lua
register_item {
    id = "raw_fish",
    type = "material",
    name = "生鱼",
    desc = "一条生鱼。直接吃会中毒;被烧会煎熟,被冻会冻硬,被毒会腐烂。",
    image = 12,
    price = 7,
    stackable = true,
    defaultAction = "EAT",
    energy = 150,
    burnTransform = "fried_fish",
    freezeTransform = "frozen_fish",
    poisonTransform = "rotten_fish",
    onEat = function(heroId, itemId)
        RPD.affectBuff(heroId, "Poison", 5)
    end,
    onThrow = function(cell, itemId)
        RPD.GLog("生鱼滑落到地上...")
    end,
}
