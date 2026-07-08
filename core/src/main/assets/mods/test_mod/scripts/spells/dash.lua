-- M10a port of Remished Dash. Original: castOnCell, Ballistica + push chars/objects + AoE damage along path.
-- Degrade: no Ballistica / push / forCellsAround API (needs M10b level). Implemented as: damage charAtCell(cell)
-- if occupied, then teleport hero to the cell (or an empty neighbour if occupied — teleportChar rejects
-- occupied cells only via passable, so route through emptyCellNextTo when a target sits on the cell).
-- distance limit / push / path AoE dropped.
-- Original: ../remixed-dungeon/scripts/spells/Dash.lua
register_spell {
    id = "dash",
    name = "冲刺术",
    desc = "传送到选中格并对格上敌人造成伤害(teleportChar + damageChar 近似;原版含击退/路径伤害,降级)。",
    image = 2,
    castTime = 0.5,
    useMode = "mana",
    spellCost = 10,
    targeting = "cell",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charAtCell and RPD.damageChar and RPD.teleportChar and RPD.emptyCellNextTo) then return end
        local target = RPD.charAtCell(cell)
        if target then
            RPD.damageChar(target, 10)
            -- destination occupied: land on an empty neighbour instead of stacking on the enemy
            cell = RPD.emptyCellNextTo(cell)
        end
        RPD.teleportChar(heroId, cell)
    end,
}
