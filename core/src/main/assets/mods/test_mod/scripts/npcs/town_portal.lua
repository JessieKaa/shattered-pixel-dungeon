-- M4d town-portal NPC: a main-line NPC that ferries the hero into a SafeZone hub.
-- Spawned only in debug builds by LuaLevelService.injectLevelNpcs (depth==1, see
-- RegularLevel.createMobs hook). Interacting fires onInteract, which calls the
-- Java side via RPD.enterTown to swap the live level for the JSON-defined
-- test_safezone. Release builds never spawn this NPC (DeviceCompat.isDebug guard),
-- so vanilla progression is untouched.
--
-- Inherits LuaNpc (extends NPC): passive/invincible (RatKing-clone overrides),
-- so it is combat-safe even though it stands on a hostile floor. Lua only emits
-- a yell + delegates the scene switch to Java — it never receives a Char object,
-- only int char ids (M1 sandbox boundary, same as test_npc).

register_npc {
    id = "town_portal",
    name = "城镇传送门",
    sprite = "imp",

    onInteract = function(selfId, heroId)
        RPD.npcYell(selfId, "想去城镇看看吗?跟我来。")
        RPD.enterTown("test_safezone")
    end,
}
