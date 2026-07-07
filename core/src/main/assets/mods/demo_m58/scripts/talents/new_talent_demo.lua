-- M8d1/M8d2 demo: a NEW talent entering a class tier via register_talent.
-- Activates the pre-declared MOD_SECOND_TALENT enum slot (Talent.java:211) for
-- MAGE tier 2. The `name` field is MANDATORY — MOD_ enum slots have no .title
-- properties key, so Talent.title() reads the Lua title override first; without
-- it the UI would render a !!!MOD_SECOND_TALENT.title!!! placeholder.
-- on_upgrade(heroId, points) fires from Talent.onTalentUpgraded on each upgrade;
-- it exercises two RPD helpers (giveItem + affectBuff) end-to-end. giveItem uses
-- demo_m58's own m58_test_weapon (test_mod is disabled in the load test, so
-- test_sword would be an unknown id).
register_talent{
    id = "MOD_SECOND_TALENT",
    tier = 2,
    class = "MAGE",
    name = "M58 二号天赋",
    maxPoints = 2,
    desc = "M58 demo:升级时送一把测试武器并挂 combat_hook_demo buff",

    on_upgrade = function(heroId, points)
        RPD.giveItem(heroId, "m58_test_weapon", 1)
        RPD.affectBuff(heroId, "combat_hook_demo", points)
    end,
}
