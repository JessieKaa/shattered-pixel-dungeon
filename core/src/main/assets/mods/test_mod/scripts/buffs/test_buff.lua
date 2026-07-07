-- M7a port of Remished scripts/buffs/TestBuff.lua
-- Remished: defenceProc/attackProc logging via RPD.glog. M7a wires both proc
-- hooks to log via RPD.GLog, exercising the new LuaBuff combat callbacks.
register_buff{
    id = "test_buff",
    name = "Test buff",
    info = "Test buff (M7a: attackProc/defenseProc logging via RPD.GLog)",
    icon = 1,

    attachTo = function(targetId, state)
        return true
    end,

    act = function(selfId, targetId, state)
        return 3
    end,

    attackProc = function(selfId, enemyId, damage)
        RPD.GLog("test_buff attackProc dmg=" .. damage)
        return damage
    end,

    defenseProc = function(selfId, enemyId, damage)
        RPD.GLog("test_buff defenseProc dmg=" .. damage)
        return damage
    end,
}
