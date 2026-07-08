-- M11d spell-stub representative: self-cast mana spell that curses a random backpack
-- item via the RPD.setItemCursed one-way API. SPD has no item-selector UI, so this
-- curses a uniformly-random backpack item (mirrors test_mod/curse_item.lua).
register_spell {
    id = "regression_demo_curse",
    name = "回归诅咒",
    desc = "诅咒背包中的一件随机物品(消耗法力)。",
    image = 0,
    castTime = 0,
    useMode = "mana",
    spellCost = 5,
    targeting = "self",

    onUse = function(heroId)
        if not (RPD and RPD.randomBackpackItem and RPD.setItemCursed) then return end
        local idx = RPD.randomBackpackItem(heroId)
        if idx == nil then
            if RPD.GLogW then RPD.GLogW("背包是空的。") end
            return
        end
        RPD.setItemCursed(heroId, idx)
    end,
}
