-- M7b port of Remished scripts/buffs/ChampionOfWater.lua
-- Remished: defenceSkillBonus = lvl * 1.25 + glow. M7b wires defenceSkillBonus
-- via the defenseSkill hook (bearer's Lua buff amends its own evasion at the
-- Char.hit / Stone.proc read sites). glow is a sprite-tint effect left to M8
-- (no sprite-tint hook in this feature set); defenceSkill is the gameplay-
-- load-bearing half and is now bridged.
register_buff{
    id = "champion_of_water",
    name = "ChampionOfWater",
    info = "ChampionOfWater (M7b: defenseSkill bonus via hook; glow M8)",
    icon = 0,

    attachTo = function(targetId, state)
        return true
    end,

    defenseSkill = function(selfId, def)
        local lvl = RPD.buffLevel(selfId, "champion_of_water") or 1
        return def + math.floor(lvl * 1.25)
    end,
}
