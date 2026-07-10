-- M20g: Bone Saw. Remixed-style weapon ported from test_mod/scripts/items/bone_saw.lua.
-- attackProc 命中撕裂目标造成流血,沿用 hooked_dagger/kunai 的 attackProc + RPD.affectBuff 范式
-- (test enabled_weaponAttackProcFiresAndAppliesBuff 已验证 fork 路由 attackProc 并通过 affectBuff 落 Bleeding)。
-- spriteFile 用 absolute 形式,与现有 weapon(hooked_dagger)一致 —— relative 形式仅 material 有测试背书。
register_item {
    id = "rf_bone_saw",
    name = "骨锯",
    desc = "锯齿狰狞的骨制利刃,命中时撕裂目标造成流血。",
    image = 9,
    tier = 3,
    spriteFile = "mods/remixed_full/sprites/items/item_BoneSaw.png",

    attackProc = function(attacker, defender, baseDamage)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(defender, "Bleeding", 3)
        end
        return baseDamage
    end,
}
