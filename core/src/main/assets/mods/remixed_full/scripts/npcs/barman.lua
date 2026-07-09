-- remixed_full barman NPC: 酒保,城镇氛围 NPC。
-- 源: remished scripts/npc/Barman.lua(interact 弹 chooseOption 测试选项:"Test title / Go back / Yes / No";
--   index0 把英雄 handle 传送回 cell(x,y-3);index1 glog("okay..."))。
-- 注: remished 源是 demo 残留(title="Test title",内容极薄),非完整酒保实现。
-- 降级: fork LuaNpc 仅暴露 onInteract 回调 + {showDialog/npcYell/giveItem/leaveTown} 子集,
--   无 chooseOption,也无 Dungeon.hero:handle/level:cell 传送 API。原 chooseOption 4 选项 → 单线
--   RPD.showDialog 酒馆寒暄;原 handle 传送 → 删除(信息有损:传送功能丢失,仅作氛围 NPC)。
-- 台词硬编码 ZH(fork 无 textById i18n)。引用方式: 关卡 json mobs[] 写 "lua_npc:remixed_full_barman"。
register_npc {
    id = "remixed_full_barman",
    name = "酒保",
    sprite = "shopkeeper",

    onInteract = function(selfId, heroId)
        RPD.showDialog(selfId,
            "酒保默默擦拭着杯子,见你走近,微微点头:\n\n" ..
            "『欢迎,旅人。这酒馆是你歇脚的地方。』\n\n" ..
            "『本店的特酿能驱散地牢的寒意 —— 可惜今日酒桶见底,改日再来吧。』")
    end,
}
