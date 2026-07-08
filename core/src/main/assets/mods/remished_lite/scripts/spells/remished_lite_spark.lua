-- remished_lite showcase spell. Registered-but-inert: RPD.giveItem only resolves Lua items
-- (LuaItemRegistry.createItem), and SPD has no starter-spell / spellbook acquisition path
-- for Lua spells yet (deferred per PLAN). So this spell is registered (registry coverage +
-- C3 demonstration) but has no acquisition path — it does NOT appear in main game nor in
-- the hub. register_spell requires id/name only; targeting/useMode/onUse optional.
-- Minimal self-cast form mirroring regression_demo_curse.
register_spell {
    id = "remished_lite_spark",
    name = "微光术 (Lua)",
    desc = "Remished Lite 展示法术(registered-but-inert:无获取路径,PLAN 延后 spellbook UI)。",
    image = 0,
    castTime = 0,
    useMode = "mana",
    spellCost = 3,
    targeting = "self",

    onUse = function(heroId)
        if RPD and RPD.GLogW then RPD.GLogW("一缕微光闪过(展示法术,无实际效果)。") end
    end,
}
