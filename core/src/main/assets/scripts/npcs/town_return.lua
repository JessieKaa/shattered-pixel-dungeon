-- M4d town-return NPC: the outbound leg inside the SafeZone hub. Spawned by the
-- test_safezone.json mob spec (lua_npc:town_return). Interacting fires onInteract,
-- which calls RPD.leaveTown to swap back to the main-line level while preserving
-- the hero's in-memory state (purchases, gold deductions) — see LuaLevelService.
-- leaveLevel (Option C synchronous path). Like the portal NPC it is passive and
-- invincible (LuaNpc base), so it never becomes a combat target inside the hub.

register_npc {
    id = "town_return",
    name = "返回传送门",
    sprite = "imp",

    onInteract = function(selfId, heroId)
        RPD.npcYell(selfId, "回主线去吧。")
        RPD.leaveTown()
    end,
}
