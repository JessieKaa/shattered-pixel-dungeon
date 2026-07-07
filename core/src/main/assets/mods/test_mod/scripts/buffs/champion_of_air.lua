-- M7a port of Remished scripts/buffs/ChampionOfAir.lua
-- Remished: hasteLevel + glow tint. M7a bridges the haste effect via the new
-- speed hook (*1.5). The glow tint needs a sprite/visual hook not in this
-- feature set and stays deferred to M7b.
register_buff{
    id = "champion_of_air",
    name = "ChampionOfAir",
    info = "ChampionOfAir (M7a: haste via speed hook; glow tint M7b)",
    icon = 0,

    attachTo = function(targetId, state)
        return true
    end,

    speed = function(selfId, spd)
        return spd * 1.5
    end,
}
