-- M8d3 (D6(b) 闭合): register NEW tier-3 and tier-4 mod talents.
-- `id` MUST be a MOD_-prefixed Talent enum constant (MOD_TIER3_TALENT /
-- MOD_TIER4_TALENT, declared in Talent.java with caps 3/4). Lua activates a
-- pre-declared slot — it cannot mint new ids at runtime.
--
-- tier 3 keys on `subclass` (HeroSubClass enum name); the talent is injected
-- into that subclass's tier-3 slot. tier 4 keys on `armor_ability` (the
-- ArmorAbility simple class name, e.g. "HeroicLeap"); ArmorAbility is an
-- abstract class, not an enum, so the name is matched against the live
-- ability instance's class at inject time.
--
-- `name`/`maxPoints`/`desc` are forwarded by the engine to LuaTalentOverride
-- (the M7e path), so Talent.title()/maxPoints()/desc() pick these values up
-- via the existing fallback — no second override source.
--
-- Only loaded when test_mod is enabled (default_enabled=false), so the C3
-- vanilla baseline (every mod disabled → empty registry → inject* noop) holds.

-- tier 3: a Berserker-only subclass talent (cap 3).
register_talent {
    id = "MOD_TIER3_TALENT",
    tier = 3,
    subclass = "BERSERKER",
    name = "Lua 子类天赋（tier3 示例）",
    maxPoints = 3,
    desc = "Lua tier3 示例：证明 register_talent 能把预声明的 MOD_TIER3_TALENT 槽位注入到指定子类（BERSERKER）的 tier-3 列表。",
    on_upgrade = function(hero, points)
        RPD.giveItem(hero, "rotten_organ", points)
    end,
}

-- tier 4: a HeroicLeap armor-ability talent (cap 4).
register_talent {
    id = "MOD_TIER4_TALENT",
    tier = 4,
    armor_ability = "HeroicLeap",
    name = "Lua 护甲天赋（tier4 示例）",
    maxPoints = 4,
    desc = "Lua tier4 示例：证明 register_talent 能把预声明的 MOD_TIER4_TALENT 槽位注入到指定护甲技能（HeroicLeap）的 tier-4 列表。",
    on_upgrade = function(hero, points)
        RPD.giveItem(hero, "rotten_organ", points)
    end,
}
