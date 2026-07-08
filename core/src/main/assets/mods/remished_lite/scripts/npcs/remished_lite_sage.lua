-- remished_lite sage NPC: a lore/dialogue-only NPC. Same passive/invincible LuaNpc base as
-- the guide; no item handout. Exists to give the hub a second interactive character and
-- demonstrate that multiple register_npc ids coexist. Referenced by the hub level's mobs[]
-- as "lua_npc:remished_lite_sage".
register_npc {
    id = "remished_lite_sage",
    name = "隐者",
    sprite = "ghost",

    onInteract = function(selfId, heroId)
        RPD.npcYell(selfId, "......你听见一阵低语。")
        RPD.showDialog(selfId,
            "这间厅堂之外,原版地牢的规则未被触动。\n\n" ..
            "这里的一切都是 Lua 定义的:NPC、商店、甚至你脚下的关卡。\n" ..
            "但它们不会渗入你的正常冒险 —— 那是 C3 的承诺。")
    end,
}
