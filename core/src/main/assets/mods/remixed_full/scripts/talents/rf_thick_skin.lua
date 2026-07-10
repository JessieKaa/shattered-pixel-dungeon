-- M20c remixed_full talent: 减伤/厚皮向,WARRIOR tier2。
-- 激活预声明的 MOD_SECOND_TALENT 槽位(Talent.java:211, cap=2)。与 rf_initiative 分占
-- 两个 cap=2 槽位,分别注入 ROGUE tier1 / WARRIOR tier2,验证 remixed_full 的 tier1+tier2
-- 双注入。on_upgrade 升级时送暗金(护甲硬派材料)。
register_talent {
    id = "MOD_SECOND_TALENT",
    tier = 2,
    class = "WARRIOR",
    name = "厚皮(Lua)",
    maxPoints = 2,
    desc = "remixed 风格天赋示例(减伤/厚皮向):每次升级获得暗金材料,与先机天赋共同验证 remixed_full 的 tier1/tier2 talent 注入。",
    on_upgrade = function(heroId, points)
        RPD.giveItem(heroId, "remixed_full_dark_gold", points)
    end,
}
