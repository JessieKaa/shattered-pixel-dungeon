-- M19e: Fried Fish. Stackable food that fully satiates, ported from remixed FriedFish.lua.
-- 降级:
--   - remixed taste 文案 fork LuaMaterial 无 taste 机制 → 丢弃;
--   - remixed hunger satisfaction 用 STARVING(全饱)→ fork energy=450(STARVING=450);
--   - poison 转换保留:poisonTransform 指向 remixed_full_rotten_fish(fork LuaMaterial.poisonTransformId 支持,lazy)。
-- 核心"烤鱼饱腹"保留。
register_item {
    id = "remixed_full_fried_fish",
    type = "material",
    name = "烤鱼",
    desc = "一条烤得喷香的鱼,吃下能完全填饱肚子。",
    image = 13,
    price = 30,
    stackable = true,
    defaultAction = "EAT",
    energy = 450,
    poisonTransform = "remixed_full_rotten_fish",
    spriteFile = "sprites/items/item_FriedFish.png",
}
