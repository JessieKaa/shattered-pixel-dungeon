-- M6d material item: Remished Plague Doctor 纯数据材料 (RottenOrgan).
-- 走 LuaMaterial(plain Item)而非 weapon wrapper,材料语义正确:可堆叠、按 price 定价、不进武器槽。
-- 原件: ../remished-dungeon/scripts/items/RottenOrgan.lua
register_item {
    id = "rotten_organ",
    type = "material",
    name = "腐烂器官",
    desc = "从瘟疫医生采集系统获得的腐烂器官。",
    image = 4,
    price = 5,
    stackable = true,
}
