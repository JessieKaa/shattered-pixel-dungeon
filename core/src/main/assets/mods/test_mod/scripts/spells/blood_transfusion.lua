-- M10a port of Remished BloodTransfusion. Original: castOnChar ally, drain target HP to heal caster.
-- Degrade: no getOwnerId ally check / target:hp() drain API; implemented as enemy-targeted:
-- damageChar target + healChar hero by a fraction. transfer-rate / skill scaling dropped.
-- Original: ../remixed-dungeon/scripts/spells/BloodTransfusion.lua
register_spell {
    id = "blood_transfusion",
    name = "输血术",
    desc = "抽取选中敌人的生命恢复自身(damageChar + healChar 近似;原版限友方目标,降级为敌方)。",
    image = 0,
    castTime = 1,
    useMode = "mana",
    spellCost = 7,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charAtCell and RPD.damageChar and RPD.healChar) then return end
        local target = RPD.charAtCell(cell)
        if target then
            local drain = 12
            RPD.damageChar(target, drain)
            RPD.healChar(heroId, math.floor(drain * 0.5))
        end
    end,
}
