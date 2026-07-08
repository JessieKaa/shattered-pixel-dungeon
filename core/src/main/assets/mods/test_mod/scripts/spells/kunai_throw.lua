-- M10a port of Remished KunaiThrow. Original: self-cast, hit N random visible enemies (tier-scaled).
-- Degrade: no visibleEnemies / randomEnemy query (needs enemy-query API); implemented as enemy-targeted
-- single-target damage with a zap effect. multi-target / tier scaling dropped.
-- Original: ../remixed-dungeon/scripts/spells/KunaiThrow.lua
register_spell {
    id = "kunai_throw",
    name = "苦无投掷",
    desc = "对选中敌人投掷苦无造成伤害(原版随机多目标,降级为单目标)。",
    image = 3,
    castTime = 0.01,
    useMode = "mana",
    spellCost = 5,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charPos and RPD.charAtCell and RPD.zapEffect and RPD.damageChar) then return end
        local from = RPD.charPos(heroId)
        RPD.zapEffect(from, cell)
        local target = RPD.charAtCell(cell)
        if target then
            RPD.damageChar(target, 8)
        end
    end,
}
