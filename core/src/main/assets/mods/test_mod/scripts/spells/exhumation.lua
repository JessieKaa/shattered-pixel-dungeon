-- M10a port of Remished Exhumation. Original: castOnCell, open TOMB/SKELETON heap, tame spawned Wraith.
-- Degrade: no heap / Heap.Type API (needs M10b level); implemented as: spawnAlly test_ally at cell
-- (tamed ally ≈ remished "tame wraith" intent, not a hostile mob). heap-open / Wraith asset dropped.
-- Original: ../remixed-dungeon/scripts/spells/Exhumation.lua
register_spell {
    id = "exhumation",
    name = "掘墓术",
    desc = "在选中格召唤一名友方亡灵(spawnAlly 占位;原版需墓穴堆+驯服幽魂,降级为友方 ally)。",
    image = 3,
    castTime = 2,
    useMode = "mana",
    spellCost = 10,
    targeting = "cell",

    onUseAt = function(heroId, cell)
        if not (RPD and RPD.spawnAlly) then return end
        RPD.spawnAlly("test_ally", cell)
    end,
}
