-- M6d 代表 spell:RaiseDead。spawnMobNear + 占位 skeleton(用 test_mob;Remished 用 skeleton 资产)。
-- 原件: ../remished-dungeon/scripts/spells/RaiseDead.lua
register_spell {
    id = "raise_dead",
    name = "亡灵复生",
    desc = "在附近复活一具亡灵(spawnMobNear 占位;Remished skeleton 资产 M6e)。",
    image = 1,
    castTime = 1,
    spellCost = 10,
    targeting = "self",

    onUse = function(heroId)
        if not (RPD and RPD.charPos and RPD.spawnMobNear) then return end
        local pos = RPD.charPos(heroId)
        RPD.spawnMobNear("test_mob", pos)
    end,
}
