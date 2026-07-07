-- M7e 代表 talent override:HEARTY_MEAL(Warrior T1, vanilla max=2)。
-- 下调 maxPoints 到 1 + 覆写 desc。验证 LuaTalentOverride 注册表 +
-- Talent.maxPoints()/desc() 单点 fallback 全链路。
--
-- 注意(M7e scope, modder beware):
--   * maxPoints 只许 ≤ vanilla。Lua 给 >2 会在 register 时被拒绝(不静默钳位)。
--   * 下调 maxPoints 对已点满的天赋是【不可逆钳位】:存档写入时 Bundle 按
--     当前 maxPoints() 钳位,trim 掉的点数即使之后禁用 mod 也回不来。
--   * "放大"天赋效果(>vanilla)留 M8 effectivePointsInTalent() 迁移 + allowlist。
register_talent_override {
    id = "HEARTY_MEAL",
    maxPoints = 1,
    desc = "Lua override: 吃饱时回复的生命值降低(封顶 1 点)。",
}
