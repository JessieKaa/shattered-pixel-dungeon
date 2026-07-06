-- M6c port of Remished scripts/buffs/Anesthesia.lua
-- Remished: marks damage as non-waking during sleep (handled by a modified
-- Sleeping AI that SPD does not expose). M6c has no per-damage proc hook on a
-- generic Buff, so this buff attaches with metadata + icon only and is
-- documented as behaviourally degraded (no sleep-lock effect).
register_buff{
    id = "anesthesia",
    name = "Anesthesia",
    info = "Anesthesia (M6c degraded: sleep-lock hook not bridged)",
    icon = 26,
    degraded = true,
    degradation = "Remished uses a custom damage() callback to suppress waking; SPD has no generic buff damage proc, so this buff is metadata-only.",

    attachTo = function(targetId, state)
        return true
    end,
}
