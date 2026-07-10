-- M20g: Lightning Bolt. Cell-targeted spell ported from test_mod/scripts/spells/lightning_bolt.lua.
-- onUseAt 沿射线对每个生物造成伤害(cellRay + charAtCell + damageChar),沿用 magic_arrow 范式
-- (test enabled_cellSpellMagicArrowDamagesTargetOnRay 验证 damageChar 经 cellRay 命中)。
-- 关键:跳过射线源 cell(from),避免施法者被自己的闪电击中(magic_arrow 同款 guard)。
register_spell {
    id = "rf_lightning_bolt",
    name = "闪电术",
    desc = "沿射线释放闪电,对路径上的所有生物造成伤害。",
    image = 1,
    castTime = 1,
    useMode = "mana",
    spellCost = 5,
    targeting = "cell",
    spriteFile = "mods/remixed_full/sprites/spells/spell_LightningBolt.png",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charPos and RPD.cellRay and RPD.charAtCell and RPD.damageChar and RPD.zapEffect) then return end
        local from = RPD.charPos(heroId)
        local ray = RPD.cellRay(from, cell)
        if ray then
            RPD.zapEffect(from, ray[#ray])
            for i = 1, #ray do
                local c = ray[i]
                if c ~= from then
                    local target = RPD.charAtCell(c)
                    if target then
                        RPD.damageChar(target, 6)
                    end
                end
            end
        end
    end,
}
