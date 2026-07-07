-- M6d 代表 spell:SummonBeast。spawnMobNear(注册的 Lua mob id, centerCell)。
-- 原件: ../remished-dungeon/scripts/spells/SummonBeast.lua(spawnMob + makePet)
-- 注:beast 用 test_mod 已注册的 Lua mob(test_mob);makePet/allied 化留给 M6e。
register_spell {
    id = "summon_beast",
    name = "召唤野兽",
    desc = "在附近召唤一只 Lua mob(spawnMobNear 占位,敌对;M6e 改 ally)。",
    image = 3,
    castTime = 1,
    spellCost = 20,
    targeting = "cell",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.spawnMobNear) then return end
        -- M7c: 在玩家选中的 cell 附近召唤(原来在施法者脚下)。
        RPD.spawnMobNear("test_mob", cell)
    end,
}
