register_mob {
    id = "hydra",
    name = "Hydra",
    hp = 36,
    ht = 36,
    attack = 11,
    defense = 8,
    sprite = "slime",

    die = function(selfId)
        local selfPos = RPD.charPos(selfId)
        for _ = 1, 2 do
            local cell = RPD.emptyCellNextTo(selfPos)
            if cell ~= nil then
                RPD.spawnMob("hydra", cell)
            end
        end
    end,
}
