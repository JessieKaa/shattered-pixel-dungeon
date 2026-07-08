-- M10a port of Remished Backstab. Original: self-cast, finds nearest distracted enemy, sqrt(skill)*weapon damage.
-- Degrade: no nearest-enemy query / belongings.weapon:damageRoll / state-tag API (needs M10b level + hero API).
-- Here: enemy-targeted, fixed bonus damage. distracted-state / awareness check dropped.
-- Original: ../remixed-dungeon/scripts/spells/Backstab.lua
register_spell {
    id = "backstab",
    name = "背刺",
    desc = "对选中的敌人造成固定伤害(原版基于最近分心敌人+武器伤害,降级为定点伤害)。",
    image = 2,
    castTime = 1,
    useMode = "mana",
    spellCost = 2,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charAtCell and RPD.damageChar) then return end
        local target = RPD.charAtCell(cell)
        if target then
            RPD.damageChar(target, 15)
        end
    end,
}
