-- M16b alpha weapon: Lantern Blade. Glimmers with a warm light; burns target on hit.
register_item {
    id = "remixed_full_lantern_blade",
    name = "提灯之刃",
    desc = "剑身散发着微弱火光的短剑,命中时有几率点燃敌人。",
    image = 22,
    tier = 2,
    spriteFile = "mods/remixed_full/sprites/items/item_Sword.png",

    attackProc = function(attacker, defender, baseDamage)
        if RPD and RPD.affectBuff and math.random() <= 0.33 then
            RPD.affectBuff(defender, "Burning", 4)
        end
        return baseDamage
    end,
}
