-- remixed_full inquirer NPC: 调查者,城镇氛围 NPC(讲述隐私政策)。
-- 源: remished scripts/npc/Inquirer.lua(interact 弹 chooseOption 3 选项:启动 PollfishSurveys 问卷 SDK /
--   showStoryWindow("Inquirer_privacyPolicy") 隐私政策故事窗 / 再见)。
-- M17a 降级: fork LuaNpc 无 chooseOption,也无 showStoryWindow,且 sandbox 禁 luajava(PollfishSurveys 不可达)
--   → 单线 showDialog(调查者身份 + 隐私政策长文本)。
-- M19c 升级: M18a 已补 RPD.chooseOption,inquirer 从单线升级为 3 选项交互:
--   了解问卷调查(showDialog 说明原 remished 的问卷 SDK,本 fork 不接任何第三方 SDK,仅作氛围)/
--   查看隐私政策(showDialog 隐私文案)/ 告辞(npcYell)。
--   仍未补(继续缺失,信息有损):PollfishSurveys 问卷 SDK(luajava.bindClass)→ 删除(sandbox 禁用 +
--   商业无关),选项1 改为"说明"而非"启动";showStoryWindow(隐私政策)→ 并入选项2 的 showDialog 长文本。
-- 台词硬编码 ZH(fork 无 textById i18n)。引用方式: 关卡 json mobs[] 写 "lua_npc:remixed_full_inquirer"。
register_npc {
    id = "remixed_full_inquirer",
    name = "调查者",
    sprite = "mirror",

    onInteract = function(selfId, heroId)
        RPD.chooseOption(selfId, "调查者",
            {"了解问卷调查", "查看隐私政策", "告辞"},
            function(choice)
                if choice == 1 then
                    RPD.showDialog(selfId,
                        "一个戴着兜帽的人递来一份卷宗,压低声音:\n\n" ..
                        "『冒昧打扰,旅人。我在做一项关于地牢冒险者的田野调查 —— 不过别紧张,回答与否全凭自愿。』\n\n" ..
                        "『原本我会请你填写一份问卷……但这副躯体所在的版本,不接入任何外部问卷服务。』\n\n" ..
                        "『所以,权当一次闲聊吧。若你愿意,可以听听我的隐私承诺。』")
                elseif choice == 2 then
                    RPD.showDialog(selfId,
                        "调查者翻开卷宗扉页,逐条念给你听:\n\n" ..
                        "── 隐私政策 ──\n" ..
                        "本项目不收集任何个人信息,不接入任何第三方问卷、广告或统计 SDK。\n" ..
                        "你的冒险进度只存于本地,不会上传到任何服务器。\n\n" ..
                        "『所有记录仅用于学术研究,严格保密。』")
                elseif choice == 3 then
                    RPD.npcYell(selfId, "祝你好运,旅人。")
                end
            end)
    end,
}
