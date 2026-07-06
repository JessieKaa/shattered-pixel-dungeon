-- M6c port of Remished scripts/buffs/ChampionOfAir.lua
-- Remished: hasteLevel + glow tint. M6c has no hasteLevel/glow hook on a
-- generic Lua buff, so the haste effect is degraded; metadata + lifecycle only.
register_buff{
    id = "champion_of_air",
    name = "ChampionOfAir",
    info = "ChampionOfAir (M6c degraded: hasteLevel/glow not bridged)",
    icon = 0,
    degraded = true,
    degradation = "hasteLevel needs a Char speed hook not exposed in M6c; glow tint needs a sprite hook. Metadata + attach/detach only.",

    attachTo = function(targetId, state)
        return true
    end,
}
