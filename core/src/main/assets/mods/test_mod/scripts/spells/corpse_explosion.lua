-- M10a port of Remished CorpseExplosion. Original: castOnCell, explode a Carcass heap → toxic + mixed gases.
-- Degrade: no heap/Carcass API (needs M10b level/heap); implemented as: place ToxicGas at cell + ParalyticGas
-- on the same cell via placeBlob. corpseHealth / skill scaling dropped (fixed strength). MiasmaGas not in
-- SPD blob whitelist → dropped (ParalyticGas stands in).
-- Original: ../remixed-dungeon/scripts/spells/CorpseExplosion.lua
register_spell {
    id = "corpse_explosion",
    name = "尸体爆裂",
    desc = "在选中格释放毒气云(placeBlob ToxicGas + ParalyticGas;原版需尸体堆,降级为直接放气)。",
    image = 1,
    castTime = 2,
    useMode = "mana",
    spellCost = 9,
    targeting = "cell",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.placeBlob) then return end
        RPD.placeBlob("ToxicGas", cell, 20)
        RPD.placeBlob("ParalyticGas", cell, 6)
    end,
}
