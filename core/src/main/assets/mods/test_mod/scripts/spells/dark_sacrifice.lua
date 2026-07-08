-- M10a port of Remished DarkSacrifice. Original: castOnChar own pet, kill it + placeBlob LiquidFlame at its pos.
-- Degrade: no getOwnerId ally check; LiquidFlame not in SPD blob whitelist → Fire. Implemented as
-- enemy-targeted: big damage + Fire blob. skillLevel scaling dropped.
-- Original: ../remixed-dungeon/scripts/spells/DarkSacrifice.lua
register_spell {
    id = "dark_sacrifice",
    name = "黑暗献祭",
    desc = "对选中敌人造成重伤害并在其位置点燃(damageChar + placeBlob Fire;原版限友方+LiquidFlame,降级)。",
    image = 1,
    castTime = 0.5,
    useMode = "mana",
    spellCost = 3,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charAtCell and RPD.damageChar and RPD.placeBlob) then return end
        local target = RPD.charAtCell(cell)
        if target then
            RPD.damageChar(target, 50)
            RPD.placeBlob("Fire", cell, 20)
        end
    end,
}
