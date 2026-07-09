-- M16b alpha weapon: Kunai. Light thrown blade coated with poison.
register_item {
    id = "remixed_full_kunai",
    name = "苦无",
    desc = "涂有毒药的短刃,命中时会让目标中毒。",
    image = 13,
    tier = 1,
    spriteFile = "mods/remixed_full/sprites/items/item_Kunai.png",

    attackProc = function(attacker, defender, baseDamage)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(defender, "Poison", 3)
        end
        return baseDamage
    end,
}
