-- M6d 代表 spell:TownPortal。M7c 改为 cell targeting 的「传送术」。
-- 原 M6d 是 self+enterTown(回城),但回城本质 self-cast,无法演示 cell targeting。
-- M7c 改成 Blink 式:把施法者传送到选中的格子(teleportChar),真正用上 cell 目标。
-- (回城语义若需要,后续 spell 用 targeting=self + enterTown 自行实现。)
-- 原件: ../remished-dungeon/scripts/spells/TownPortal.lua(teleportTo town_2)
register_spell {
    id = "town_portal",
    name = "传送术",
    desc = "传送到选中的格子(teleportChar;需 passable 格)。",
    image = 3,
    castTime = 2,
    spellCost = 3,
    targeting = "cell",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.teleportChar) then return end
        RPD.teleportChar(heroId, cell)
    end,
}
