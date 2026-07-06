-- M6d 代表 spell:Sprout。placeBlob Regrowth(自然系)。
-- 原件: ../remished-dungeon/scripts/spells/Sprout.lua
register_spell {
    id = "sprout",
    name = "催生术",
    desc = "在脚下催生植被(placeBlob Regrowth)。",
    image = 0,
    castTime = 1,
    spellCost = 2,
    targeting = "self",

    onUse = function(heroId)
        if not (RPD and RPD.charPos and RPD.placeBlob) then return end
        local pos = RPD.charPos(heroId)
        RPD.placeBlob("Regrowth", pos, 10)
    end,
}
