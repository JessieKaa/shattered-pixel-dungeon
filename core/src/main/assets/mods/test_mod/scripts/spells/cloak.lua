-- M10a port of Remished Cloak. Original: self buff "Cloak"(custom invisibility) if no enemies nearby.
-- Degrade: Cloak custom buff → Invisibility (whitelist). visibleEnemies guard dropped (no enemy-query API).
-- skillLevel scaling dropped.
-- Original: ../remixed-dungeon/scripts/spells/Cloak.lua
register_spell {
    id = "cloak",
    name = "披风术",
    desc = "获得隐身(Invisibility 近似;原版 Cloak 自定义 buff,降级为 Invisibility)。",
    image = 0,
    castTime = 0.5,
    useMode = "mana",
    spellCost = 5,
    targeting = "self",

    onUse = function(heroId)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(heroId, "Invisibility", 10)
        end
    end,
}
