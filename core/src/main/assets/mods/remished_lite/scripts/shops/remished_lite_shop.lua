-- remished_lite showcase shop: a LuaShopNpc (extends LuaNpc — passive/invincible). interact
-- opens a WndOptions purchase window driven by this items pool; selecting an item calls
-- Java-side attemptBuy (gold check + deduct + hand item). Lua defines data only.
-- Each items[] entry is { id=..., price=..., quantity=... }:
--   omitted/<0 = infinite, 0 = sold out, >0 = finite (decrements per buy).
-- id must be in the LuaShopItems whitelist (consumables: potions/scrolls/food); unknown ids
-- are skipped at hydrate time. Referenced by the hub level's mobs[] as "lua_shop:remished_lite_shop".
--
-- Pricing (M14b, vanilla-baselined): showcase price = round(2 × vanilla item.value()).
--   potion_of_healing value 30 → 60, scroll_of_identify value 30 → 60, small_ration value
--   10 → 20, berry value 5 → 10. Vanilla shopkeepers charge value × 5 × (depth/5+1)
--   (Shopkeeper.sellPrice, depth-scaled) — far pricier than this flat 2× showcase rate, so
--   the hub is welcoming but not free. A single uniform 2× multiplier also fixes the prior
--   inconsistency where two value-30 consumables (potion/scroll) were priced differently.
-- Every entry carries a finite quantity (LuaShopNpc: omitted = infinite), bounding stock
-- per shop instance. Note: the hub is an ephemeral DataDrivenLevel and LuaShopNpc does NOT
-- bundle runtime stock, so a fresh visit re-reads these quantities — i.e. stock is finite
-- *per visit*, not across hub re-entry. That is acceptable for a showcase (items still cost
-- gold at 2× value; it is a demo shop, not a farm gate). Cross-visit stock persistence would
-- require a Java-side change to LuaShopNpc, intentionally out of scope here.
register_shop {
    id = "remished_lite_shop",
    name = "展示商店",
    sprite = "shopkeeper",

    items = {
        { id = "potion_of_healing",  price = 60, quantity = 2 },
        { id = "small_ration",       price = 20, quantity = 3 },
        { id = "scroll_of_identify", price = 60, quantity = 3 },
        { id = "berry",              price = 10, quantity = 4 },
    },
}
