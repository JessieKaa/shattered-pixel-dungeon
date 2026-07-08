-- M10c port of Remished scripts/buffs/ChampionOfFire.lua
-- Remished: attackProc applies Burning + attackSkillBonus + setGlowing. M10c
-- keeps attackProc (apply Burning) and wires the canonical attackSkillBonus
-- (bridges additively into attackSkill) and setGlowing (fx aura).
register_buff{
    id = "champion_of_fire",
    name = "ChampionOfFire",
    info = "ChampionOfFire (M10c: attackSkillBonus + setGlowing; attackProc Burning)",
    icon = 0,

    attachTo = function(targetId, state)
        return true
    end,

    attackProc = function(selfId, enemyId, damage)
        RPD.affectBuff(enemyId, "Burning", 4)
        return damage
    end,

    attackSkillBonus = function(selfId)
        return RPD.buffLevel(selfId, "champion_of_fire") or 1
    end,

    -- 0xAA2222 fire-red glow
    setGlowing = function(selfId, state)
        return { color = 11149858, rays = 6 }
    end,
}
