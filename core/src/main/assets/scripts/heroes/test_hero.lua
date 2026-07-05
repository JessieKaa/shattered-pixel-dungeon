-- M3c demo Lua hero class. Registered via register_hero{...} (LuaEngine.loadHeroScripts
-- enumerates scripts/heroes/*.lua on game start). Selected from the HeroSelectScene
-- extra-button row; Dungeon.init routes to Hero.initLuaHero because LuaHeroService
-- holds a pending id.
--
-- talentSource = WARRIOR: this Lua hero reuses the Warrior talent tree (D2 —
-- Talent.initClassTalents(host, hero.talents), Talent.java unchanged). hero.heroClass
-- is set to WARRIOR so all existing switch code keeps working; the lua_class_id
-- sidecar is what marks this save as "actually Lua hero test_hero" (D3).
--
-- hp/defenseSkill override the Hero defaults (20/5). startingItems lists LuaItem ids
-- (registered via scripts/items/*.lua) that Hero.initLuaHero collects into the backpack.

register_hero{
    id = 'test_hero',
    name = '测试 Lua 职业',
    talentSource = 'WARRIOR',
    hp = 25,
    defenseSkill = 4,
    startingItems = { 'test_sword' }
}
