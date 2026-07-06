-- M6d 代表 weapon item:Kunai。走 LuaItem(weapon wrapper),简化 tier-2 + onEquip 占位。
-- 原件: ../remished-dungeon/scripts/items/Kunai.lua
register_item {
    id = "kunai",
    name = "苦无",
    desc = "M6d weapon 代表:tier-2 占位,onEquip 记日志。",
    image = 0,
    tier = 2,

    onEquip = function(heroId)
        if RPD and RPD.GLog then
            RPD.GLog("kunai equipped by hero " .. tostring(heroId))
        end
    end,
}
