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

    onUse = function(heroId)
        if not (RPD and RPD.charPos and RPD.cellRay and RPD.charAtCell and RPD.damageChar and RPD.zapEffect) then return end
        local from = RPD.charPos(heroId)
        -- 无 targeting UI:沿 +x 方向打 3 格作为占位(M6e 接入真实目标选择)。
        local ray = RPD.cellRay(from, from + 3)
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
