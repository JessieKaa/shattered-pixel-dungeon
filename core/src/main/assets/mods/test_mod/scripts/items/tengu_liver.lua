-- M10a material item: Remished TenguLiver(tengu 肝,mastery 食物)。
-- 降级(M10c/LuaMaterial 无 eat/onPickUp 派发):eat(选子类 GUARDIAN/WITCHDOCTOR 的 WndChooseWay)、onPickUp Badge 未接。
-- 原件: ../remished-dungeon/scripts/items/TenguLiver.lua
register_item {
    id = "tengu_liver",
    type = "material",
    name = "tengu 之肝",
    desc = "传说中的 mastery 食物,本可让人选择新的子职业(降级:eat 选职 / onPickUp 徽章 未接)。",
    image = 51,
    price = 0,
    stackable = false,
}
