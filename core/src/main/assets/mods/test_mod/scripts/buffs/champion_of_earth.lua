-- M6c port of Remished scripts/buffs/ChampionOfEarth.lua
-- Remished: x4 HT + heal, drBonus, regenerationBonus. M6c bridges HT/heal via
-- RPD.healChar but cannot bridge drBonus/regenerationBonus (no Char hook), so
-- those are degraded. attachTo heals the bearer once (one-shot, guarded by
-- state so a restore-replay would not re-heal).
register_buff{
    id = "champion_of_earth",
    name = "ChampionOfEarth",
    info = "ChampionOfEarth (M6c: one-shot heal on attach; DR/regen not bridged)",
    icon = 0,
    degraded = true,
    degradation = "drBonus/regenerationBonus need Char hooks not exposed in M6c; HT scaling is not applied (would need a safe Char.HT setter).",

    attachTo = function(targetId, state)
        if not state.activated then
            state.activated = true
            RPD.healChar(targetId, 20)
        end
        return true
    end,
}
