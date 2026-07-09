-- remixed_full inquirer NPC: 调查者,城镇氛围 NPC(讲述隐私政策)。
-- 源: remished scripts/npc/Inquirer.lua(interact 弹 chooseOption 3 选项:启动 PollfishSurveys 问卷 SDK /
--   showStoryWindow("Inquirer_privacyPolicy") 隐私政策故事窗 / 再见)。
-- 降级: fork LuaNpc 仅暴露 onInteract 回调 + {showDialog/npcYell/giveItem/leaveTown} 子集,
--   无 chooseOption,也无 showStoryWindow,且 fork sandbox 禁用 luajava(PollfishSurveys 不可达)。
--   原 chooseOption 3 选项 → 单线 RPD.showDialog(调查者身份 + 隐私政策长文本);
--   原 PollfishSurveys 问卷 SDK(luajava.bindClass)→ 删除(sandbox 禁用 + 商业无关);
--   原 showStoryWindow(隐私政策)→ 并入 showDialog 长文本。
-- 台词硬编码 ZH(fork 无 textById i18n)。引用方式: 关卡 json mobs[] 写 "lua_npc:remixed_full_inquirer"。
register_npc {
    id = "remixed_full_inquirer",
    name = "调查者",
    sprite = "mirror",

    onInteract = function(selfId, heroId)
        RPD.showDialog(selfId,
            "一个戴着兜帽的人递来一份卷宗,压低声音:\n\n" ..
            "『冒昧打扰,旅人。我在做一项关于地牢冒险者的田野调查 —— 不过别紧张,回答与否全凭自愿。』\n\n" ..
            "『所有记录仅用于学术研究,严格保密。』\n\n" ..
            "── 隐私政策 ──\n" ..
            "本项目不收集任何个人信息,不接入任何第三方问卷、广告或统计 SDK。\n" ..
            "你的冒险进度只存于本地,不会上传到任何服务器。")
    end,
}
