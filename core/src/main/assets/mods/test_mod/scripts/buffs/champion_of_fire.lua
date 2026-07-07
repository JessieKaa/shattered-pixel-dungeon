-- M7a port of Remished scripts/buffs/ChampionOfFire.lua
-- Remished: attackProc applies Burning to enemy + attackSkillBonus. M7a wires
-- attackProc (apply Burning to the enemy on each hit). attackSkillBonus (a to-
-- hit modifier) has no SPD hook in this feature set and is deferred to M7b.
register_buff{
    id = "champion_of_fire",
    name = "ChampionOfFire",
    info = "ChampionOfFire (M7a: on-hit Burning via attackProc; attackSkillBonus M7b)",
    icon = 0,

    attachTo = function(targetId, state)
        return true
    end,

    attackProc = function(selfId, enemyId, damage)
        RPD.affectBuff(enemyId, "Burning", 4)
        return damage
    end,
}
