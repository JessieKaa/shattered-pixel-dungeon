-- M8a buff representative: sleepLock votes to suppress the "wake on damage" behaviour
-- in Char.damage (the MagicalSleep detach) — the bearer still takes damage but stays
-- asleep. Fail-open by contract: a missing/errored/non-boolean return yields false,
-- so a broken script can never pin a Char asleep forever.
register_buff{
    id = "regression_demo_sleep_lock",
    name = "RegSleepLockDemo",
    info = "RegSleepLockDemo (M8a: suppress waking on damage; fail-open)",
    icon = 26,

    attachTo = function(targetId, state)
        return true
    end,

    sleepLock = function(selfId)
        return true
    end,
}
