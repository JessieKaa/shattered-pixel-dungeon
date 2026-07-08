-- M10a port of remished Hero.lua. The source damage hook body is fully
-- commented out (no-op), so this is a pure default mob. The registry id "hero"
-- is scoped to LuaMobRegistry and does not collide with the player Hero class;
-- the display name is qualified to keep GLog lines unambiguous.
register_mob {
    id = "hero",
    name = "Remished Hero",
    hp = 35,
    ht = 35,
    attack = 10,
    defense = 8,
    sprite = "brute",
}
