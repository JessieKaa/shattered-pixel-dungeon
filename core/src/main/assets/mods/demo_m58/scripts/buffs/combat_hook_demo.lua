-- M7a/M7b demo: full combat-hook surface. One buff amends every Char combat
-- value Lua can touch, so a single attach exercises the whole dispatch chain:
--   attackProc   (M7a) — outgoing damage on the bearer's own attack
--   defenseProc  (M7a) — incoming damage before applied
--   attackSkill  (M7b) — to-hit (dispatched at Char.hit / Stone.proc read sites)
--   defenseSkill (M7b) — evasion (same dispatch sites)
--   drRoll       (M7a) — damage-reduction roll (armor-style DR)
--   speed        (M7a) — movement-speed multiplier (float)
--   charAct      (M7b) — per-Char-tick advisory hook (no return consumed)
-- All amendments are small (+10% / +2 / +1 / *1.2) so the buff is observable
-- without breaking combat balance. GLog on attackProc so a tester sees it fire.
register_buff{
    id = "combat_hook_demo",
    name = "CombatHookDemo",
    info = "CombatHookDemo (M7a/M7b: attackProc/defenseProc/attackSkill/defenseSkill/drRoll/speed/charAct)",
    icon = 1,

    attachTo = function(targetId, state)
        return true
    end,

    attackProc = function(selfId, enemyId, damage)
        local out = damage + math.floor(damage * 0.1)
        RPD.GLog("[demo_m58] combat_hook attackProc " .. damage .. "->" .. out)
        return out
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
