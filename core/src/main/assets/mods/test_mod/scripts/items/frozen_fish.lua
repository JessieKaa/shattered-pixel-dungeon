-- M10a material item: Remished FrozenFish。可堆叠冻鱼,按价定价。
-- 降级(M10c/LuaMaterial 无 onThrow/burn 派发):onThrow(投进水里召唤 Piranha)、burn→FriedFish 转换未接。
-- 原件: ../remished-dungeon/scripts/items/FrozenFish.lua
register_item {
    id = "frozen_fish",
    type = "material",
    name = "冻鱼",
    desc = "一条冻得硬邦邦的鱼(降级:onThrow 召唤食人鱼 / burn 化为煎鱼 未接,仅可堆叠/出售)。",
    image = 14,
    price = 15,
    stackable = true,
}
