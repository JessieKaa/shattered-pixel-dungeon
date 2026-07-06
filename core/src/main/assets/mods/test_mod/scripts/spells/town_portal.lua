-- M6d 代表 spell:TownPortal。enterTown(levelId) 复用 M4d 城镇传送。
-- 原件: ../remished-dungeon/scripts/spells/TownPortal.lua(teleportTo town_2)
register_spell {
    id = "town_portal",
    name = "回城术",
    desc = "进入 JSON 城镇关卡(M4d enterTown)。",
    image = 3,
    castTime = 2,
    spellCost = 3,
    targeting = "self",

    onUse = function(heroId)
        if RPD and RPD.enterTown then
            RPD.enterTown("town_hub")
        end
    end,
}
