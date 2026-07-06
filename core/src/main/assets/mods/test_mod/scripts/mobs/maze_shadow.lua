register_mob {
    id = "maze_shadow",
    name = "Maze Shadow",
    hp = 22,
    ht = 22,
    attack = 9,
    defense = 7,
    sprite = "bat",

    act = function(selfId)
        if math.random() >= 0.3 then
            return false
        end
        local enemyId = RPD.enemyOf(selfId)
        if enemyId == nil then
            return false
        end
        local cell = RPD.emptyCellNextTo(RPD.charPos(enemyId))
        if cell ~= nil then
            RPD.blink(selfId, cell)
        end
        return false
    end,
}
