-- M16b alpha mob: Bandit. Sneaky gnoll that causes a little extra pain on hit.
register_mob {
    id = "remixed_full_bandit",
    name = "强盗",
    hp = 16,
    ht = 16,
    attack = 6,
    defense = 3,
    sprite = "gnoll",
    spriteFile = "mods/remixed_full/sprites/mobs/mob_Bandit.png",
    maxLvl = 10,

    attackProc = function(selfId, enemyId, baseDamage)
        if RPD and RPD.damageChar then
            RPD.damageChar(enemyId, 2)
        end
        return baseDamage
    end,
}
