-- M6c/M7a/M7b buff representative: one buff exercising the full Char combat-hook
-- dispatch chain (7 callbacks). All amendments are small (+1/+2/*1.2) so the buff
-- is observable without breaking combat balance. attachTo returns true so the buff
-- attaches cleanly on RPD.affectBuff (the on_upgrade smoke path attaches this).
register_buff{
    id = "regression_demo_combat",
    name = "RegCombatDemo",
    info = "RegCombatDemo (M6c/M7a/M7b: attackProc/defenseProc/attackSkill/defenseSkill/drRoll/speed/charAct)",
    icon = 1,

    attachTo = function(targetId, state)
        return true
    end,

    attackProc = function(selfId, enemyId, damage)
        return damage + 1
    end,

    defenseProc = function(selfId, enemyId, damage)
        return math.floor(damage * 0.9)
    end,

    attackSkill = function(selfId, atk)
        return atk + 2
    end,

    defenseSkill = function(selfId, def)
        return def + 2
    end,

    drRoll = function(selfId, dr)
        return dr + 1
    end,

    speed = function(selfId, spd)
        return spd * 1.2
    end,

    charAct = function(selfId, targetId, state)
        state.tick = (state.tick or 0) + 1
    end,
}
