-- remixed_full bishop NPC: 主教,城镇祝福 NPC。
-- 源: remished scripts/npc/Bishop.lua(interact 弹 chooseOption 4 选项:小祝福 100 金 / 大祝福 500 金 /
--   解诅咒 200 金 / 退出,价格按难度×等级缩放;index0/1 affectBuff(Blessed);index2 ScrollOfRemoveCurse;
--   die 让英雄掉半血上限 + 受伤 + 粒子 + 音效(惩罚杀主教);spawn setAi NpcDefault)。
-- 降级: fork LuaNpc 仅暴露 onInteract 回调 + {showDialog/npcYell/giveItem/leaveTown} + affectBuff 子集,
--   无 chooseOption,也无 gold()/spendGold()/RemixedDungeon:getDifficultyFactor/ScrollOfRemoveCurse:uncurse。
--   原 chooseOption 金币交易 → 删除(免费祝福);原 affectBuff(Blessed) → affectBuff(RPD.Buffs.Bless)
--   (fork 白名单 buff,Blessed→Bless 改名);原金币计费 → 删除。
--   原 die() 半血惩罚 LuaNpc 无敌不会触发 → 删除;原粒子(Sfx.ShadowParticle)/playSound → 删除。
-- 守卫: 去掉金币后每次 interact 会免费祝福,加 session 级 blessed[heroId] 一次性守卫(同 guide
--   granted[heroId] 风格),避免反复白嫖。注意 RPD.affectBuff 恒返回 nil(非 bool,见 RpdApi.AffectBuff),
--   无法据返回值判断成功;但 onInteract 的 heroId 来自 hero-only 路由、Bless 确在 fork 白名单,
--   参数确定有效 → 调用后无条件置位(最坏:bad arg 时少一次祝福,正常 play 不会触发)。
-- 台词硬编码 ZH(fork 无 textById i18n)。引用方式: 关卡 json mobs[] 写 "lua_npc:remixed_full_bishop"。
local blessed = {}

register_npc {
    id = "remixed_full_bishop",
    name = "主教",
    sprite = "wandmaker",

    onInteract = function(selfId, heroId)
        if blessed[heroId] then
            RPD.showDialog(selfId,
                "主教正在诵读经文,见你又来,温和地摇头:\n\n" ..
                "『圣辉每日只祝祷一次,孩子。明日再来吧。』")
            return
        end
        RPD.showDialog(selfId,
            "主教微微颔首,画了个十字:\n\n" ..
            "『愿光芒庇佑你,孩子。』\n\n" ..
            "『在你踏上地牢征途前,让圣辉为你祝祷 —— 愿你的剑刃所向披靡。』\n\n" ..
            "一道温暖的祝福环绕全身 —— 获得「祝福」增益。")
        RPD.affectBuff(heroId, RPD.Buffs.Bless, 200)
        blessed[heroId] = true
    end,
}
