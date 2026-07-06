-- M6c port of Remished scripts/buffs/ChampionOfWater.lua
-- Remished: defenceSkillBonus + glow. M6c has no defenceSkillBonus hook, so
-- degraded; metadata + lifecycle only.
register_buff{
    id = "champion_of_water",
    name = "ChampionOfWater",
    info = "ChampionOfWater (M6c degraded: defenceSkillBonus not bridged)",
    icon = 0,
    degraded = true,
    degradation = "defenceSkillBonus needs a Char hook not exposed in M6c. Metadata + lifecycle only.",

    attachTo = function(targetId, state)
        return true
    end,
}
