-- M2 attackProc test item: a tier-2 Lua weapon that adds Bleeding on hit and
-- bumps damage by 1. Proves the proc callback chain (super.proc runs first for
-- enchantments/ID, then Lua can layer on a debuff + override damage).
-- attacker/defender are int char ids (D3 option B) — RPD.affectBuff resolves them.

register_item {
    id = "test_proc_weapon",
    name = "测试钩镰 (Lua)",
    desc = "M2 attackProc 验证武器:命中时给目标加 Bleeding(level 3) 并 +1 伤害。attacker/defender 是 charId。",
    image = 216,  -- ItemSpriteSheet.HOOKED_SWORD-ish slot, reuse existing art
    tier = 2,

    attackProc = function(attacker, defender, baseDamage)
        RPD.affectBuff(defender, "Bleeding", 3)
        RPD.GLog("lua attackProc: applied Bleeding, base=" .. baseDamage)
        return baseDamage + 1
    end,
}
