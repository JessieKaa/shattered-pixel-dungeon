-- M8d1/M8d2 representative: register_talent tier=2 via the `class` dispatch branch.
-- This is the SAME branch tier1 would use — Talent.java declares no tier1 MOD_ slot,
-- and RegisterTalentFunction gates tier<=2 to exactly the `class` key, so tier2 here
-- exercises the tier1 code path too (see PLAN "Talent tier1 scope 决策").
-- Activates the pre-declared MOD_SECOND_TALENT enum slot for MAGE tier 2. `name` is
-- MANDATORY (MOD_ slots have no .title properties key). on_upgrade exercises
-- RPD.giveItem + RPD.affectBuff against THIS mod's own ids, so it does not depend on
-- any other mod being enabled.
register_talent{
    id = "MOD_SECOND_TALENT",
    tier = 2,
    class = "MAGE",
    name = "回归二号天赋",
    maxPoints = 2,
    desc = "M11e 回归:升级时送一把回归鹤嘴锄并挂 regression_demo_combat buff。",

    on_upgrade = function(heroId, points)
        RPD.giveItem(heroId, "regression_demo_pickaxe", 1)
        RPD.affectBuff(heroId, "regression_demo_combat", points)
    end,
}
