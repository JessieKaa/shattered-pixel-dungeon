-- M11b material item: Remished RottenFish。可堆叠腐鱼。
-- 最小可用 eat: 极微饱食 + Poison。
-- 原件: ../remished-dungeon/scripts/items/RottenFish.lua
register_item {
    id = "rotten_fish",
    type = "material",
    name = "腐烂的鱼",
    desc = "一条腐烂发臭的鱼,吃了会中毒。",
    image = 15,
    price = 0,
    stackable = true,
    defaultAction = "EAT",
    energy = 50,
    onEat = function(heroId, itemId)
        RPD.affectBuff(heroId, "Poison", 8)
    end,
}
