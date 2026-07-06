-- M6d material item: Remished Plague Doctor 骨片碎片 (BoneShard)。
-- 走 LuaMaterial(plain Item)而非 weapon wrapper,见 rotten_organ.lua 头注。
-- 原件: ../remished-dungeon/scripts/items/BoneShard.lua
register_item {
    id = "bone_shard",
    type = "material",
    name = "骨片碎片",
    desc = "锋利的骨片碎片。",
    image = 5,
    price = 5,
    stackable = true,
}
