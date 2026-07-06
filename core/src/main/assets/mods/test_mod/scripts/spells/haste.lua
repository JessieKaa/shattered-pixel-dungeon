-- M6d 代表 spell:Haste。走 affectBuff(charId, "Haste", duration)。
-- 原件: ../remished-dungeon/scripts/spells/Haste.lua
register_spell {
    id = "haste",
    name = "加速术",
    desc = "短暂提升施法者速度。",
    image = 2,
    castTime = 1,
    spellCost = 4,
    targeting = "self",

    onUse = function(heroId)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(heroId, "Haste", 10)
        end
    end,
}
