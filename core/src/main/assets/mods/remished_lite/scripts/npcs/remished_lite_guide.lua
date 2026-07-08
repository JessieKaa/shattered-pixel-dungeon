-- remished_lite guide NPC: the hub greeter. onInteract emits a yell + opens a dialog, then
-- hands the hero the showcase Lua weapon via RPD.giveItem (LuaItemRegistry.createItem path).
-- The NPC is passive/invincible (LuaNpc clones RatKing overrides) and only appears in-game
-- when the hub level's mobs[] references it as "lua_npc:remished_lite_guide" — it is NOT
-- part of the vanilla spawn rotation. Lua never receives a Char object — only int ids.
register_npc {
    id = "remished_lite_guide",
    name = "向导",
    sprite = "wandmaker",

    onInteract = function(selfId, heroId)
        RPD.npcYell(selfId, "欢迎来到 Remished Lite 展示厅!")
        RPD.showDialog(selfId,
            "这是一个展示 fork modding 平台的 hub 关卡。\n\n" ..
            "收下这把展示武器(灯笼刃),它由 Lua 注册、经我发放给你 —— \n" ..
            "main game 的掉落表完全不受影响(C3)。\n\n" ..
            "东边有商店,可以买卖消耗品。")
        -- Hand the hero the showcase Lua weapon. giveItem resolves the id through
        -- LuaItemRegistry; returns bool but we do not branch on it (idempotent enough
        -- for a showcase — repeated interacts just top up the backpack).
        RPD.giveItem(heroId, "remished_lite_lantern_blade", 1)
    end,
}
