-- M7d demo: full mana API. attachTo exercises all four RPD mana helpers:
--   restoreMana(heroId, amt)  — gain clamped to manaMax
--   spendMana(heroId, amt)    — deduct iff enough mana
--   heroMana(heroId)          — current MP (NIL for non-Hero / missing)
--   heroManaMax(heroId)       — MP cap    (NIL for non-Hero / missing)
-- restore-then-spend-then-log lets a tester read the net effect in one attach.
-- heroMana/Max may return nil (the bearer is not a Hero); tostring() guards the
-- GLog concat so a non-Hero bearer does not crash the attach.
register_buff{
    id = "mana_demo",
    name = "ManaDemo",
    info = "ManaDemo (M7d: restoreMana/spendMana/heroMana/heroManaMax)",
    icon = 49,

    attachTo = function(targetId, state)
        RPD.restoreMana(targetId, 50)
        RPD.spendMana(targetId, 10)
        local cur = RPD.heroMana(targetId)
        local cap = RPD.heroManaMax(targetId)
        RPD.GLog("[demo_m58] mana " .. tostring(cur) .. "/" .. tostring(cap))
        return true
    end,
}
