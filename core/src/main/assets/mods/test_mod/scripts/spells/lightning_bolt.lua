-- M6d 代表 spell:LightningBolt。cellRay + charAtCell + damageChar 近似 Remished lightningProc。
-- 原件: ../remished-dungeon/scripts/spells/LightningBolt.lua(lightningProc 用 Ballistica)
register_spell {
    id = "lightning_bolt",
    name = "闪电术",
    desc = "沿射线造成伤害(cellRay + charAtCell + damageChar 近似)。",
    image = 1,
    castTime = 1,
    spellCost = 5,
    targeting = "cell",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charPos and RPD.cellRay and RPD.charAtCell and RPD.damageChar and RPD.zapEffect) then return end
        local from = RPD.charPos(heroId)
        -- M7c: 真实目标选择。射线终点是玩家选中的 cell(不再用 from+3 占位)。
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
