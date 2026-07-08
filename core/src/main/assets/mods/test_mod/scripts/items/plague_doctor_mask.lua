-- M10a accessory item: Remished PlagueDoctorMask(瘟疫医生面具)。走 LuaItem(weapon 占位 wrapper —— 无 LuaAccessory/artifact 槽)。
-- 可移植:onEquip → RPD.permanentBuff(hero,"gases_immunity");onDeactivate → RPD.removeBuff(hero,"gases_immunity")。
--   buff id 取注册 id(snake_case),非显示 name "GasesImmunity"。
-- 降级(M10c):原版 equipable=artifact 槽 + Accessory Java 桥(equip/unequip),当前落 weapon 槽(语义降级)。
-- 原件: ../remished-dungeon/scripts/items/PlagueDoctorMask.lua
register_item {
    id = "plague_doctor_mask",
    name = "瘟疫医生面具",
    desc = "瘟疫医生标志性的面具。装备时使佩戴者免疫毒气/麻痹/眩晕。原版为饰品(artifact)槽(降级:当前落 weapon 槽,待 LuaAccessory)。",
    image = 26,
    tier = 1,

    onEquip = function(heroId)
        if RPD and RPD.permanentBuff then
            RPD.permanentBuff(heroId, "gases_immunity")
        end
    end,

    onDeactivate = function(heroId)
        if RPD and RPD.removeBuff then
            RPD.removeBuff(heroId, "gases_immunity")
        end
    end,
}
