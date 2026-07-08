-- M10a port of Remished Order. Original: castOnChar, makePet + execute ch_order (mob command).
-- Degrade: no makePet / mob-execute API in the SPD sandbox (commandAlly only commands summoned allies,
-- not arbitrary mobs). Stub: logs a hint. Effect pending a mob-command bridge.
-- Original: ../remixed-dungeon/scripts/spells/Order.lua
register_spell {
    id = "order",
    name = "命令术",
    desc = "命令选中敌人(降级:SPD 暂无通用 mob 指令 API,施法仅提示,无效,零消耗)。",
    image = 3,
    castTime = 1,
    useMode = "mana",
    spellCost = 0,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if RPD and RPD.GLogW then
            RPD.GLogW("命令术需要通用 mob 指令 API(未实现),施法无效。")
        end
    end,
}
