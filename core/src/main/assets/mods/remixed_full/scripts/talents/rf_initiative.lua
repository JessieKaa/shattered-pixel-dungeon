-- M20c remixed_full talent: 先攻/速度向,ROGUE tier1。
-- 激活预声明的 MOD_EXAMPLE_TALENT 槽位(Talent.java:211, cap=2)。Lua 不能 mint 新 id,
-- 只能激活预声明槽位;tier1 走 class 分发分支(与 tier2 同路径,key 仅为 class)。
-- on_upgrade 升级时送锈币(盗贼轻量向材料),证明 register_talent + on_upgrade +
-- RPD.giveItem 在 remixed_full 下端到端可用。
register_talent {
    id = "MOD_EXAMPLE_TALENT",
    tier = 1,
    class = "ROGUE",
    name = "先机(Lua)",
    maxPoints = 2,
    desc = "remixed 风格天赋示例(先攻/速度向):每次升级获得锈币奖励,证明 register_talent + on_upgrade 在 remixed_full 下端到端可用。",
    on_upgrade = function(heroId, points)
        RPD.giveItem(heroId, "remixed_full_rusty_coin", points)
    end,
}
