-- M10c port of Remished scripts/buffs/ChampionOfEarth.lua
-- Remished: x4 HT + heal, drBonus, regenerationBonus, setGlowing. M10c wires
-- the canonical callbacks: drBonus bridges into drRoll (additive), regenera-
-- tionBonus feeds Regeneration.act (healRate=1.2^sum), setGlowing drives the
-- fx aura. attachTo keeps the one-shot heal (guarded by state).
register_buff{
    id = "champion_of_earth",
    name = "ChampionOfEarth",
    info = "ChampionOfEarth (M10c: drBonus + regenerationBonus + setGlowing)",
    icon = 0,

    attachTo = function(targetId, state)
        if not state.activated then
            state.activated = true
            RPD.healChar(targetId, 20)
        end
        return true
    end,

    drBonus = function(selfId)
        return RPD.buffLevel(selfId, "champion_of_earth") or 1
    end,

    regenerationBonus = function(selfId)
        return RPD.buffLevel(selfId, "champion_of_earth") or 1
    end,

    -- 0x55AA55 earth-green glow
    setGlowing = function(selfId, state)
        return { color = 5614165, rays = 6 }
    end,
}
