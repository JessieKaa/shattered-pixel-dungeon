-- M16b alpha spell: Ignite. Cell-targeted spell that sets the target on fire.
register_spell {
    id = "remixed_full_ignite",
    name = "点燃",
    desc = "点燃选中的生物。",
    image = 5,
    castTime = 1,
    useMode = "mana",
    spellCost = 4,
    targeting = "cell",
    spriteFile = "mods/remixed_full/sprites/spells/spell_Ignite.png",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charAtCell and RPD.affectBuff) then return end
        local target = RPD.charAtCell(cell)
        if target then
            RPD.affectBuff(target, "Burning", 5)
        end
    end,
}
