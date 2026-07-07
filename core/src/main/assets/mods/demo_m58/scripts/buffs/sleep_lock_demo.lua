-- M8a demo: sleepLock. A Lua buff that votes to suppress the "wake on damage"
-- behaviour in Char.damage (the MagicalSleep detach) — the bearer still takes
-- damage but stays asleep. Fail-open by contract: a missing/errored/non-boolean
-- return yields false, so a broken script can never pin a Char asleep forever.
-- Gameplay note: meaningful on a sleeping target (anesthesia-style); on a hero
-- it is a no-op unless the hero is asleep. Triggered here via m58_test_weapon's
-- attackProc (applied to the enemy on hit).
register_buff{
    id = "sleep_lock_demo",
    name = "SleepLockDemo",
    info = "SleepLockDemo (M8a: suppress waking on damage; fail-open)",
    icon = 26,

    attachTo = function(targetId, state)
        RPD.GLog("[demo_m58] sleep_lock attached to " .. targetId)
        return true
    end,

    sleepLock = function(selfId)
        return true
    end,
}
