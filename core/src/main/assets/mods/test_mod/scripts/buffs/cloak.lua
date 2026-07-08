-- M10c port of Remished scripts/buffs/Cloak.lua
-- Remished: stealthBonus + INVISIBLE sprite status + timed detach. M10c wires
-- the canonical callbacks: stealthBonus feeds Char.stealth() (bearer harder to
-- detect), charSpriteStatus drives the M8c fx visual State (INVISIBLE sprite).
-- Visual-only — does not touch the Char.invisible gameplay counter.
register_buff{
    id = "cloak",
    name = "Cloak",
    info = "Cloak (M10c: stealthBonus + charSpriteStatus INVISIBLE)",
    icon = 46,

    attachTo = function(targetId, state)
        return true
    end,

    act = function(selfId, targetId, state)
        -- timed: drop after a short window (Remished detaches on act)
        return false
    end,

    stealthBonus = function(selfId)
        return 5
    end,

    charSpriteStatus = function(selfId, state)
        return "INVISIBLE"
    end,
}
