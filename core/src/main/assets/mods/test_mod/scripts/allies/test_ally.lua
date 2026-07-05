-- M3b PoC test ally: a minimal Lua-defined friendly pet.
-- Demonstrates the register_ally pipeline: a Lua table is handed to Java,
-- which builds a LuaAlly (extends DirectableAlly, friendly/intelligent by
-- default) and registers it. The ally only appears in-game when a script
-- calls RPD.spawnAlly("test_ally", pos) — it is NOT part of the vanilla
-- spawn rotation (Level.createMob/MobSpawner untouched).
--
-- LuaAlly inherits the follow/defend/attack state machine from DirectableAlly,
-- so a Lua author only defines stats + optional callbacks:
--   * onCommand(selfId, cmd, targetId) — advisory feedback after Java dispatches
--     follow/defend/attack via RPD.commandAlly. Lua does NOT take over scheduling.
--   * attackProc(selfId, enemyId, baseDamage) — super runs first (champion/buff
--     chain), Lua receives the rolled damage and returns base unchanged here.
-- Lua never receives a Char object — only int char ids (M1 sandbox boundary).

register_ally {
    id = "test_ally",
    name = "测试 Lua 宠物",
    hp = 25,
    ht = 25,
    attack = 7,
    defense = 5,
    sprite = "rat",

    onCommand = function(selfId, cmd, targetId)
        -- Advisory only: a real mod would emit particles/yells here. Java has
        -- already applied the command; this callback must not change ally state.
        if cmd == "follow" then
            RPD.GLog("宠物跟随！")
        elseif cmd == "defend" then
            RPD.GLog("宠物守卫 " .. targetId)
        elseif cmd == "attack" then
            RPD.GLog("宠物攻击 " .. targetId)
        end
    end,

    attackProc = function(selfId, enemyId, baseDamage)
        -- Returning baseDamage is a no-op; a real mod could buff/debuff here.
        return baseDamage
    end,
}
