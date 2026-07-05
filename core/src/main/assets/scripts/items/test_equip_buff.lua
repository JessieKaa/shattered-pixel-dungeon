-- M2 onEquip test item: a tier-1 Lua weapon that applies Barkskin + logs when
-- equipped. Proves the activate→onEquip callback fires on a real equip (and is
-- suppressed during save restore via Belongings.bundleRestoring).
-- "hero" is an int char id (D3 option B).

register_item {
    id = "test_equip_buff",
    name = "测试护身刃 (Lua)",
    desc = "M2 onEquip 验证武器:装备时给自己加 Barkskin(value 5) 并 GLog。读档时不重复触发。",
    image = 212,  -- reuse existing ItemSpriteSheet slot
    tier = 1,

    onEquip = function(hero)
        RPD.affectBuff(hero, "Barkskin", 5)
        RPD.GLog("lua onEquip: Barkskin applied")
    end,

    onDeactivate = function(hero)
        RPD.GLog("lua onDeactivate: weapon unequipped")
    end,
}
