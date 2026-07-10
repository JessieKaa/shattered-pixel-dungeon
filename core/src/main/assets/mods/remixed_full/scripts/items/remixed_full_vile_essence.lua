-- M19e: Vile Essence. Stackable material, ported from remixed VileEssence.lua.
-- 降级:去掉 remixed 的 glowing 视觉发光(itemLib.makeGlowing,fork LuaMaterial 无 glowing 支持);
--       材料属性(price/stackable)完整保留。
register_item {
    id = "remixed_full_vile_essence",
    type = "material",
    name = "恶秽精华",
    desc = "一团散发着不洁气息的暗紫色精华,价值不菲。",
    image = 97,
    price = 10,
    stackable = true,
    spriteFile = "sprites/items/item_VileEssence.png",
}
