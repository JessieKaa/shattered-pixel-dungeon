-- M3d test spell: a Lua-defined consumable that fires onUse(heroId).
-- Exists to prove the register_spell pipeline wires a Lua table into
-- LuaSpell.execute → LuaItemCallbacks.callOpt("onUse", ...).

register_spell {
    id = "test_spell",
    name = "测试法术 (Lua)",
    desc = "M3d 消耗性法术:使用即消耗,onUse 回调验证 charId 范式 + RPD API。",
    image = 0,
    spriteFile = "sprites/items/item_HookedDagger.png",

    onUse = function(heroId)
        -- RPD.GLog is injected by the engine (M2). Reaching it from here
        -- proves the sandbox surface is still wired after M3d additions.
        if RPD and RPD.GLog then
            RPD.GLog("spell used on hero " .. tostring(heroId))
        end
        -- Track that the callback actually ran (read back by LuaSpellTest
        -- via the registry table — execute's callOpt path is exercised
        -- directly here so the test does not need a live GameScene).
        _M_test_spell_fired = true
    end,
}
