-- M6d material item: Remished Plague Doctor 毒腺 (ToxicGland)。
-- 走 LuaMaterial(plain Item)而非 weapon wrapper,见 rotten_organ.lua 头注。
-- 原件: ../remished-dungeon/scripts/items/ToxicGland.lua
register_item {
    id = "toxic_gland",
    type = "material",
    name = "毒腺",
    desc = "含有毒素的生物腺体。",
    image = 3,
    price = 5,
    stackable = true,
}
