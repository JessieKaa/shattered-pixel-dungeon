-- M6c port of Remished scripts/buffs/ChampionOfFire.lua
-- Remished: attackProc applies Burning to enemy + attackSkillBonus. M6c has
-- no attackProc hook on a generic Lua buff, so Burning-on-hit is degraded.
register_buff{
    id = "champion_of_fire",
    name = "ChampionOfFire",
    info = "ChampionOfFire (M6c degraded: on-hit Burning not bridged)",
    icon = 0,
    degraded = true,
    degradation = "attackProc/attackSkillBonus need Char/Mob hooks not exposed in M6c for a generic buff. Metadata + lifecycle only.",

    attachTo = function(targetId, state)
        return true
    end,
}
