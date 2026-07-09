-- M16b alpha spell: Blink. Self-cast short-range teleport to a neighbouring
-- empty cell. Falls back to a Haste buff if no empty adjacent cell exists.
register_spell {
    id = "remixed_full_blink",
    name = "闪烁",
    desc = "将施法者传送至附近的一个空格;若无可用空格,则暂时加速。",
    image = 3,
    castTime = 1,
    useMode = "mana",
    spellCost = 6,
    targeting = "self",
    spriteFile = "mods/remixed_full/sprites/spells/spell_TownPortal.png",

    onUse = function(heroId)
        if RPD and RPD.charPos and RPD.emptyCellNextTo and RPD.teleportChar then
            local from = RPD.charPos(heroId)
            local dest = from and RPD.emptyCellNextTo(from)
            if dest then
                RPD.teleportChar(heroId, dest)
                return
            end
        end
        if RPD and RPD.affectBuff then
            RPD.affectBuff(heroId, "Haste", 5)
        end
    end,
}
