-- M16b alpha weapon: Hooked Dagger. Light blade that inflicts Bleeding on hit.
register_item {
    id = "remixed_full_hooked_dagger",
    name = "钩刃匕首",
    desc = "一把带有倒钩的短匕首,命中时会让目标流血。",
    image = 0,
    tier = 1,
    spriteFile = "mods/remixed_full/sprites/items/item_HookedDagger.png",

    attackProc = function(attacker, defender, baseDamage)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(defender, "Bleeding", 3)
        end
        return baseDamage
    end,
}
