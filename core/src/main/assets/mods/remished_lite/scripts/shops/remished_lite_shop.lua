-- remished_lite showcase shop: a LuaShopNpc (extends LuaNpc — passive/invincible). interact
-- opens a WndOptions purchase window driven by this items pool; selecting an item calls
-- Java-side attemptBuy (gold check + deduct + hand item). Lua defines data only.
-- Each items[] entry is { id=..., price=..., quantity=... }:
--   omitted/<0 = infinite, 0 = sold out, >0 = finite (decrements per buy).
-- id must be in the LuaShopItems whitelist (consumables: potions/scrolls/food); unknown ids
-- are skipped at hydrate time. Referenced by the hub level's mobs[] as "lua_shop:remished_lite_shop".
register_shop {
    id = "remished_lite_shop",
    name = "展示商店",
    sprite = "shopkeeper",

    items = {
        { id = "potion_of_healing",  price = 50, quantity = 2 },
        { id = "small_ration",       price = 15 },
        { id = "scroll_of_identify", price = 30, quantity = 3 },
        { id = "berry",              price = 10 },
    },
}
