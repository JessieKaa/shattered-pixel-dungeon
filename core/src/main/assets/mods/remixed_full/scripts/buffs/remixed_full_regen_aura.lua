-- M20d remixed_full buff: RegenAura.
-- Remished-style regen/aura buff. The fork's RPD.* does not expose a
-- char-iteration API (no getAllChars/forEachChar/neighbors) and the sandbox
-- does not expose Dungeon, so "aura" is realized on the bearer: act() trickles
-- a periodic heal via RPD.healChar on a cooldown, regenerationBonus feeds SPD's
-- native Regeneration (out-of-combat heal), and setGlowing drives the M8c fx
-- aura (green glow) on the bearer's sprite.
register_buff{
    id = "remixed_full_regen_aura",
    name = "RegenAura",
    info = "RegenAura (M20d: periodic heal + regeneration bonus + green glow aura)",
    icon = 39,

    attachTo = function(targetId, state)
        state.ticks = 0
        return true
    end,

    act = function(selfId, targetId, state)
        state.ticks = (state.ticks or 0) + 1
        RPD.healChar(targetId, 2)
        return 10
    end,

    regenerationBonus = function(selfId)
        return 1
    end,

    -- 0x55AA55 earth-green glow, declarative (reuses M8c aura via computeTint)
    setGlowing = function(selfId, state)
        return { color = 5614165, rays = 6 }
    end,
}
