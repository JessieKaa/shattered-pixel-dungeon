-- M10a port of Remished MagicArrow. Original: castOnChar, zapEffect + random damage scaled by caster ht.
-- SPD has charPos / charAtCell / zapEffect / damageChar. skill / ht scaling dropped (fixed damage).
-- Original: ../remixed-dungeon/scripts/spells/MagicArrow.lua
register_spell {
    id = "magic_arrow",
    name = "魔箭术",
    desc = "对选中敌人射出魔法箭(zapEffect + damageChar)。",
    image = 0,
    castTime = 0.1,
    useMode = "mana",
    spellCost = 5,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charPos and RPD.zapEffect and RPD.charAtCell and RPD.damageChar) then return end
        local from = RPD.charPos(heroId)
        RPD.zapEffect(from, cell)
        local target = RPD.charAtCell(cell)
        if target then
            RPD.damageChar(target, 10)
        end
    end,
}
