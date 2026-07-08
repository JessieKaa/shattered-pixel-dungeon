-- M10a material item: Remished RawFish(RawFish)。可堆叠食物,按价定价。
-- 降级(M10c/LuaMaterial 无 eat 派发):eat(Poison + Hunger HUNGRY)、burn→FriedFish、freeze→FrozenFish、poison→RottenFish 转换未接。
-- 原件: ../remished-dungeon/scripts/items/RawFish.lua
register_item {
    id = "raw_fish",
    type = "material",
    name = "生鱼",
    desc = "一条生鱼。直接吃会中毒(降级:eat 未接,仅可堆叠/出售)。",
    image = 12,
    price = 7,
    stackable = true,
}
