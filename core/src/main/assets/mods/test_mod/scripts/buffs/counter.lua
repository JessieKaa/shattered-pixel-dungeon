-- M6c port of Remished scripts/buffs/Counter.lua
-- Remished: per-tick damage + charAct counter via storage. M6c bridges the act
-- tick (damage the bearer by 1 each tick, decrement level, detach at 0) and
-- keeps a per-instance counter in state (isolated per Java buff instance).
register_buff{
    id = "counter",
    name = "Counter",
    info = "Counter (M6c: ticks damage + per-instance counter; charAct not bridged)",
    icon = 46,
    degraded = true,
    degradation = "Remished charAct runs every Char turn; M6c has no per-Char-turn hook on a generic buff, so the counter advances on the buff's own act tick instead.",

    attachTo = function(targetId, state)
        state.counter = 0
        return true
    end,

    act = function(selfId, targetId, state)
        state.counter = (state.counter or 0) + 1
        RPD.damageChar(targetId, 1)
        local lvl = RPD.buffLevel(targetId, "counter") or 1
        lvl = lvl - 1
        if lvl <= 0 then
            RPD.detachBuff(targetId, "counter")
            return true
        end
        RPD.setBuffLevel(targetId, "counter", lvl)
        return 1
    end,
}
