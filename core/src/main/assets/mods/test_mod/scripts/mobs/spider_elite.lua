register_mob {
    id = "spider_elite",
    name = "Spider Elite",
    hp = 24,
    ht = 24,
    attack = 9,
    defense = 6,
    sprite = "crab",

    attackProc = function(selfId, enemyId, baseDamage)
        if math.random() < 0.2 then
            RPD.affectBuff(enemyId, RPD.Buffs.Vertigo, 5)
        end
        return baseDamage
    end,
}
