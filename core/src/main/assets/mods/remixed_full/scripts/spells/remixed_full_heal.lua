-- M16b alpha spell: Heal. Self-cast mana spell that restores health.
register_spell {
    id = "remixed_full_heal",
    name = "治疗术",
    desc = "恢复施法者少量生命值。",
    image = 2,
    castTime = 1,
    useMode = "mana",
    spellCost = 5,
    targeting = "self",
    spriteFile = "mods/remixed_full/sprites/spells/spell_Heal.png",

    onUse = function(heroId)
        if RPD and RPD.healChar then
            RPD.healChar(heroId, 20)
        end
    end,
}
