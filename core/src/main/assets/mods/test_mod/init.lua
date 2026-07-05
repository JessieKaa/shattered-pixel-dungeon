-- test_mod entry script (M5b). Loaded by LuaEngine.loadModEntryScripts only when test_mod is
-- enabled (default_enabled=false, so off by default). Registers a single demonstration item with
-- a NEW id (test_mod_item) that does not collide with the flat-loaded scripts/items/test_sword.lua,
-- so the existing 208 tests are unaffected. Toggle test_mod in the Mods UI (debug builds) and
-- restart to see this item registered (LuaModEntryTest verifies the data layer).
register_item {
    id = "test_mod_item",
    name = "Entry 示范剑 (Lua)",
    desc = "由 test_mod 的 entry 脚本(init.lua)注册,证明 M5b mod 开关 + entry 加载机制工作。开启 test_mod 并重启后注册,关闭则跳过。",
    image = 106,
    tier = 2,
}
