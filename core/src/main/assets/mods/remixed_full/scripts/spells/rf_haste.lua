-- M20g: Haste. Self-cast spell ported from test_mod/scripts/spells/haste.lua.
-- onUse 调 RPD.affectBuff(heroId,"Haste",...) 短暂加速,沿用 iron_skin self affectBuff + blink Haste fallback 范式
-- (test enabled_blinkSpellPrefersTeleportOverHasteFallback 验证 Haste 经 affectBuff 能落到英雄身上)。
register_spell {
    id = "rf_haste",
    name = "加速术",
    desc = "短暂提升施法者的移动速度。",
    image = 2,
    castTime = 1,
    useMode = "mana",
    spellCost = 4,
    targeting = "self",
    spriteFile = "mods/remixed_full/sprites/spells/spell_Haste.png",

    onUse = function(heroId)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(heroId, "Haste", 10)
        end
    end,
}
