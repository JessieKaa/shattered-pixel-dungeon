-- M16b alpha spell: Magic Arrow. Cell-targeted mana bolt that damages the first creature on the ray.
register_spell {
    id = "remixed_full_magic_arrow",
    name = "魔法箭",
    desc = "沿直线发射一枚魔法箭,对路径上第一个敌人造成伤害。",
    image = 1,
    castTime = 1,
    useMode = "mana",
    spellCost = 4,
    targeting = "cell",
    spriteFile = "mods/remixed_full/sprites/spells/spell_MagicArrow.png",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charPos and RPD.cellRay and RPD.charAtCell and RPD.damageChar and RPD.zapEffect) then return end
        local from = RPD.charPos(heroId)
        local ray = RPD.cellRay(from, cell)
        if ray then
            RPD.zapEffect(from, ray[#ray])
            -- Ballistica paths include the source cell; skip it so the bolt
            -- never strikes its own caster.
            for i = 1, #ray do
                local c = ray[i]
                if c ~= from then
                    local target = RPD.charAtCell(c)
                    if target then
                        RPD.damageChar(target, 8)
                        break
                    end
                end
            end
        end
    end,
}
