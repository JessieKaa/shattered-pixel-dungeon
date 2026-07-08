-- M10a port of remished Mirror.lua (mirror image). Reflects incoming damage
-- back onto the attacker (source: damage(src, dmg) -> src:damage(dmg, src)).
-- Degradation: LuaMob has no `damage` callback, so reflection rides
-- `defenseProc` (fires when this mob is struck): it routes the hit through
-- RPD.damageChar onto the attacker, then returns baseDamage unchanged so the
-- mob still takes the original damage.
register_mob {
    id = "mirror",
    name = "Mirror",
    hp = 20,
    ht = 20,
    attack = 8,
    defense = 6,
    sprite = "brute",

    defenseProc = function(selfId, enemyId, baseDamage)
        RPD.damageChar(enemyId, baseDamage)
        return baseDamage
    end,
}
