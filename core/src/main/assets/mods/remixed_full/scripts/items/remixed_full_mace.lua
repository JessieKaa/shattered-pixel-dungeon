-- M16b alpha weapon: Mace. Blunt weapon with a chance to daze the target.
register_item {
    id = "remixed_full_mace",
    name = "钉头锤",
    desc = "沉重的钝器,命中时可能让敌人短暂眩晕。",
    image = 15,
    tier = 2,
    spriteFile = "mods/remixed_full/sprites/items/item_Mace.png",

    attackProc = function(attacker, defender, baseDamage)
        if RPD and RPD.affectBuff and math.random() <= 0.20 then
            RPD.affectBuff(defender, "Vertigo", 2)
        end
        return baseDamage
    end,
}
