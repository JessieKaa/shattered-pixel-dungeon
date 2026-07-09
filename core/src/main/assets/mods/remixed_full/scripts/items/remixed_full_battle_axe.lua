-- M16b alpha weapon: Battle Axe. Slow but heavy hitting; small damage bump.
register_item {
    id = "remixed_full_battle_axe",
    name = "战斧",
    desc = "沉重的单手斧,威力十足。",
    image = 8,
    tier = 3,
    spriteFile = "mods/remixed_full/sprites/items/item_BattleAxe.png",

    attackProc = function(attacker, defender, baseDamage)
        return baseDamage + 1
    end,
}
