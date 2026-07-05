-- M0 PoC test item: a minimal Lua-defined weapon.
-- tier=2 mirrors a shortsword (image 104 == ItemSpriteSheet.SHORTSWORD),
-- so min/max/STRReq come out the same as the vanilla shortsword via
-- MeleeWeapon's default formulas. The only point of this item is to prove
-- the Lua -> Java -> in-game pipeline works end to end.

register_item {
    id = "test_sword",
    name = "测试剑 (Lua)",
    desc = "一把由 Lua 脚本定义的剑。这是 SPD Lua modding 平台的 M0 可行性验证物品 —— 攻击力/力量需求由 tier=2 推导，与原版短剑相同。",
    image = 104,
    tier = 2,
}
