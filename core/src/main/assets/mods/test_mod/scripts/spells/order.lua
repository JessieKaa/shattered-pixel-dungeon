-- M11d: Order made minimal-usable. Original: castOnChar + makePet + ch_order (mob command).
-- SPD has no makePet / mob-command API. Charm via the buff whitelist is a no-op for control
-- (Charm only turns the target friendly when Charm.object == source.id, which the generic
-- prolong applier does not set). Terror is the minimal real effect: the enemy flees.
-- Source-aware charm is deferred to a later feature. Original: ../remixed-dungeon/scripts/spells/Order.lua
register_spell {
    id = "order",
    name = "命令术",
    desc = "命令选中敌人逃跑(Terror)。",
    image = 3,
    castTime = 1,
    useMode = "mana",
    spellCost = 8,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charAtCell and RPD.affectBuff) then return end
        local enemy = RPD.charAtCell(cell)
        if enemy ~= nil then
            RPD.affectBuff(enemy, "Terror", 10)
            if RPD.GLog then RPD.GLog("命令敌人逃跑。") end
        end
    end,
}
