-- M10a port of Remished Anesthesia. Original: castOnChar Sleep + Anesthesia(custom) on target.
-- Degrade: Anesthesia (pain-immunity buff) is a custom Remished buff not in the SPD whitelist
-- (needs M10c buff-callbacks); only Sleep is applied. skillLevel scaling dropped (no hero skillLevel API).
-- Original: ../remixed-dungeon/scripts/spells/Anesthesia.lua
register_spell {
    id = "anesthesia",
    name = "麻醉术",
    desc = "使敌人陷入沉睡(Sleep)。原 Remished 附带 Anesthesia(痛觉屏蔽)buff,SPD 暂缺,降级为纯 Sleep。",
    image = 2,
    castTime = 1,
    useMode = "mana",
    spellCost = 6,
    targeting = "enemy",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.charAtCell and RPD.affectBuff) then return end
        local target = RPD.charAtCell(cell)
        if target then
            RPD.affectBuff(target, "Sleep", 10)
        end
    end,
}
