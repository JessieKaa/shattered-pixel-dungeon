-- M10a port of Remished Roar. Original: self-cast, Terror on N random enemies (skill-scaled).
-- Degrade: no randomEnemy query (needs enemy-query API); implemented as enemy-targeted single Terror.
-- multi-target / skill scaling dropped.
-- Original: ../remixed-dungeon/scripts/spells/Roar.lua
register_spell {
    id = "roar",
    name = "咆哮术",
    desc = "恐吓选中敌人(Terror;原版群体随机,降级为单目标)。",
    image = 0,
    castTime = 0,
    useMode = "mana",
    spellCost = 1,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charAtCell and RPD.affectBuff) then return end
        local target = RPD.charAtCell(cell)
        if target then
            RPD.affectBuff(target, "Terror", 10)
        end
    end,
}
