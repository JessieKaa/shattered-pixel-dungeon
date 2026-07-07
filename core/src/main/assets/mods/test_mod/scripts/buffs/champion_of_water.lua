-- M7b port of Remished scripts/buffs/ChampionOfWater.lua
-- Remished: defenceSkillBonus = lvl * 1.25 + glow. M7b wires defenceSkillBonus
-- via the defenseSkill hook (bearer's Lua buff amends its own evasion at the
-- Char.hit / Stone.proc read sites). M8c adds the glow half: tintChar returns
-- a blue aura (ChampionEnemy-style sprite.aura) so the bearer visibly glows.
-- defenceSkill is the gameplay-load-bearing half; glow is the cosmetic half.
register_buff{
    id = "champion_of_water",
    name = "ChampionOfWater",
    info = "ChampionOfWater (M7b: defenseSkill bonus; M8c: blue glow aura)",
    icon = 0,

    attachTo = function(targetId, state)
        return true
    end,

    defenseSkill = function(selfId, def)
        local lvl = RPD.buffLevel(selfId, "champion_of_water") or 1
        return def + math.floor(lvl * 1.25)
    end,

    -- M8c: blue glow aura on the bearer. Color is 0x3399FF (water blue) as a
    -- decimal literal (luaj 3.0.1 = Lua 5.1 has no hex numeric literals).
    tintChar = function(selfId, state)
        return { color = 3381759, rays = 5 }
    end,
}
