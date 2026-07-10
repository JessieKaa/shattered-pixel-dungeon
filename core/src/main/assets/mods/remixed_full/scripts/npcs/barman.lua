-- remixed_full barman NPC: 酒保,城镇选项交互 NPC。
-- 源: remished scripts/npc/Barman.lua(interact 弹 chooseOption 测试选项:"Test title / Go back / Yes / No";
--   index0 把英雄 handle 传送回 cell(x,y-3);index1 glog("okay..."))。
-- 注: remished 源是 demo 残留(title="Test title",内容极薄),非完整酒保实现。
-- M18a 升级(回归本貌): fork 已补 RPD.chooseOption API,barman 从 m17a 的单线 showDialog
--   升级为 3 选项交互(买酒/聊传闻/离开),回调用 1-based choice。
--   仍未补: Dungeon.hero:handle / level:cell 传送 API —— 原 remished index0 传送功能
--   继续缺失(信息有损:传送丢失,仅作氛围+选项 NPC)。
-- 台词硬编码 ZH(fork 无 textById i18n)。引用方式: 关卡 json mobs[] 写 "lua_npc:remixed_full_barman"。
register_npc {
    id = "remixed_full_barman",
    name = "酒保",
    sprite = "shopkeeper",

    onInteract = function(selfId, heroId)
        RPD.chooseOption(selfId, "酒保",
            {"买一杯特酿", "聊聊最近的传闻", "转身离开"},
            function(choice)
                if choice == 1 then
                    RPD.showDialog(selfId,
                        "酒保摇摇头:\n\n『今日酒桶见底,特酿一滴不剩了。改日再来吧,旅人。』")
                elseif choice == 2 then
                    RPD.showDialog(selfId,
                        "酒保压低声音:\n\n『听说地牢深处又不太平了 —— 有冒险者下去后再没上来。』\n\n『……保重。』")
                elseif choice == 3 then
                    RPD.npcYell(selfId, "慢走,旅人。")
                end
            end)
    end,
}
