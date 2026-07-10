-- M20g: Tengu Liver. Eatable material ported from test_mod/scripts/items/tengu_liver.lua.
-- onEat 调 RPD.healChar 回复体力,沿用 rotten_fish onEat + heal spell healChar 范式
-- (test m19e_rottenFish_onEatAppliesPoison 验证 onEat 路由;enabled_selfSpellOnUseHealsHero 验证 healChar)。
register_item {
    id = "rf_tengu_liver",
    type = "material",
    name = "tengu 之肝",
    desc = "传说中的珍馐。吃下它能恢复一些体力。",
    image = 51,
    price = 20,
    stackable = false,
    defaultAction = "EAT",
    energy = 400,
    spriteFile = "sprites/items/item_TenguLiver.png",

    onEat = function(heroId, itemId)
        if RPD and RPD.healChar then
            RPD.healChar(heroId, 10)
        end
    end,
}
