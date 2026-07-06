-- M6-fast C 路径数据皮:Remished 纯数据材料 item 的搬运验证。
-- 原件: ../remished-dungeon/scripts/items/ToxicGland.lua (Plague Doctor 材料类,21 行,无行为回调)
-- 见 rotten_organ.lua 头注对 C 路径局限的说明(类型错配 + price/stackable 不被读取)。
register_item {
    id = "toxic_gland",
    name = "毒腺",
    desc = "含有毒素的生物腺体。(C 路径数据皮:材料语义未保留,实际作为 tier=0 武器加载。)",
    image = 3,
    tier = 0,
    price = 5,
    stackable = true,
}
