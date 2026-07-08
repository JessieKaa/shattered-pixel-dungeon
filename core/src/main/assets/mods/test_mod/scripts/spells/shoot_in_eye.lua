-- M10a port of Remished ShootInEye. Original: castOnChar, zapEffect + Blindness + sqrt(skill)*weapon damage.
-- Blindness is in the SPD buff whitelist. weapon-damageRoll dropped (no belongings API); fixed damage.
-- Original: ../remixed-dungeon/scripts/spells/ShootInEye.lua
register_spell {
    id = "shoot_in_eye",
    name = "射眼术",
    desc = "致盲选中敌人并造成伤害(Blindness + damageChar;原版含武器伤害,降级为固定伤害)。",
    image = 0,
    castTime = 1,
    useMode = "mana",
    spellCost = 5,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charPos and RPD.zapEffect and RPD.charAtCell and RPD.affectBuff and RPD.damageChar) then return end
        local from = RPD.charPos(heroId)
        RPD.zapEffect(from, cell)
        local target = RPD.charAtCell(cell)
        if target then
            RPD.affectBuff(target, "Blindness", 5)
            RPD.damageChar(target, 10)
        end
    end,
}
