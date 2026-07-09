-- M16b alpha mob: Cold Spirit. Floating frost wisp, slightly deeper floors.
register_mob {
    id = "remixed_full_cold_spirit",
    name = "寒霜精灵",
    hp = 18,
    ht = 18,
    attack = 7,
    defense = 4,
    sprite = "bat",
    spriteFile = "mods/remixed_full/sprites/mobs/mob_ColdSpirit.png",
    maxLvl = 10,

    attackProc = function(selfId, enemyId, baseDamage)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(enemyId, "Frost", 3)
        end
        return baseDamage
    end,
}
