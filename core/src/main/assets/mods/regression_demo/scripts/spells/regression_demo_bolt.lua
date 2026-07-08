-- M6d/M7c spell representative: cell-targeted mana spell. onUseAt exercises the RPD
-- ray/effect/damage trio (cellRay + zapEffect + damageChar) — the M7c real-targeting
-- path where the ray endpoint is the player-selected cell (not a from+offset stub).
-- useMode="mana" + spellCost exercise the mana mode (M7d).
register_spell {
    id = "regression_demo_bolt",
    name = "回归闪电",
    desc = "沿射线造成伤害(cellRay + zapEffect + damageChar)。",
    image = 1,
    castTime = 1,
    useMode = "mana",
    spellCost = 5,
    targeting = "cell",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charPos and RPD.cellRay and RPD.charAtCell and RPD.damageChar and RPD.zapEffect) then return end
        local from = RPD.charPos(heroId)
        local ray = RPD.cellRay(from, cell)
        if ray then
            RPD.zapEffect(from, ray[#ray])
            for i = 1, #ray do
                local target = RPD.charAtCell(ray[i])
                if target then
                    RPD.damageChar(target, 8)
                end
            end
        end
    end,
}
