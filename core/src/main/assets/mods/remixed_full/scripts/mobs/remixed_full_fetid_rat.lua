-- M16b alpha mob: Fetid Rat. Diseased rat that poisons on bite.
register_mob {
    id = "remixed_full_fetid_rat",
    name = "腐臭老鼠",
    hp = 14,
    ht = 14,
    attack = 6,
    defense = 2,
    sprite = "rat",
    spriteFile = "mods/remixed_full/sprites/mobs/mob_FetidRat.png",
    maxLvl = 10,

    attackProc = function(selfId, enemyId, baseDamage)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(enemyId, "Poison", 3)
        end
        return baseDamage
    end,
}
