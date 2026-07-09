-- remished_lite guide NPC: the hub greeter. onInteract emits a yell + opens a dialog, then
-- hands the hero the showcase Lua weapon via RPD.giveItem (LuaItemRegistry.createItem path).
-- The NPC is passive/invincible (LuaNpc clones RatKing overrides) and only appears in-game
-- when the hub level's mobs[] references it as "lua_npc:remished_lite_guide" — it is NOT
-- part of the vanilla spawn rotation. Lua never receives a Char object — only int ids.
--
-- One-shot handout (M14b): the weapon is granted at most once per hero per LuaEngine
-- session. A script-level `granted` table (keyed by hero id) guards RPD.giveItem so
-- repeated interacts cannot farm the showcase weapon (the prior version re-gave it every
-- interact, "topping up the backpack"). The flag is set only after giveItem returns true,
-- so a failed handout (e.g. full backpack) leaves the hero un-marked and able to retry.
-- The table is session-scoped (LuaEngine outlives a single hub visit but re-inits across
-- save/load); re-entering the hub after a reload to receive one more weapon is acceptable
-- for a showcase and is not a farming loop. RPD exposes no hasItem query, so a Lua-side
-- guard is the simplest showcase-local fix (no Java change).
local granted = {}

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
        -- One-shot: grant the showcase weapon only on the first successful handout for
        -- this hero. giveItem returns bool; mark granted only on success so a full backpack
        -- (or any failure) leaves the hero un-marked and free to retry on a later interact.
        if not granted[heroId] then
            if RPD.giveItem(heroId, "remished_lite_lantern_blade", 1) then
                granted[heroId] = true
            end
        end
    end,
}
