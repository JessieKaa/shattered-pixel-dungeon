package com.shatteredpixel.shatteredpixeldungeon.modding;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Berry;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.food.SmallRation;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfMagicMapping;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRemoveCurse;

/**
 * Whitelist that maps a Lua-facing {@code item id} string to a fresh {@link Item}
 * instance. This is the M4c MVP answer to {@code PLAN §R2}: a small, hand-curated
 * catalogue of common consumables a SafeZone shop would plausibly sell. Anything
 * outside the catalogue is rejected (logged + skipped) rather than instantiated
 * via reflection — a Lua author cannot smuggle an arbitrary class name through
 * the shop pipeline, and an unknown id never crashes {@code interact}.
 *
 * <p>Each {@link #create(String)} call returns a brand-new {@link Item}; the
 * caller ({@link LuaShopNpc#attemptBuy}) hands it to {@code Item.doPickUp} so it
 * stacks/pickups through the normal hero-backpack path.
 */
final class LuaShopItems {

    private static final String TAG = "LuaShopItems";

    private LuaShopItems() { }

    /**
     * @return a fresh {@link Item} for the id, or {@code null} (logged) if the id
     *         is not in the whitelist. Returning null lets the caller degrade
     *         gracefully (skip the entry / refuse the buy) instead of crashing.
     */
    static Item create(String id) {
        if (id == null) {
            return null;
        }
        switch (id) {
            case "potion_of_healing":     return new PotionOfHealing();
            case "potion_of_strength":    return new PotionOfStrength();
            case "scroll_of_identify":    return new ScrollOfIdentify();
            case "scroll_of_magic_mapping": return new ScrollOfMagicMapping();
            case "scroll_of_remove_curse":  return new ScrollOfRemoveCurse();
            case "small_ration":          return new SmallRation();
            case "ration":                return new Food();
            case "berry":                 return new Berry();
            default:
                Gdx.app.error(TAG, "unknown shop item id: " + id + " — not in whitelist");
                return null;
        }
    }
}
