-- M4c PoC test shop: a minimal Lua-defined shop NPC.
-- Demonstrates the register_shop pipeline: a Lua table with an items pool is
-- handed to Java, which builds a LuaShopNpc (extends LuaNpc — passive/invincible
-- by inheritance) and registers it. The shop only appears in-game when a
-- DataDrivenLevel mob spec references it as "lua_shop:test_shop" — it is NOT
-- part of the vanilla spawn rotation (Level.createMob/MobSpawner untouched).
--
-- interact opens a WndOptions purchase window driven entirely by this items
-- pool; selecting an item calls Java-side attemptBuy (gold check + deduct +
-- hand item to hero). No Lua callback fires on purchase — Lua defines data only.
--
-- items[]: each entry is { id=..., price=..., quantity=... }.
--   quantity tri-state (PLAN §决策4):
--     omitted / <0 = infinite stock  (never decrements)
--     0            = sold out        (UI disabled, attemptBuy refuses)
--     >0           = finite          (decrements per buy, 0 becomes sold out)
--   id must be in the LuaShopItems whitelist (consumables: potions/scrolls/food);
--   unknown ids are skipped at hydrate time (logged, never crashes interact).
-- Lua never receives a Char object — only int char ids (M1 sandbox boundary).

register_shop {
    id = "test_shop",
    name = "测试 Lua 商店",
    sprite = "shopkeeper",

    items = {
        { id = "potion_of_healing",  price = 50, quantity = 2 },   -- finite: 2 in stock
        { id = "small_ration",       price = 15 },                  -- infinite (quantity omitted)
        { id = "scroll_of_identify", price = 30, quantity = -1 },   -- explicit infinite
        { id = "berry",              price = 10, quantity = 0 }     -- sold out (UI disabled)
    },
}
