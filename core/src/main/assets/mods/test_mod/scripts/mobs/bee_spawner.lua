-- M10a port of remished BeeSpawner.lua. A passive hive that releases a swarm
-- of small mobs when it dies and one each time it is struck.
-- Degradations:
--   * no vanilla "Bee" is registered in LuaMobRegistry, so the swarm spawns
--     the existing small mob "test_mob" as a proxy (a dedicated bee.lua would
--     be a separate addition).
--   * LuaMob has no `damage` callback, so the on-hit spawn rides defenseProc.
-- Like the source, the swarm size is rolled once at script load and shared
-- across both die/defenseProc (decremented per successful spawn).
local remaining = math.random(3, 8)

local function spawnOne(selfPos)
    if remaining <= 0 then return end
    if RPD.spawnMobNear("test_mob", selfPos) ~= nil then
        remaining = remaining - 1
    end
end

register_mob {
    id = "bee_spawner",
    name = "Bee Spawner",
    hp = 30,
    ht = 30,
    attack = 6,
    defense = 4,
    sprite = "bat",

    spawn = function(selfId)
        RPD.setMobAi(selfId, "passive")
    end,

    die = function(selfId)
        local selfPos = RPD.charPos(selfId)
        local n = remaining
        for _ = 1, n do
            spawnOne(selfPos)
        end
    end,

    defenseProc = function(selfId, enemyId, baseDamage)
        spawnOne(RPD.charPos(selfId))
        return baseDamage
    end,
}
