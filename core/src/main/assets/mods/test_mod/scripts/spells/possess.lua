-- M10a port of Remished Possess. Original: castOnChar, makePet + ControlledAi + setControlTarget.
-- Degrade: no makePet / ControlledAi / setControlTarget API (needs a mob-possession bridge, not yet planned).
-- Stub: logs a hint. Effect pending a possession API.
-- Original: ../remixed-dungeon/scripts/spells/Possess.lua
register_spell {
    id = "possess",
    name = "附身术",
    desc = "附身选中敌人(降级:SPD 暂无附身/受控 AI API,施法仅提示,无效,零消耗)。",
    image = 0,
    castTime = 0.5,
    useMode = "mana",
    spellCost = 0,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if RPD and RPD.GLogW then
            RPD.GLogW("附身术需要 mob 附身 API(未实现),施法无效。")
        end
    end,
}
