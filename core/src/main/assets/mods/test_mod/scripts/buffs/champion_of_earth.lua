-- M7a port of Remished scripts/buffs/ChampionOfEarth.lua
-- Remished: x4 HT + heal, drBonus, regenerationBonus. M7a bridges drBonus via
-- the new drRoll hook; attachTo heals the bearer once (one-shot, guarded by
-- state so a restore-replay would not re-heal). regenerationBonus (HP/tick) and
-- HT-scaling need hooks not in this feature set and stay deferred to M7b.
register_buff{
    id = "champion_of_earth",
    name = "ChampionOfEarth",
    info = "ChampionOfEarth (M7a: one-shot heal + drRoll; regenBonus/HT-scale M7b)",
    icon = 0,

    attachTo = function(targetId, state)
        if not state.activated then
            state.activated = true
            RPD.healChar(targetId, 20)
        end
        return true
    end,

    drRoll = function(selfId, dr)
        return dr + 5
    end,
}
