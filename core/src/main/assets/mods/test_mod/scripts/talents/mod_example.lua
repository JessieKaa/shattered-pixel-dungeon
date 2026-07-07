-- M8d1 (D6(b) MVP): register a NEW talent into the Warrior tier-2 slot.
-- `id` MUST be a MOD_-prefixed Talent enum constant (MOD_EXAMPLE_TALENT,
-- declared in Talent.java) — tier keys are Talent constants, so Lua cannot
-- mint entirely new ids at runtime; it activates a pre-declared slot.
--
-- `name`/`maxPoints`/`desc` are forwarded by the engine to LuaTalentOverride
-- (the M7e path), so Talent.title()/maxPoints()/desc() pick these values up
-- via the existing fallback — no second override source.
--
-- Only loaded when test_mod is enabled (default_enabled=false), so the C3
-- vanilla baseline (every mod disabled → empty registry → injectClassTalents
-- is a no-op) holds.
register_talent {
    id = "MOD_EXAMPLE_TALENT",
    tier = 2,
    class = "WARRIOR",
    name = "Lua 新天赋（示例）",
    maxPoints = 2,
    desc = "Lua 新天赋示例（D6(b) MVP）：证明 register_talent 能把一个预声明的 MOD_ enum 槽位注入到指定职业的 tier 列表，玩家可用天赋点升级。",
}
