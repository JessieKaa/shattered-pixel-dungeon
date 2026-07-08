-- M10a port of remished Stinger.lua. ~75% chance to poison on attack
-- (source: if math.random() > 0.25 then affectBuff Poison). Poison is a
-- level-based buff, so the amount is the poison level.
register_mob {
    id = "stinger",
    name = "Stinger",
    hp = 16,
    ht = 16,
    attack = 8,
    defense = 3,
    sprite = "crab",

    attackProc = function(selfId, enemyId, baseDamage)
        if math.random() > 0.25 then
            RPD.affectBuff(enemyId, RPD.Buffs.Poison, 2)
        end
        return baseDamage
    end,
}
