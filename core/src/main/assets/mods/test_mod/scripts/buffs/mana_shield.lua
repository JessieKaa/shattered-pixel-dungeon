-- M6c port of Remished scripts/buffs/ManaShield.lua
-- Remished: defenceProc blocks one hit (damage->0) then detaches. M6c has no
-- defenceProc hook on a generic Lua buff, so the one-hit-block is degraded to
-- a metadata-only marker; the timed detach is preserved.
register_buff{
    id = "mana_shield",
    name = "ManaShield",
    info = "ManaShield (M6c degraded: one-hit block not bridged)",
    icon = 49,
    degraded = true,
    degradation = "Remished defenceProc returns 0 damage once then detaches; M6c has no generic buff defenceProc hook, so the block effect is not active.",

    attachTo = function(targetId, state)
        return true
    end,

    act = function(selfId, targetId, state)
        return false
    end,
}
