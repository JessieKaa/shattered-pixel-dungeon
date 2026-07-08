-- M6b mob representative: minimal register_mob (pure stats, mirroring test_mod/rat).
-- AI/spawn/etc callback slots are intentionally not exercised here — the regression
-- goal is registry coverage of every register_* global, not mob behaviour depth.
register_mob {
    id = "regression_demo_rat",
    name = "RegRat",
    hp = 12,
    ht = 12,
    attack = 5,
    defense = 2,
    sprite = "rat",
}
