-- M19e: Bone Shard. Stackable alchemy material, ported from remixed BoneShard.lua.
-- remixed 源是纯 desc 表,零 callback,直接映射为 fork 声明式 material。
register_item {
    id = "remixed_full_bone_shard",
    type = "material",
    name = "骨片",
    desc = "锋利的骨头碎片,炼金术士常用的基础材料。",
    image = 5,
    price = 5,
    stackable = true,
    spriteFile = "sprites/items/item_BoneShard.png",
}
