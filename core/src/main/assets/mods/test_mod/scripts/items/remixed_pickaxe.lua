-- M10a weapon item: Remished RemixedPickaxe(任务鹤嘴锄)。走 LuaItem(weapon wrapper)。
-- 降级(M10c/无 API):挖矿 action(execute/actions ACMine + terrain WALL_DECO→WALL 改写 + DarkGold/MysteryMeat 掉落)
--   需 item action 派发 + terrain API,未接;attackProc 的 Bat-击杀血染 self.data.bloodStained 状态
--   需 per-instance Lua 字段(luajava 无),未接;glowing 血染发光未接;STR 检查 say 未接。
-- 原件: ../remished-dungeon/scripts/items/RemixedPickaxe.lua
register_item {
    id = "remixed_pickaxe",
    name = "鹤嘴锄",
    desc = "矿工的鹤嘴锄。原版可挖掘特殊墙壁、击杀蝙蝠会血染(降级:挖矿 action / bloodStained 状态 / glowing 未接)。",
    image = 75,
    tier = 3,
}
