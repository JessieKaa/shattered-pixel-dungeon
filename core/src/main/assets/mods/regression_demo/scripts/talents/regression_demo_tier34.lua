-- M8d3 representative: register_talent tier 3 (subclass dispatch branch) + tier 4
-- (armor_ability dispatch branch) — the two remaining branches beyond the tier<=2
-- `class` path covered by regression_demo_tier2.lua. Both activate pre-declared MOD_
-- enum slots; Lua cannot mint new ids at runtime.
--   tier 3 keys on `subclass` (HeroSubClass enum name, e.g. BERSERKER)
--   tier 4 keys on `armor_ability` (ArmorAbility simple class name, e.g. HeroicLeap)

register_talent {
    id = "MOD_TIER3_TALENT",
    tier = 3,
    subclass = "BERSERKER",
    name = "回归子类天赋(tier3)",
    maxPoints = 3,
    desc = "M11e 回归 tier3:把 MOD_TIER3_TALENT 注入 BERSERKER 的 tier-3 槽。",
    on_upgrade = function(heroId, points)
        RPD.giveItem(heroId, "regression_demo_pickaxe", 1)
    end,
}

register_talent {
    id = "MOD_TIER4_TALENT",
    tier = 4,
    armor_ability = "HeroicLeap",
    name = "回归护甲天赋(tier4)",
    maxPoints = 4,
    desc = "M11e 回归 tier4:把 MOD_TIER4_TALENT 注入 HeroicLeap 的 tier-4 槽。",
    on_upgrade = function(heroId, points)
        RPD.giveItem(heroId, "regression_demo_pickaxe", 1)
    end,
}
