-- remixed_full black_cat NPC: 黑猫,城镇氛围 NPC(神秘向)。
-- 源: remished scripts/npc/BlackCat.lua(interact 随机 3 句 + playExtra("sleep") 动画;
--   die 让英雄掉 1 力量 + 粒子 + 音效(惩罚杀猫);spawn setAi BlackCat;
--   actionsList/execute 提供 "pet" 动作)。
-- 降级: fork LuaNpc 仅暴露 onInteract 回调 + {showDialog/npcYell/giveItem/leaveTown} 子集,
--   无 act/die/spawn/actionsList/execute 分发,且 LuaNpc 无敌(damage no-op)故 die 永不触发。
--   原 self:say(textById) → RPD.npcYell 随机硬编码 ZH 猫语;原 playExtra 动画 fork 无此 API → 删除;
--   原_actionsList/"pet" 动作 fork LuaNpc 无此机制 → 并入默认 onInteract(点击即摸猫);
--   原 die() 力量惩罚 LuaNpc 无敌不会触发,保留无意义 → 删除;原 spawn setAi → 删除。
-- 台词硬编码 ZH(fork 无 textById i18n)。引用方式: 关卡 json mobs[] 写 "lua_npc:remixed_full_black_cat"。
local phrases = {
    "喵~ 黑猫眯着眼打量你。",
    "……黑猫竖起尾巴,在你腿边蹭了蹭。",
    "猫的眼睛在黑暗里闪着幽光,仿佛看穿了什么。",
}

register_npc {
    id = "remixed_full_black_cat",
    name = "黑猫",
    sprite = "ghost",

    onInteract = function(selfId, heroId)
        RPD.npcYell(selfId, phrases[math.random(1, #phrases)])
    end,
}
