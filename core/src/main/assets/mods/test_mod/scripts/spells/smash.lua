-- M10a port of Remished Smash. Original: self-cast, AoE damage + Vertigo on adjacent enemies (forCellsAround).
-- Degrade: no forCellsAround / AoE API (needs M10b level); implemented as enemy-targeted single-target
-- Vertigo + damage. AoE / weapon-roll dropped.
-- Original: ../remixed-dungeon/scripts/spells/Smash.lua
register_spell {
    id = "smash",
    name = "猛击术",
    desc = "眩晕选中敌人并造成伤害(Vertigo + damageChar;原版范围伤害,降级为单目标)。",
    image = 3,
    castTime = 0.5,
    useMode = "mana",
    spellCost = 10,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charAtCell and RPD.affectBuff and RPD.damageChar) then return end
        local target = RPD.charAtCell(cell)
        if target then
            RPD.affectBuff(target, "Vertigo", 5)
            RPD.damageChar(target, 15)
        end
    end,
}
