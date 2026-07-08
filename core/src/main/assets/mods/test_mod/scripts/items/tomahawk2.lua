-- M10a weapon item: Remished Tomahawk2(投掷斧)。走 LuaItem(weapon wrapper)。
-- 降级(M10c/无 API):原版 equipable="left_hand" 双持投掷槽 + stackable 投掷语义,未接(当前为普通单手 weapon)。
-- 原件: ../remished-dungeon/scripts/items/Tomahawk2.lua
register_item {
    id = "tomahawk2",
    name = "战斧",
    desc = "gnoll 风格的投掷战斧。原版可左手双持投掷(降级:left_hand 投掷槽 未接)。",
    image = 2,
    tier = 2,
}
