-- remixed_full drunkard NPC: 醉汉,城镇氛围 NPC。
-- 源: remished scripts/npc/Drunkard.lua(interact 随机 4 句醉话 textById;act 切 Sleeping 状态)。
-- 降级: fork LuaNpc 仅暴露 onInteract 回调 + {showDialog/npcYell/giveItem/leaveTown} 子集,
--   无 act/die/spawn/actionsList 分发。原 self:say(textById) → RPD.npcYell 随机硬编码 ZH 醉话;
--   原 act() setState("Sleeping") fork LuaNpc 无 act 回调 → 删除(NPC 本就 passive 站桩)。
-- 台词硬编码 ZH(fork 无 textById i18n)。引用方式: 关卡 json mobs[] 写 "lua_npc:remixed_full_drunkard"。
local phrases = {
    "嗝……再来一杯!",
    "这地板在转……你、你看见没?",
    "老子当年……也是条好汉……",
    "酒!有酒吗!给我酒!",
}

register_npc {
    id = "remixed_full_drunkard",
    name = "醉汉",
    sprite = "blacksmith",

    onInteract = function(selfId, heroId)
        RPD.npcYell(selfId, phrases[math.random(1, #phrases)])
    end,
}
