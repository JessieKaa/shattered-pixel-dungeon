# PLAN: M19c — NPC choice content upgrade

## Goal
利用 M18a `RPD.chooseOption` 升级 remixed_full NPC 内容:把 bishop/inquirer 从单线 dialog 升级为选项交互,并评估搬运一个 remixed 选项型 NPC(Innkeeper 或 PlagueDoctor 的降级版)。

## Context
M18a 已提供 `RPD.chooseOption(charId,title,options,callback)`(callback 收 1-based choice;dismiss/-1 不触发)。M17a 的 barman 已作为示例升级。当前 remixed_full 还有 bishop/inquirer 是 chooseOption 降级残留(单线 showDialog);remixed 原 NPC 里 Innkeeper/PlagueDoctor/Mercenary 仍 blocked by trade/quest/hire API,但可先做低风险降级选项内容。

## Fork NPC API 现状(本 feature 的事实基础)
fork `RpdApi` 对 NPC `onInteract(selfId, heroId)` 暴露的子集(经 RpdApi.java + RemixedFullPackTest 禁令清单核对):
- `RPD.chooseOption(selfId, title, {opt1,opt2,...}, function(choice) ... end)` — M18a,1-based choice
- `RPD.showDialog(selfId, text)` / `RPD.npcYell(selfId, text)`
- `RPD.giveItem(heroId, itemId, qty)` — **只解析 `LuaItemRegistry` 的 id,不解析 vanilla 物品**;有 per-depth quota
- `RPD.affectBuff(heroId, RPD.Buffs.Bless, amount)` — 白名单 buff(Blessed→Bless 改名)
- `RPD.leaveTown()` — town portal
- **缺失**:gold/spendGold、showTradeWindow、showQuestWindow、showStoryWindow、Sfx 粒子、playSound、
  setAi、getDifficultyFactor、textById、luajava、ScrollOfRemoveCurse:uncurse、mob 持久化(restoreData/storeData)、
  checkItem、collectAnimated、pet(Pets_l/makePet)、getHeroClass、compassTarget。

## Files
- `core/src/main/assets/mods/remixed_full/scripts/npcs/bishop.lua` — 升级为 chooseOption 3 选项
- `core/src/main/assets/mods/remixed_full/scripts/npcs/inquirer.lua` — 升级为 chooseOption 3 选项
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RemixedFullPackTest.java` — 不改禁令清单(chooseOption 已非禁),仅随 bishop/inquirer 文本变化保持绿(见 Steps)
- `docs/PLAN-m19c-npc-choice-content.md`
- **不新增** innkeeper.lua / plague_doctor.lua(见 Portability Assessment)

## Portability Assessment(Innkeeper / PlagueDoctor)
对比 `../remixed-dungeon/scripts/npc/` 原始源:

- **PlagueDoctor → BLOCKED**。原始是 5 阶段任务链,依赖 `mob.restoreData/storeData`(每 NPC 持久化 questIndex/questVariant/questInProgress/needToGiveSpecialReward)、`showQuestWindow`、`chr:checkItem` + `wantedItem:quantity/removeItem`、`chr:getPets_l` + `mob:makePet`(宠物交付)、`collectAnimated`(奖励)、`luajava.bindClass(PlagueDoctorNPC):questCompleted()`、`hero:getHeroClass()=="DOCTOR"` 时 `me:destroy()`、`level:setCompassTarget`。**这些 API fork LuaNpc 一个都没有**(无持久化、无 quest 窗、无物品检查、无宠物)。降级只能沦为氛围对话,丢失 100% 任务身份 → 不搬。

- **Innkeeper → 不搬(推荐)**。原始是纯商人:`spawn` 时 `self:collect` 3 种食物(FriedFish/ChargrilledMeat/FrozenCarpaccio,remished 物品 fork 无)、`interact` = `showTradeWindow`、`priceForSell/sellMode/buyMode` 走 `RPD.BackpackMode.CARCASS`(买 carcass 卖食物)。
  - fork NPC `onInteract` **无法开商店**(商店是独立的 `LuaShopRegistry`/`LuaShopNpc.showShopWindow` 机制,不是 NPC interact);
  - `giveItem` 只解析 Lua 物品,无法给 remished 食物;最大降级 = "免费给一份 `remixed_full_remixed_ration` + 对话",**商人身份 100% 丢失**,变成第 7 个氛围 NPC 套个误导性名字(innkeeper 但不能交易)。
  - 结论:API 上"能跑",内容上"误导"。本 feature 主题是"选项内容升级",bishop/inquirer 的升级即价值所在;新增失真 NPC 属于 scope 蔓延。**推荐不搬,如确需 7th NPC 留作独立 task 再议**(见 Pending Issues)。

## Steps
1. **bishop.lua 升级**(保留 session 级一次性祝福守卫 `blessed[heroId]`,沿用 M17a 既有守卫语义):
   - `onInteract` 改为 `RPD.chooseOption(selfId, "主教", {"祈求祝福","请教教义","告辞"}, cb)`。
   - `choice==1`(祈求祝福):`if blessed[heroId] then showDialog("今日已祝祷过") else showDialog(祝福词); affectBuff(heroId, RPD.Buffs.Bless, 200); blessed[heroId]=true end`。
   - `choice==2`(请教教义):`showDialog(教义长文本)`。
   - `choice==3`(告辞):`npcYell(selfId, "愿圣辉与你同行。")`。
   - 顶部注释更新为 M19c 升级说明(原 chooseOption 金币交易 → 删除;现 chooseOption 3 选项:免费祝福(一次性守卫)/教义/告辞)。
2. **inquirer.lua 升级**:
   - `onInteract` 改为 `RPD.chooseOption(selfId, "调查者", {"了解问卷调查","查看隐私政策","告辞"}, cb)`。
   - `choice==1`:showDialog 说明"原 remished 会弹 Pollfish 问卷 SDK,本 fork 不接任何第三方 SDK,此处仅作氛围说明"(无 luajava/无 SDK)。
   - `choice==2`:showDialog 隐私政策长文本(沿用现 inquirer.lua 的隐私文案)。
   - `choice==3`:npcYell 告辞。
   - 顶部注释更新(原 Pollfish/showStoryWindow → 删除;现 chooseOption 3 选项:问卷说明/隐私/告辞)。
3. **测试**:RemixedFullPackTest 的 `townNpcs_useOnlyForkSupportedApis` 禁令清单**无需改动**(chooseOption/showDialog/npcYell/affectBuff 都非禁词;bishop/inquirer 也不引入 showTradeWindow/showQuestWindow/luajava/textById/spendGold/playSound/.Sfx/setAi)。NPC 数量断言仍为 6(不新增 NPC)。`./gradlew :core:test` 须全绿(已知 flaky `GeneratorLuaItemTest.luaItemProbabilityPersistsAcrossFullReset` 见 memory,仅该 1 个失败→重跑即过,非回归)。
4. **校验**:仅改 2 个 .lua;`git add` 精确到文件名,不 `git add -A`,不提交 `.claude/`。

## Acceptance
- [x] (规划阶段标注)bishop/inquirer 升级为 `RPD.chooseOption` 3 选项,无 fork 缺失 API。
- [x] (规划阶段标注)Innkeeper/PlagueDoctor 已评估:PlagueDoctor 任务 API 全缺→BLOCKED;Innkeeper 商人身份全失→不搬(理由见 Portability Assessment)。
- [ ] 实施后:bishop/inquirer 用 chooseOption,lint 绿,`./gradlew :core:test` 绿。
- [ ] 只改 Lua content/tests(本次实为只改 2 个 Lua),不改 Java API。
- [ ] 不提交 `.claude/`;不用 `git add -A`。

## Pending Issues
- Innkeeper 是否作为"降级食物 NPC(免费给 remixed_ration)"新增为 7th NPC:本 worker **推荐不新增**(失真),但属设计判断,留待 reviewer 定夺。若 reviewer 要求新增,需同步:RemixedFullPackTest 的 NPC 计数 6→7 + 加 assertTrue、禁令 lint 列表加 innkeeper.lua、tavern.json 放一个 floor pos 的 innkeeper(避免孤立注册)。
