-- M3a PoC test mob: a minimal Lua-defined hostile mob.
-- Demonstrates the register_mob pipeline: a Lua table is handed to Java,
-- which builds a LuaMob (extends Mob, hostile by default) and registers it.
-- The mob only appears in-game when a script calls RPD.spawnMob("test_mob", pos);
-- it is NOT part of the vanilla spawn rotation (Level.createMob/MobSpawner untouched).
--
-- AI callbacks (act/attackProc/defenseProc/die) are optional. This example:
--   * omits act  -> falls back to the upstream Mob AI (SLEEPING/HUNTING/...),
--                   i.e. a normal crab-like enemy that paths to the hero.
--   * attackProc -> super runs first (enchant/champion chain), Lua receives
--                   (selfId, enemyId, baseDamage) and returns base unchanged.
-- Lua never receives a Char object — only int char ids (M1 sandbox boundary).

register_mob {
    id = "test_mob",
    name = "测试 Lua 怪物",
    hp = 20,
    ht = 20,
    attack = 8,
    defense = 4,
    spriteFile = "sprites/mobs/mob_TestMob.png",

    attackProc = function(selfId, enemyId, baseDamage)
        -- Returning baseDamage is a no-op; a real mod could buff/debuff here.
        return baseDamage
    end,
}
