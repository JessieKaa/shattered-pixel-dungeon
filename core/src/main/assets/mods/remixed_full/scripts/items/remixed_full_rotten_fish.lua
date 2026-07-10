-- M19e: Rotten Fish. Stackable food that poisons on eat, ported from remixed RottenFish.lua.
-- 降级:
--   - remixed 中毒时长 2*math.random(1, hero:lvl()) 依赖 hero:lvl()(fork 不暴露)→ 固定 Poison 4 回合;
--   - remixed taste 文案("RottenFish_Taste")fork LuaMaterial 无 taste 机制 → 丢弃;
--   - hunger satisfaction remixed 用 HUNGRY/4 → fork energy=75(HUNGRY=300 的 1/4)。
-- 核心"吃下腐鱼会中毒"通过 onEat callback + RPD.affectBuff 保留。
register_item {
    id = "remixed_full_rotten_fish",
    type = "material",
    name = "腐烂的鱼",
    desc = "一条散发着恶臭的腐鱼,吃下去虽能稍稍果腹,却会让你中毒。",
    image = 15,
    price = 8,
    stackable = true,
    defaultAction = "EAT",
    energy = 75,
    onEat = function(hero)
        RPD.affectBuff(hero, "Poison", 4)
    end,
    spriteFile = "sprites/items/item_RottenFish.png",
}
