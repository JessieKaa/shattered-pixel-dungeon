-- M10a port of Remished CurseItem. Original: self-cast, opens item selector, curses chosen backpack item.
-- Degrade: no item-selector UI / item:cursed API in the SPD sandbox (needs an item-selection bridge,
-- not yet planned). Stub: logs a hint. Registered so the spell book is complete; effect pending that API.
-- Original: ../remixed-dungeon/scripts/spells/CurseItem.lua
register_spell {
    id = "curse_item",
    name = "诅咒物品",
    desc = "诅咒背包中的一件物品(降级:SPD 暂无物品选择器 API,施法仅提示,无效,零消耗)。",
    image = 0,
    castTime = 0,
    useMode = "mana",
    spellCost = 0,
    targeting = "self",

    onUse = function(heroId)
        if RPD and RPD.GLogW then
            RPD.GLogW("诅咒物品需要物品选择器 API(未实现),施法无效。")
        end
    end,
}
