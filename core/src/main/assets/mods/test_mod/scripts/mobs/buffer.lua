local buffs = {
    RPD.Buffs.Vertigo,
    RPD.Buffs.Roots,
    RPD.Buffs.Paralysis,
    RPD.Buffs.Cripple,
    RPD.Buffs.Slow,
    RPD.Buffs.Poison,
    RPD.Buffs.Ooze,
}

register_mob {
    id = "buffer",
    name = "Buffer",
    hp = 26,
    ht = 26,
    attack = 8,
    defense = 6,
    sprite = "gnoll",

    attackProc = function(selfId, enemyId, baseDamage)
        local buff = buffs[math.random(1, #buffs)]
        if buff ~= nil then
            RPD.affectBuff(enemyId, buff, 5)
        end
        return baseDamage
    end,
}
