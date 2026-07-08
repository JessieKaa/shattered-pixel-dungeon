-- M10c port of Remished scripts/buffs/ChampionOfAir.lua
-- Remished: hasteLevel + setGlowing. M10c wires both via the new canonical
-- callbacks: hasteLevel feeds Char.speed() as clamp(1.1^sum, 0.25, 4.0), and
-- setGlowing drives the M8c fx aura (glow) on the bearer's sprite.
register_buff{
    id = "champion_of_air",
    name = "ChampionOfAir",
    info = "ChampionOfAir (M10c: hasteLevel + setGlowing)",
    icon = 0,

    attachTo = function(targetId, state)
        return true
    end,

    -- returns a haste level; summed with other hasteLevel buffs, then speed *= 1.1^sum
    hasteLevel = function(selfId)
        return RPD.buffLevel(selfId, "champion_of_air") or 1
    end,

    -- 0xAAAABB air-grey glow, declarative (reuses M8c aura via computeTint)
    setGlowing = function(selfId, state)
        return { color = 11184827, rays = 6 }
    end,
}
