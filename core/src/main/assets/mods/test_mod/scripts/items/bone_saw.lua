-- M10a weapon item: Remished BoneSaw(骨锯,Plague Doctor 系武器)。走 LuaItem(weapon wrapper)。
-- 可移植:attackProc 命中给目标 Bleeding(RPD.affectBuff 取 snake_case buff id)。
-- 降级(M10c/无 API):damageRoll/accuracyFactor/attackDelayFactor 自定义公式 → tier 基线;
--   Doctor 暴击(50% 加成)+ 采集掉落(ToxicGland/RottenOrgan/BoneShard)+ 麻痹加成(2x)
--   需 heroClass/itemFactory API,未接。attacker/defender 是 charId。
-- 原件: ../remished-dungeon/scripts/items/BoneSaw.lua
register_item {
    id = "bone_saw",
    name = "骨锯",
    desc = "锯齿狰狞的骨制利刃,命中撕裂目标造成流血。原版还有 Doctor 暴击/采集加成(降级:需 heroClass API)。",
    image = 9,
    tier = 3,

    attackProc = function(attacker, defender, baseDamage)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(defender, "Bleeding", 3)
        end
        return baseDamage
    end,
}
