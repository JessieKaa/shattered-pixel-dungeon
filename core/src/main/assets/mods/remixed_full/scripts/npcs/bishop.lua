-- remixed_full bishop NPC: 主教,城镇祝福 NPC。
-- 源: remished scripts/npc/Bishop.lua(interact 弹 chooseOption 4 选项:小祝福 100 金 / 大祝福 500 金 /
--   解诅咒 200 金 / 退出,价格按难度×等级缩放;index0/1 affectBuff(Blessed);index2 ScrollOfRemoveCurse;
--   die 让英雄掉半血上限 + 受伤 + 粒子 + 音效(惩罚杀主教);spawn setAi NpcDefault)。
-- M17a 降级: fork LuaNpc 仅暴露 onInteract 回调 + {showDialog/npcYell/giveItem/leaveTown} + affectBuff 子集,
--   无 chooseOption → 单线 showDialog 免费祝福。
-- M19c 升级: M18a 已补 RPD.chooseOption,bishop 从单线升级为 3 选项交互:
--   祈求祝福(免费,沿用 M17a session 级 blessed[heroId] 一次性守卫,避免反复白嫖)/
--   请教教义(showDialog 教义长文本)/ 告辞(npcYell)。
--   仍未补(继续缺失,信息有损):金币计费(无 gold/spendGold/getDifficultyFactor)→ 免费;
--   解诅咒选项(无 ScrollOfRemoveCurse:uncurse)→ 用教义选项替代;die 半血惩罚(LuaNpc 无敌)→ 删除;
--   Sfx/playSound 粒子 → 删除。
-- 守卫注意: RPD.affectBuff 恒返回 nil(非 bool),无法据返回值判断成功;但 onInteract 的 heroId 来自
--   hero-only 路由、Bless 确在 fork 白名单,参数确定有效 → 祝福分支调用后无条件置位(最坏:bad arg 时
--   少一次祝福,正常 play 不会触发)。守卫放在 chooseOption callback 的 choice==1 分支内。
-- 台词硬编码 ZH(fork 无 textById i18n)。引用方式: 关卡 json mobs[] 写 "lua_npc:remixed_full_bishop"。
local blessed = {}

register_npc {
    id = "remixed_full_bishop",
    name = "主教",
    sprite = "wandmaker",

    onInteract = function(selfId, heroId)
        RPD.chooseOption(selfId, "主教",
            {"祈求祝福", "请教教义", "告辞"},
            function(choice)
                if choice == 1 then
                    if blessed[heroId] then
                        RPD.showDialog(selfId,
                            "主教正在诵读经文,见你又来,温和地摇头:\n\n" ..
                            "『圣辉每日只祝祷一次,孩子。明日再来吧。』")
                    else
                        RPD.showDialog(selfId,
                            "主教微微颔首,画了个十字:\n\n" ..
                            "『愿光芒庇佑你,孩子。』\n\n" ..
                            "『在你踏上地牢征途前,让圣辉为你祝祷 —— 愿你的剑刃所向披靡。』\n\n" ..
                            "一道温暖的祝福环绕全身 —— 获得「祝福」增益。")
                        RPD.affectBuff(heroId, RPD.Buffs.Bless, 200)
                        blessed[heroId] = true
                    end
                elseif choice == 2 then
                    RPD.showDialog(selfId,
                        "主教翻开一本泛黄的经卷,缓缓开口:\n\n" ..
                        "『地牢之下,是被遗忘的旧日罪愆。勇者前赴后继,非为黄金,而为驱散那片黑暗。』\n\n" ..
                        "『记住,孩子 —— 光辉不偏爱持剑的手,它只照亮不退缩的心。』\n\n" ..
                        "『若你迷失方向,便回到圣像前;信仰会为你重新指路。』")
                elseif choice == 3 then
                    RPD.npcYell(selfId, "愿圣辉与你同行,旅人。")
                end
            end)
    end,
}
