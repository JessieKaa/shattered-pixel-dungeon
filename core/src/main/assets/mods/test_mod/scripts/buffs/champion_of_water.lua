-- M10c port of Remished scripts/buffs/ChampionOfWater.lua
-- Remished: defenceSkillBonus = lvl * 1.25 + setGlowing. M10c wires the
-- canonical callbacks: defenceSkillBonus bridges additively into defenseSkill
-- (the M7b dispatch path still composes it), and setGlowing drives the M8c fx
-- aura (computeTint reads setGlowing with precedence over tintChar).
register_buff{
    id = "champion_of_water",
    name = "ChampionOfWater",
    info = "ChampionOfWater (M10c: defenceSkillBonus + setGlowing)",
    icon = 0,

    attachTo = function(targetId, state)
        return true
    end,

    defenceSkillBonus = function(selfId)
        local lvl = RPD.buffLevel(selfId, "champion_of_water") or 1
        return math.floor(lvl * 1.25)
    end,

    -- 0x3399FF water-blue glow (decimal 3381759; luaj 3.0.1 = Lua 5.1 has no hex literals)
    setGlowing = function(selfId, state)
        return { color = 3381759, rays = 5 }
    end,
}
