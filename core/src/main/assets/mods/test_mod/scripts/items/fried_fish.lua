-- M10a material item: Remished FriedFish。可堆叠熟食,按价定价。
-- 降级(M10c/LuaMaterial 无 eat 派发):eat(Hunger STARVING)、poison→RottenFish 转换未接。
-- 原件: ../remished-dungeon/scripts/items/FriedFish.lua
register_item {
    id = "fried_fish",
    type = "material",
    name = "煎鱼",
    desc = "一条煎熟的鱼,香气四溢(降级:eat 未接,仅可堆叠/出售)。",
    image = 13,
    price = 30,
    stackable = true,
}
