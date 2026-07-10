-- M20g: Soul Shard. Pure declarative stackable material, original remixed-style content.
-- 纯声明式 material,沿用 bone_shard/vile_essence 范式
-- (test m19e_items_declareExpectedFields 验证 relative spriteFile + stackable + price 字段)。
register_item {
    id = "rf_soul_shard",
    type = "material",
    name = "灵魂碎片",
    desc = "一小片飘忽不定的灵魂结晶,炼金术士视为珍稀材料。",
    image = 83,
    price = 40,
    stackable = true,
    spriteFile = "sprites/items/item_SoulShard.png",
}
