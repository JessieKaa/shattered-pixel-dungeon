-- M4b PoC test NPC: a minimal Lua-defined interactive NPC.
-- Demonstrates the register_npc pipeline: a Lua table is handed to Java,
-- which builds a LuaNpc (extends NPC, passive/invincible by default) and
-- registers it. The NPC only appears in-game when a DataDrivenLevel mob spec
-- references it as "lua_npc:test_npc" — it is NOT part of the vanilla spawn
-- rotation (Level.createMob/MobSpawner untouched).
--
-- LuaNpc clones RatKing's invincibility overrides (defenseSkill/damage/chooseEnemy/
-- add(Buff)/reset) and inherits NPC's PASSIVE state, so a Lua author only defines
-- display fields + the interact callback:
--   * onInteract(selfId, heroId) — fires when Dungeon.hero bumps this NPC. Use
--     RPD.npcYell(selfId, text) to emit a GLog line in the NPC's voice, and/or
--     RPD.showDialog(selfId, text) to open a WndMessage popup.
-- Lua never receives a Char object — only int char ids (M1 sandbox boundary).

register_npc {
    id = "test_npc",
    name = "测试 Lua NPC",
    sprite = "rat_king",

    onInteract = function(selfId, heroId)
        -- A real mod would branch on quest state / inventory here. MVP just
        -- emits a yell + opens a dialog so the SafeZone has visible content.
        RPD.npcYell(selfId, "你好,冒险者!我是 Lua 定义的 NPC。")
        RPD.showDialog(selfId, "Welcome to the SafeZone.\nThis dialog is driven entirely by Lua.")
    end,
}
