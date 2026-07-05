-- M1 test item: a Lua-defined axe (tier 4, mirrors BattleAxe image 99).
-- Exists to prove the Lua item pool can hold more than one definition and
-- Generator.random(LUA_ITEM) can pick any of them.

register_item {
    id = "test_axe",
    name = "测试斧 (Lua)",
    desc = "M1 沙箱+双源验证物品:tier=4,攻防由 MeleeWeapon 公式推导。",
    image = 99,
    tier = 4,
}
