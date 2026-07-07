-- M6d 代表 spell:Charm。Remished Charm 给目标 Vertigo/Slow 近似(mind-affecting 无专用 buff)。
-- 原件: ../remished-dungeon/scripts/spells/Charm.lua
-- M7c: enemy targeting。Java 侧已保证选中的 cell 上是 ENEMY,onUseAt 对其施 Vertigo。
-- 注:真 Charm buff 依赖 M7a 的 buff whitelist(并行开发,未合),暂用 Vertigo 近似;
--     M7a 合后把 "Vertigo" 换成真 Charm buff name 即可。
register_spell {
    id = "charm",
    name = "魅惑术",
    desc = "干扰敌人心智(Vertigo 近似)。",
    image = 2,
    castTime = 1,
    spellCost = 4,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charAtCell and RPD.affectBuff) then return end
        local target = RPD.charAtCell(cell)
        if target then
            RPD.affectBuff(target, "Vertigo", 5)
        end
    end,
}
