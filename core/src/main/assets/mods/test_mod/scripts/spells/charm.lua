-- M6d 代表 spell:Charm。Remished Charm 给目标 Vertigo/Slow 近似(mind-affecting 无专用 buff)。
-- 原件: ../remished-dungeon/scripts/spells/Charm.lua
register_spell {
    id = "charm",
    name = "魅惑术",
    desc = "干扰目标心智(Vertigo 近似)。",
    image = 2,
    castTime = 1,
    spellCost = 4,
    targeting = "char",

    onUse = function(heroId)
        -- 自身施法占位;真实 char-targeting 需 targeting UI(M6e)。
        if RPD and RPD.affectBuff then
            RPD.affectBuff(heroId, "Vertigo", 5)
        end
    end,
}
