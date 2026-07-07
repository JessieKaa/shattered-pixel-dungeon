-- M6c port of Remished scripts/buffs/Anesthesia.lua
-- Remished: marks damage as non-waking during sleep (handled by a modified
-- Sleeping AI that SPD does not expose). M8a bridges this via a Char.damage
-- guard on the MagicalSleep detach + the LuaBuff sleepLock callback, so the
-- bearer still takes damage but does not wake.
register_buff{
    id = "anesthesia",
    name = "Anesthesia",
    info = "Anesthesia (M8a: suppresses waking on damage)",
    icon = 26,

    attachTo = function(targetId, state)
        return true
    end,

    sleepLock = function(selfId)
        return true
    end,
}
