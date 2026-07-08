-- M10a port of remished Assasin.lua. Stealth assassin: invisible until its
-- first strike breaks cover.
-- Degradation: RPD.permanentBuff rejects Java-whitelist buffs (Invisibility
-- is whitelisted), so spawn grants a long 1000-turn Invisibility rather than a
-- true permanent buff; the first attackProc detaches it.
register_mob {
    id = "assasin",
    name = "Assasin",
    hp = 25,
    ht = 25,
    attack = 12,
    defense = 4,
    sprite = "gnoll",

    spawn = function(selfId)
        RPD.affectBuff(selfId, RPD.Buffs.Invisibility, 1000)
    end,

    attackProc = function(selfId, enemyId, baseDamage)
        RPD.removeBuff(selfId, RPD.Buffs.Invisibility)
        return baseDamage
    end,
}
