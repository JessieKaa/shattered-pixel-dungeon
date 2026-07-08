-- M10a material item: Remished RottenFish。可堆叠腐鱼。
-- 降级(M10c/LuaMaterial 无 eat 派发):eat(Poison + Hunger/4)未接。
-- 原件: ../remished-dungeon/scripts/items/RottenFish.lua
register_item {
    id = "rotten_fish",
    type = "material",
    name = "腐烂的鱼",
    desc = "一条腐烂发臭的鱼,吃了会中毒(降级:eat 未接,仅可堆叠)。",
    image = 15,
    price = 0,
    stackable = true,
}
