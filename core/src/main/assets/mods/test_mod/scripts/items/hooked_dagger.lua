-- M6d 代表 weapon item:HookedDagger。走 LuaItem(weapon wrapper),attackProc Bleeding。
-- 原件: ../remished-dungeon/scripts/items/HookedDagger.lua
register_item {
    id = "hooked_dagger",
    name = "钩刃匕首",
    desc = "M6d weapon 代表:命中给目标 Bleeding。attacker/defender 是 charId。",
    image = 0,
    spriteFile = "sprites/items/item_HookedDagger.png",
    tier = 1,

    attackProc = function(attacker, defender, baseDamage)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(defender, "Bleeding", 3)
        end
        return baseDamage
    end,
}
