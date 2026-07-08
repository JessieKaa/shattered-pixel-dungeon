-- M10a port of Remished Calm. Original: castOnChar Sleep on target.
-- Sleep is in the SPD buff whitelist; skillLevel scaling dropped (no hero skillLevel API).
-- Original: ../remixed-dungeon/scripts/spells/Calm.lua
register_spell {
    id = "calm",
    name = "安抚术",
    desc = "使敌人沉睡(Sleep)。",
    image = 1,
    castTime = 0,
    useMode = "mana",
    spellCost = 2,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charAtCell and RPD.affectBuff) then return end
        local target = RPD.charAtCell(cell)
        if target then
            RPD.affectBuff(target, "Sleep", 5)
        end
    end,
}
