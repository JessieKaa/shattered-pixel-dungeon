-- M8b/M8c demo: declarative shield + gold glow tint. shieldAmount is a plain
-- int field (NOT a callback) — LuaBuff.attachTo seeds it into the shared
-- ShieldTracker pool on both fresh attach and Bundle restore; defenseProc then
-- drains it via RPD.absorbShield. tintChar returns a {color,rays} aura spec
-- (M8c) so the bearer visibly glows gold (0xFFD700 = 16753920 decimal; luaj
-- 3.0.1 = Lua 5.1 has no hex numeric literals).
register_buff{
    id = "shield_demo",
    name = "ShieldDemo",
    info = "ShieldDemo (M8b: ShieldTracker pool; M8c: gold aura)",
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
