-- M6-fast C 路径数据皮:Remished 纯数据材料 item 的搬运验证。
-- 原件: ../remished-dungeon/scripts/items/RottenOrgan.lua (Plague Doctor 材料类,21 行,无行为回调)
-- C 路径 = 只搬数据(名字/数值/贴图索引),行为套现有 wrapper;行为回调栏全空。
-- 局限(刻意保留,见 docs/PLAN-modding-m6-fast-data-skin.md 「结构性错配」):
--   * LuaItem extends MeleeWeapon,所以这件"材料"实际进武器槽、有 STR/damage 公式,
--     不可堆叠、不按材料定价 —— 这正是 C 路径的头号成本。
--   * price / stackable 填入但 LuaItem.hydrate 不读,留作可观测局限记录(测试锁定)。
register_item {
    id = "rotten_organ",
    name = "腐烂器官",
    desc = "从瘟疫医生采集系统获得的腐烂器官。(C 路径数据皮:材料语义未保留,实际作为 tier=0 武器加载。)",
    image = 4,            -- 占位 int,不搬 Remished materials.png(版权)
    tier = 0,             -- 材料类填 0
    price = 5,            -- hydrate 不读,记录 C 路径局限
    stackable = true,     -- hydrate 不读,记录 C 路径局限
}
