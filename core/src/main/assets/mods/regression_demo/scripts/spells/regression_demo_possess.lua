-- M11d spell-stub representative: enemy-targeted mana spell. The minimal "possession"
-- effect is MagicalSleep — the target is paralysed and put into the SLEEPING state
-- (the actual hard-control path). amount is ignored by the MagicalSleep applier but
-- must be > 0 (affectBuff's validAmount gate). Mirrors test_mod/possess.lua.
register_spell {
    id = "regression_demo_possess",
    name = "回归附身",
    desc = "使选中敌人陷入沉睡(MagicalSleep)。",
    image = 0,
    castTime = 1,
    useMode = "mana",
    spellCost = 10,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charAtCell and RPD.affectBuff) then return end
        local enemy = RPD.charAtCell(cell)
        if enemy ~= nil then
            RPD.affectBuff(enemy, "MagicalSleep", 1)
        end
    end,
}
