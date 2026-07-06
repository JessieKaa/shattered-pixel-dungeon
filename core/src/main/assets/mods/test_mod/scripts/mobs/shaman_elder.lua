register_mob {
    id = "shaman_elder",
    name = "Shaman Elder",
    hp = 28,
    ht = 28,
    attack = 10,
    defense = 8,
    sprite = "gnoll",

    act = function(selfId)
        local enemyId = RPD.enemyOf(selfId)
        if enemyId == nil then
            return false
        end
        local distance = RPD.cellDistance(RPD.charPos(selfId), RPD.charPos(enemyId))
        if distance == nil then
            return false
        end
        if distance < 2 then
            RPD.setMobAi(selfId, "fleeing")
        elseif distance > 4 then
            RPD.setMobAi(selfId, "hunting")
        end
        return false
    end,
}
