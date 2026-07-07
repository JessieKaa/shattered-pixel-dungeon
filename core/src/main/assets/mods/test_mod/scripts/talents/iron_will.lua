-- M7e 代表 talent override:IRON_WILL(Warrior T1, vanilla max=2)。
-- 只覆写 desc(desc-only,不动 maxPoints),验证 desc fallback。
-- desc 是整串替换(override-first,覆盖 meta_desc);tier-aware 留 M8。
register_talent_override {
    id = "IRON_WILL",
    desc = "Lua override: 提供基于最大生命值的护盾(Lua 覆写文案)。",
}
