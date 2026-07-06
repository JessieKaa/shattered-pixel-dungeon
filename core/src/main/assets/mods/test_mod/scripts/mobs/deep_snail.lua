register_mob {
    id = "deep_snail",
    name = "Deep Snail",
    hp = 40,
    ht = 40,
    attack = 7,
    defense = 12,
    sprite = "slime",

    defenseProc = function(selfId, enemyId, baseDamage)
        if math.random() < 0.2 then
            local distance = RPD.cellDistance(RPD.charPos(selfId), RPD.charPos(enemyId))
            if distance == 1 then
                RPD.affectBuff(enemyId, RPD.Buffs.Ooze, 5)
            end
        end
        return baseDamage
    end,
}
