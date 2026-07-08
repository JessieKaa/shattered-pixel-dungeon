-- M10a port of Remished NatureArmor. Original: self, affectBuff Barkskin scaled by skill*level.
-- Barkskin is in the SPD buff whitelist. skill / level scaling dropped (fixed amount).
-- Original: ../remixed-dungeon/scripts/spells/NatureArmor.lua
register_spell {
    id = "nature_armor",
    name = "自然护甲",
    desc = "获得树皮护甲(Barkskin)。",
    image = 3,
    castTime = 0.5,
    useMode = "mana",
    spellCost = 5,
    targeting = "self",

    onUse = function(heroId)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(heroId, "Barkskin", 20)
        end
    end,
}
