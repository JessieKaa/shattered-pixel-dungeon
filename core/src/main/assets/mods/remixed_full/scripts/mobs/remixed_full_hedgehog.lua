-- M16b alpha mob: Hedgehog. Spiny critter that reflects a bit of damage.
register_mob {
    id = "remixed_full_hedgehog",
    name = "刺猬",
    hp = 8,
    ht = 8,
    attack = 3,
    defense = 3,
    sprite = "rat",
    spriteFile = "mods/remixed_full/sprites/mobs/mob_Hedgehog.png",
    maxLvl = 5,

    defenseProc = function(selfId, enemyId, baseDamage)
        if RPD and RPD.damageChar then
            RPD.damageChar(enemyId, 1)
        end
        return baseDamage
    end,
}
