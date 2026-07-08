-- remished_lite showcase mob. Registered-but-inert: DataDrivenLevel has no "lua_mob:" prefix
-- (mobs[] supports only rat_king / lua_npc: / lua_shop:), and the vanilla spawn pool never
-- reads LuaMobRegistry (Level.createMob / MobSpawner untouched). So this mob is registered
-- (registry coverage + C3 demonstration: registered yet never spawns in main game) but has
-- no placement path — it does NOT appear in the hub. The hub's living mob is the vanilla
-- rat_king placed via the level json. register_mob requires id/name; stats optional.
register_mob {
    id = "remished_lite_marauder",
    name = "Remished 掠夺者",
    hp = 20,
    ht = 20,
    attack = 6,
    defense = 3,
    sprite = "rat",
}
