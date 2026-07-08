-- remished_lite showcase weapon: a LuaItem (MeleeWeapon wrapper). Obtained inside the
-- hub via the guide NPC's RPD.giveItem (LuaItemRegistry.createItem path), NOT a main-game
-- drop — Generator.LUA_ITEM has firstProb/secondProb 0 so the standard drop deck never
-- selects it (C3). register_item requires id/name (+ tier unless type="material").
-- Minimal but real: an attackProc damage bump proves it is a functional weapon, not a stub.
register_item {
    id = "remished_lite_lantern_blade",
    name = "灯笼刃 (Lua)",
    desc = "Remished Lite 展示武器:命中时额外造成 2 点伤害。由 hub 关卡的向导 NPC 发放。",
    image = 75,
    tier = 2,

    attackProc = function(attackerId, defenderId, baseDamage, state)
        return baseDamage + 2
    end,
}
