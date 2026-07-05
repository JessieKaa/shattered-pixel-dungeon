package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.CurrencyIndicator;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.List;

/**
 * A shop NPC driven by a Lua-defined item pool (M4c). Extends {@link LuaNpc} so it
 * inherits the RatKing invincibility overrides (SafeZone-safe) and the persistence
 * pattern, then layers a custom purchase window on top of {@code interact}.
 *
 * <h3>Definition vs. runtime state</h3>
 *
 * <p>The Lua table (registered via {@code register_shop}) is the source of truth
 * for the shop {@code name}/{@code sprite} and the item pool ({@code items[]}). It
 * is re-applied on every {@link #hydrate(LuaTable)} call. Runtime stock counts
 * ({@link ShopEntry#quantity}) live in {@link #entries} and are <b>not</b> bundled —
 * SafeZone is ephemeral ({@link DataDrivenLevel#isEphemeral()}), so stock resets
 * on every visit. Bundling only {@code lua_shop_id} is sufficient (and matches the
 * {@link LuaNpc}/{@link LuaMob} pattern).
 *
 * <h3>Quantity tri-state (codex round-1 must-fix)</h3>
 *
 * <ul>
 *   <li>{@code quantity} omitted or {@code < 0} → <b>infinite</b> stock ({@link #INFINITE_STOCK}).</li>
 *   <li>{@code quantity == 0} → <b>sold out</b> (UI disabled, {@link #attemptBuy} refuses).</li>
 *   <li>{@code quantity > 0} → <b>finite</b>; each buy decrements, 0 becomes sold out.</li>
 * </ul>
 *
 * <h3>interact routing</h3>
 *
 * <p>Overrides {@link LuaNpc#interact} completely: it does <b>not</b> fire
 * {@code onInteract} (the dialog path) — instead it opens a purchase window, like
 * {@link com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Shopkeeper#interact}.
 * The hero-only guard still applies: a non-hero Char bumping a shop does nothing.
 *
 * <h3>attemptBuy (pure-logic seam)</h3>
 *
 * <p>{@link #attemptBuy(int)} is the testable core: it validates stock + gold,
 * deducts {@link Dungeon#gold}, decrements finite stock, and hands the item to the
 * hero via {@link Item#doPickUp} when {@link Dungeon#hero} is non-null. Headless
 * tests run it with {@code Dungeon.hero == null} (so the {@code doPickUp} branch is
 * skipped) and assert on gold + stock + item instantiation — the live pickup is
 * verified by the desktop run, same split as {@code LuaNpcTest} (PLAN §9).
 */
public class LuaShopNpc extends LuaNpc {

    private static final String TAG = "LuaShopNpc";
    private static final String LUA_SHOP_ID = "lua_shop_id";
    /** Sentinel for infinite stock — also the hydrate default when Lua omits {@code quantity}. */
    private static final int INFINITE_STOCK = -1;

    private String luaShopId;
    private String shopName = "???";
    private final List<ShopEntry> entries = new ArrayList<>();

    /**
     * Required for {@code Reflection.newInstance} during Bundle restore. Sets a
     * defensive default {@code spriteClass} — if the registry is missing on
     * restore (degraded path), {@link #hydrate(LuaTable)} never runs and
     * spriteClass would otherwise stay null, crashing {@code Mob.sprite()}'s
     * {@code Reflection.newInstance}. {@code ShopkeeperSprite} matches the shop
     * theme; a successful re-hydrate overwrites this with the Lua value.
     */
    public LuaShopNpc() {
        super();
        spriteClass = resolveSprite("shopkeeper");
    }

    /**
     * Calls {@code super()} (the no-arg {@link LuaNpc#LuaNpc()}) on purpose — this
     * skips {@link LuaNpc#hydrate(LuaTable)} so the inherited {@code luaNpcId} stays
     * null (we never want a shop NPC to also look like a dialog NPC in the bundle).
     * Then we run our own {@link #hydrate(LuaTable)}.
     */
    public LuaShopNpc(LuaTable tbl) {
        super();
        hydrate(tbl);
    }

    private void hydrate(LuaTable tbl) {
        luaShopId = tbl.get("id").checkjstring();
        shopName = tbl.get("name").checkjstring();
        spriteClass = resolveSprite(tbl.get("sprite").optjstring("shopkeeper"));
        entries.clear();
        LuaValue itemsVal = tbl.get("items");
        if (itemsVal.istable()) {
            // 1-indexed traversal (not keys()) so the on-screen order matches the
            // Lua array order — WndOptions builds its button list from `entries`.
            LuaTable itemsTbl = itemsVal.checktable();
            int n = itemsTbl.len().toint();
            for (int i = 1; i <= n; i++) {
                LuaValue v = itemsTbl.get(i);
                if (!v.istable()) continue;
                String itemId = v.get("id").optjstring(null);
                if (itemId == null) {
                    Gdx.app.error(TAG, "shop '" + luaShopId + "' has an item without a string id — skipping");
                    continue;
                }
                if (!v.get("price").isint()) {
                    Gdx.app.error(TAG, "shop '" + luaShopId + "' item '" + itemId + "' missing/invalid price — skipping");
                    continue;
                }
                int price = v.get("price").toint();
                if (price < 0) {
                    // Negative price would otherwise silently become a free item
                    // (codex phase-2 must-fix) — reject it outright instead.
                    Gdx.app.error(TAG, "shop '" + luaShopId + "' item '" + itemId + "' negative price " + price + " — skipping");
                    continue;
                }
                int quantity = v.get("quantity").isint() ? v.get("quantity").toint() : INFINITE_STOCK;
                entries.add(new ShopEntry(itemId, price, quantity));
            }
        }
    }

    // ---- interact: open the purchase window ----

    @Override
    public boolean interact(Char c) {
        sprite.turnTo(pos, c.pos);
        if (c != Dungeon.hero) {
            return true;
        }
        Game.runOnRenderThread(new Callback() {
            @Override
            public void call() {
                showShopWindow();
            }
        });
        return true;
    }

    private void showShopWindow() {
        String[] opts = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            ShopEntry e = entries.get(i);
            Item sample = LuaShopItems.create(e.itemId);
            String displayName = sample != null ? sample.title() : e.itemId;
            String suffix;
            if (e.quantity == 0) {
                suffix = "  [售罄]";
            } else if (e.quantity > 0) {
                suffix = "  (剩余 " + e.quantity + ")";
            } else {
                suffix = "";
            }
            opts[i] = displayName + "  —  " + e.price + " 金币" + suffix;
        }
        CurrencyIndicator.showGold = true;
        GameScene.show(new WndOptions(sprite(), shopName,
                "欢迎光临!(持有 " + Dungeon.gold + " 金币)", opts) {
            @Override
            protected void onSelect(int index) {
                super.onSelect(index);
                attemptBuy(index);
            }

            @Override
            protected boolean enabled(int index) {
                if (index < 0 || index >= entries.size()) {
                    return false;
                }
                ShopEntry e = entries.get(index);
                if (e.quantity == 0) {
                    return false;
                }
                return Dungeon.gold >= e.price;
            }

            @Override
            public void hide() {
                super.hide();
                CurrencyIndicator.showGold = false;
            }
        });
    }

    /**
     * Apply a purchase at {@code index}. Returns true on success. Pure enough for
     * the headless harness: gold deduction + stock decrement + item instantiation
     * are all asserted there; the {@code doPickUp} branch is skipped when
     * {@link Dungeon#hero} is null (headless) and verified by the desktop run.
     */
    boolean attemptBuy(int index) {
        if (index < 0 || index >= entries.size()) {
            return false;
        }
        ShopEntry e = entries.get(index);
        if (e.quantity == 0) {
            GLog.w("这件物品已售罄。");
            return false;
        }
        if (Dungeon.gold < e.price) {
            GLog.w("金币不足。");
            return false;
        }
        Item item = LuaShopItems.create(e.itemId);
        if (item == null) {
            GLog.w("未知物品:" + e.itemId);
            return false;
        }
        Dungeon.gold -= e.price;
        if (e.quantity > 0) {
            e.quantity -= 1;
        }
        Hero hero = Dungeon.hero;
        if (hero != null) {
            if (!item.doPickUp(hero)) {
                Dungeon.level.drop(item, hero.pos);
            }
        }
        GLog.i("购买了 " + item.title() + "。");
        return true;
    }

    // ---- accessors (production + tests) ----

    @Override
    public String name() {
        return shopName;
    }

    @Override
    public String description() {
        return shopName;
    }

    /** Number of item entries in the pool (test-visible). */
    int entryCount() {
        return entries.size();
    }

    /** Test-visible accessor for a pool entry. */
    ShopEntry entry(int index) {
        return entries.get(index);
    }

    /** Test-visible id accessor. */
    String luaShopId() {
        return luaShopId;
    }

    // ---- persistence (D4) ----

    @Override
    public void storeInBundle(Bundle bundle) {
        // super.storeInBundle = LuaNpc.storeInBundle: writes the NPC/Char chain, then
        // conditionally writes lua_npc_id when luaNpcId != null. We never set luaNpcId,
        // so the bundle carries only our own lua_shop_id (no dialog-NPC marker leaks).
        super.storeInBundle(bundle);
        if (luaShopId != null) {
            bundle.put(LUA_SHOP_ID, luaShopId);
        }
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        // Same reasoning: super.restoreFromBundle reads the NPC chain, then peeks at
        // lua_npc_id (absent here, so it skips LuaNpc.hydrate). We re-hydrate from the
        // shop registry instead.
        super.restoreFromBundle(bundle);
        if (bundle.contains(LUA_SHOP_ID)) {
            luaShopId = bundle.getString(LUA_SHOP_ID);
            LuaTable tbl = LuaShopRegistry.getTable(luaShopId);
            if (tbl != null) {
                hydrate(tbl);
            } else {
                // Engine init failed or script removed — degrade gracefully.
                shopName = "??? (" + luaShopId + ")";
                entries.clear();
            }
        }
    }

    // ---- inner ----

    /** One shop entry. {@code quantity} is mutable for stock decrement. */
    static final class ShopEntry {
        final String itemId;
        final int price;
        int quantity;

        ShopEntry(String itemId, int price, int quantity) {
            this.itemId = itemId;
            this.price = price;
            this.quantity = quantity;
        }
    }
}
