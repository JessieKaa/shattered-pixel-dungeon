-- M8b/M8c buff representative: declarative shield (shieldAmount is a plain int that
-- LuaBuff.attachTo seeds into the shared ShieldTracker pool) + gold aura tint
-- (tintChar returns a {color,rays} spec). defenseProc drains the shield via
-- RPD.absorbShield. Color is decimal (0xFFD700 = 16753920); luaj 3.0.1 / Lua 5.1
-- hex literals do compile here (remixed_pickaxe uses 0xAA0000), but decimal is kept
-- for parity with the proven shield_demo pattern.
register_buff{
    id = "regression_demo_shield",
    name = "RegShieldDemo",
    info = "RegShieldDemo (M8b: ShieldTracker pool; M8c: gold aura tint)",
    icon = 48,
    shieldAmount = 20,
    shieldType = "physical",

    attachTo = function(targetId, state)
        return true
    end,

    defenseProc = function(selfId, enemyId, damage)
        return RPD.absorbShield(selfId, damage)
    end,

    tintChar = function(selfId, state)
        return { color = 16753920, rays = 4 }
    end,
}
