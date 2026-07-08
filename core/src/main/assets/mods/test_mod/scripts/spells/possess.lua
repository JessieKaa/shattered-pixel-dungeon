-- M11d: Possess made minimal-usable. Original: castOnChar + makePet + ControlledAi + setControlTarget.
-- SPD has no makePet / ControlledAi / possession API. Real mob control (Corruption / AllyBuff) is
-- out of scope. The minimal "possession" effect is MagicalSleep: the target is paralysed and put
-- into the SLEEPING state (the actual hard-control path, unlike the Sleep FlavourBuff which only
-- swaps idle FX). amount is ignored by the MagicalSleep applier but must be > 0 (affectBuff's
-- validAmount gate). Original: ../remixed-dungeon/scripts/spells/Possess.lua
register_spell {
    id = "possess",
    name = "附身术",
    desc = "附身选中敌人,使其陷入沉睡(MagicalSleep)。",
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
            if RPD.GLog then RPD.GLog("敌人陷入沉睡。") end
        end
    end,
}
