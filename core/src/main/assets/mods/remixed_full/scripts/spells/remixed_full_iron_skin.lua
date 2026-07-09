-- M16b alpha spell: Iron Skin. Self-cast buff that grants temporary Barkskin.
register_spell {
    id = "remixed_full_iron_skin",
    name = "铁皮术",
    desc = "让皮肤变得如树皮般坚韧,暂时获得护甲。",
    image = 4,
    castTime = 1,
    useMode = "mana",
    spellCost = 5,
    targeting = "self",
    spriteFile = "mods/remixed_full/sprites/spells/spell_BodyArmor.png",

    onUse = function(heroId)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(heroId, "Barkskin", 10)
        end
    end,
}
