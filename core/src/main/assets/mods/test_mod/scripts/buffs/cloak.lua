-- M6c port of Remished scripts/buffs/Cloak.lua
-- Remished: stealthBonus + INVISIBLE sprite status + timed detach. M6c has no
-- stealth hook bridged to a generic buff; instead this applies SPD's own
-- Invisibility (whitelisted) on attach and drops it on detach, preserving the
-- gameplay intent (target becomes harder to see while the buff is active).
register_buff{
    id = "cloak",
    name = "Cloak",
    info = "Cloak (M6c: applies SPD Invisibility on attach, removes on detach)",
    icon = 46,

    attachTo = function(targetId, state)
        state.appliedInvis = true
        RPD.affectBuff(targetId, "Invisibility", 6)
        return true
    end,

    detach = function(targetId, state)
        RPD.removeBuff(targetId, "Invisibility")
    end,
}
