-- remixed_full bard NPC: 吟游诗人,城镇氛围 NPC。
-- 源: remished scripts/npc/Bard.lua(interact 唱 BardSong_1;act 持续喷音符粒子;spawn setAi NpcDefault)。
-- 降级: fork LuaNpc 仅暴露 onInteract 回调 + {showDialog/npcYell/giveItem/leaveTown} 子集,
--   无 act/die/spawn 分发。原 self:say(textById) → RPD.showDialog 硬编码 ZH 歌词;
--   原 act() 音符粒子(speckEffectFactory + pourSpeck + RPD.Sfx.Speck)fork 无此 API → 删除;
--   原 spawn() setAi fork LuaNpc 无 spawn 回调 → 删除(NPC passive)。
-- 台词硬编码 ZH(fork 无 textById i18n)。引用方式: 关卡 json mobs[] 写 "lua_npc:remixed_full_bard"。
register_npc {
    id = "remixed_full_bard",
    name = "吟游诗人",
    sprite = "imp",

    onInteract = function(selfId, heroId)
        RPD.showDialog(selfId,
            "♪ 地牢深处藏黄金,英雄挥剑斩魔魂 ♪\n" ..
            "♪ 杯中斟满月光酒,一曲唱尽古今人 ♪\n\n" ..
            "吟游诗人拨动琴弦,沙哑的嗓音在酒馆里回荡。\n" ..
            "他冲你举杯:『愿你的剑永远锋利,旅人。』")
    end,
}
