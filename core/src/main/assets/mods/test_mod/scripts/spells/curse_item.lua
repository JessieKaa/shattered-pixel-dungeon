-- M11d: CurseItem made minimal-usable. Original: self-cast item selector that curses a chosen item.
-- SPD has no item-selector UI, so this curses a uniformly-random backpack item via the new
-- RPD.setItemCursed one-way API. Original: ../remixed-dungeon/scripts/spells/CurseItem.lua
register_spell {
    id = "curse_item",
    name = "诅咒物品",
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
        local ok = RPD.setItemCursed(heroId, idx)
        if ok == true then
            local name = RPD.itemName(heroId, idx)
            if RPD.GLog then RPD.GLog("诅咒了 " .. tostring(name)) end
        elseif ok == false then
            if RPD.GLogW then RPD.GLogW("该物品已被诅咒。") end
        end
    end,
}
