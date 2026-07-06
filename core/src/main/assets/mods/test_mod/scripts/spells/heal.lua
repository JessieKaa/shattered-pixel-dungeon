-- M6d 代表 spell:Heal。走窄 RPD API:healChar(charId, amount)。
-- 原件: ../remished-dungeon/scripts/spells/Heal.lua(castOnChar 用 target:heal + skillLevel)
-- 这里用 hero 的当前 HP 近似治疗量;skill-level scaling 留给 M6e(无 hero skillLevel API)。
register_spell {
    id = "heal",
    name = "治疗术",
    desc = "恢复施法者部分生命值。",
    image = 2,
    castTime = 1,
    spellCost = 5,
    targeting = "self",

    onUse = function(heroId)
        if RPD and RPD.healChar then
            RPD.healChar(heroId, 20)
        end
    end,
}
