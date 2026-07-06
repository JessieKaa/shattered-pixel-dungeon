-- M6c port of Remished scripts/buffs/TestBuff.lua
-- Remished: defenceProc/attackProc logging via RPD.glog. M6c has no proc hooks
-- on a generic buff; this port keeps the metadata + a timed detach so the
-- registration + lifecycle path is exercised by the test suite.
register_buff{
    id = "test_buff",
    name = "Test buff",
    info = "Test buff (M6c degraded: attackProc/defenceProc logging not bridged)",
    icon = 1,
    degraded = true,
    degradation = "Remished logs on attackProc/defenceProc; M6c has no generic buff proc hook.",

    attachTo = function(targetId, state)
        return true
    end,

    act = function(selfId, targetId, state)
        return 3
    end,
}
