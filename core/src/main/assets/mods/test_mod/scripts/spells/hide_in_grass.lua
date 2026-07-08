-- M10a port of Remished HideInGrass. Original: self, only if standing on grass → Cloak(invisibility).
-- Degrade: no terrain/map API (needs M10b level); Cloak custom buff → Invisibility (whitelist).
-- Terrain guard dropped — always grants Invisibility. skillLevel scaling dropped.
-- Original: ../remixed-dungeon/scripts/spells/HideInGrass.lua
register_spell {
    id = "hide_in_grass",
    name = "草丛隐蔽",
    desc = "获得隐身(Invisibility 近似;原版需站在草上+Cloak buff,降级为无条件 Invisibility)。",
    image = 2,
    castTime = 0.1,
    useMode = "mana",
    spellCost = 5,
    targeting = "self",

    onUse = function(heroId)
        if RPD and RPD.affectBuff then
            RPD.affectBuff(heroId, "Invisibility", 10)
        end
    end,
}
