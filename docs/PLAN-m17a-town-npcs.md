# PLAN: M17a — 城镇 NPC 补完

## Goal
从 remished `scripts/npc` 搬运 6 个能用 fork 现有 NPC API 表达的城镇 NPC(Drunkard / Bard / BlackCat / Barman / Bishop / Inquirer),降级处理 fork 不支持的 UI 交互(选项/交易/任务/故事窗口),放入 `remixed_full` 内容包,让 hub 关卡与后续 m17c 关卡有丰富 NPC。

## Context
M16 后战斗四件套(mob/item/spell/buff)已全量搬完。NPC gap 明显:remished `scripts/npc` 有 12 个,fork 只搬了 5 个(guide/sage/test_npc/town_return 等)。

**fork NPC API 边界**(已搬 NPC 全只用这 4 个,见 `remished_lite/scripts/npcs/remished_lite_guide.lua`):
- `RPD.showDialog(text)` — 对话窗口
- `RPD.npcYell(text)` — 喊话
- `RPD.giveItem(...)` — 赠物
- `RPD.leaveTown()` — 离开城镇

**remished NPC 用了 fork 缺失的 API**(调研 9 个 NPC 的 RPD 调用所得):
- `chooseOption`(选项对话框)— Barman / Bishop / Inquirer 用
- `showTradeWindow`(交易)— Innkeeper 用
- `showQuestWindow`(任务)— PlagueDoctor 用
- `showStoryWindow`(故事)— Inquirer 用
- `textById`(i18n)/ `Sfx` / `playSound` / `pourSpeck`(音效粒子)— 多个 NPC 用

**降级策略**:只搬能用 {showDialog, npcYell, giveItem, leaveTown} 表达的 NPC;选项/交易/任务/故事窗口 → 降级为 `showDialog` 单线对话;音效粒子 → 去掉。难降级的(Innkeeper 交易 / PlagueDoctor 任务 / Mercenary 雇佣)留待后续扩 API,不本批搬。

**NPC 选型(可降级,6 个)**:
1. **Drunkard**(醉汉)— 无 RPD 依赖,纯站桩对话,直接搬
2. **Bard**(吟游诗人)— 仅 Sfx/粒子,降级去特效,保留 dialog
3. **BlackCat**(黑猫)— dialog + 音效,降级音效
4. **Barman**(酒保)— chooseOption 选项,降级为单线 dialog
5. **Bishop**(主教)— affectBuff(fork 已有)+ dialog + 选项,buff 保留,选项降级
6. **Inquirer**(调查者)— showStoryWindow,降级为 dialog 长文本

## Files
预计改/新增(均在 worktree,路径相对 worktree 根):
- `core/src/main/assets/mods/remixed_full/scripts/npcs/drunkard.lua`(新)
- `.../bard.lua`(新)
- `.../black_cat.lua`(新)
- `.../barman.lua`(新)
- `.../bishop.lua`(新)
- `.../inquirer.lua`(新)
- 参考(不改):
  - fork 范本:`core/src/main/assets/mods/remished_lite/scripts/npcs/remished_lite_guide.lua`(register_npc 字段 + interact 回调写法)
  - remished 源:`../remixed-dungeon/scripts/npc/<PascalCase>.lua`(Drunkard/Bard/BlackCat/Barman/Bishop/Inquirer.lua)
- 测试(TBD,可选):`core/src/test/java/.../TownNpcsTest.java` — 断言 6 个 NPC 在 remixed_full enabled 时 register 成功、id 全局唯一

避免改动:
- 不改 `LuaNpc.java` / `LuaEngine.java`(不扩 API,纯降级搬运)
- 不改 remished 原脚本(只读参考)
- 不动其他 mod 包的内容

## Steps
1. 读 fork 范本 `remished_lite/scripts/npcs/remished_lite_guide.lua`,掌握 `register_npc` 的字段结构与 interact 回调写法(showDialog/npcYell/giveItem 的实际用法)。
2. 对 6 个 NPC,逐个读 remished 源(`../remixed-dungeon/scripts/npc/<Name>.lua`),抽出其对话文本 / 赠物 / buff 逻辑。
3. 用 fork NPC API 子集 {showDialog, npcYell, giveItem, leaveTown} 重写为 `<slug>.lua`,放 `remixed_full/scripts/npcs/`:
   - 选项菜单(chooseOption)→ 单段 `showDialog`(取最有代表性的台词,信息有损但可读)
   - 交易/任务/故事窗口 → `showDialog` 概述 + (可选)`giveItem`
   - 音效/粒子(Sfx/playSound/pourSpeck)→ 删除
   - 台词中文化(fork 约定:hardcoded ZH,fork 无 i18n `textById`)
4. 每个 NPC 文件头注释标注:源(remised NPC 名)+ 降级说明(原 X API 不支持 → 改为 Y)。
5. 扩展测试(可选):断言 6 个新 NPC 在 remixed_full enabled 时 register 成功、id 与全局(LuaNpcRegistry)不冲突。
6. `./gradlew :core:test` 绿。
7. **codex 评审**:必须 `assign("codex_reviewer", ...)`;若 assign 失败或 reviewer 不可用,**跳过该评审阶段并在最终回报告知 dispatcher 裁决,不要直接调用 codex-cli / codex exec**。

## Acceptance
- [ ] 6 个 NPC(drunkard / bard / black_cat / barman / bishop / inquirer)在 `remixed_full/scripts/npcs/` 落地,`register_npc` 成功。
- [ ] 每个 NPC 仅用 {showDialog, npcYell, giveItem, leaveTown} 子集,不依赖 fork 缺失 API。
- [ ] 降级清单(原 API → 降级为)在文件头注释 + 回报中列出。
- [ ] Innkeeper / PlagueDoctor / Mercenary 明确标注"留待(需 X API)"不本批搬。
- [ ] `:core:test` 绿;新 NPC id 全局不冲突。
- [ ] 不改 `LuaNpc` / `LuaEngine`;不提交 `.claude/`;不用 `git add -A`。

## Notes
- fork NPC 无 `chooseOption`,选项型 NPC 降级为单线对话是"信息有损但可玩"的折中,与 M6/M10 降级清单风格一致 —— 务必在回报中诚实标注信息损失。
- Mercenary 本批不碰(可能需雇佣/战斗 API)。
- NPC 关卡内放置(坐标)不在本 feature 范围 —— 由 m17c 关卡 feature 在关卡 json 里引用这些 NPC id。

---

## 细化(Worker 阶段1:核对代码后补,不覆盖上方 dispatcher 原文)

### fork NPC 真实能力边界(读 `LuaNpc.java` + `RpdApi.java` 确认)

**回调面(只有一个)**:`register_npc { ... onInteract = function(selfId, heroId) ... end }`。
LuaNpc **不** override `act()`,且无 `die`/`spawn`/`actionsList`/`execute` 回调分发。因此 remished 源里的这些回调**全部无法在 fork 表达**,降级时省略并在文件头注释说明:
- `act()`(Drunkard 的 `setState("Sleeping")`、Bard 的音符粒子)
- `die()`(BlackCat 的掉力量惩罚、Bishop 的掉半血惩罚)—— 另外 LuaNpc `damage()` 是 no-op(NPC 无敌),`die()` 永不触发,保留也无意义
- `spawn()`(setAi)
- `actionsList`/`execute`(BlackCat 的 "pet" 动作)—— fork LuaNpc 无此机制,降级并入 `onInteract`

**sprite 白名单(7 个,见 `LuaNpc.SPRITES`)**:`rat_king` / `shopkeeper` / `mirror` / `ghost` / `wandmaker` / `blacksmith` / `imp`。未知名 fallback `rat_king`。无猫/吟游/醉汉专用 sprite → 从 7 个里选最贴合的(降级折中)。

**RPD API 面(NPC 可用子集,见 `RpdApi.build()`)**:
- 核心(范本用法):`npcYell(selfId,text)` / `showDialog(selfId,text)` / `giveItem(heroId,itemId,qty)->bool` / `leaveTown()`
- buff(已有,Bishop 保留):`affectBuff(charId, RPD.Buffs.<id>, amt)`。fork buff 白名单含 `Bless`(remished 叫 `Blessed`,改名)、`Haste`、`Light`、`Barkskin`、`Invisibility` 等(FlavourBuff 的 amt 是 duration/turns)
- 其他可用(本批 NPC 无需,但不越界):`GLog`/`charHP`/`charPos`/`teleportChar`/`damageChar`/`healChar` 等

**PLAN 边界修正**:Acceptance "仅用 {showDialog,npcYell,giveItem,leaveTown}" 与 Bishop 用 `affectBuff` 冲突。实际 `affectBuff` 是 fork 已有 API(非缺失、非降级),Bishop 保留 buff 属正常用法。修正后的边界:**核心 4 个 + 已有通用 RPD 面(affectBuff/Buffs)**;`chooseOption`/`showTradeWindow`/`showQuestWindow`/`showStoryWindow`/`spendGold`/`gold()`/`uncurse`/`textById`/`Sfx`/`playSound`/`pourSpeck` 才是真·缺失,这些必须降级或删除。

### 6 NPC 逐个降级清单(原 → 降级后)+ sprite 选型

1. **drunkard.lua**(`id=remixed_full_drunkard`, sprite=`blacksmith`)
   - 源:`interact`→`self:say(phrases[random])`(4 句醉话 textById key);`act`→`setState("Sleeping")`
   - 降级:`onInteract`→`RPD.npcYell(selfId, <4 选 1 硬编码 ZH 醉话>)`。act/sleeping 无法表达,省略(注释)。
2. **bard.lua**(`id=remixed_full_bard`, sprite=`imp`)
   - 源:`interact`→`self:say("BardSong_1")`;`act`→音符粒子(`speckEffectFactory`+`pourSpeck`);`spawn`→`setAi("NpcDefault")`
   - 降级:`onInteract`→`RPD.showDialog(selfId, <硬编码 ZH 歌词>)`。粒子/act/spawn 全删(注释)。
3. **black_cat.lua**(`id=remixed_full_black_cat`, sprite=`ghost`)
   - 源:`interact`→`say("BlackCat_Phrases",random)+playExtra("sleep")`;`die`→英雄掉 1 力量+粒子+音效;`spawn`→`setAi("BlackCat")`;`actionsList`/`execute`→"pet"
   - 降级:`onInteract`→`RPD.npcYell(selfId, <3 选 1 硬编码 ZH 猫语>)`。die(反正 NPC 无敌不触发)/playExtra/pet 动作/粒子全删(注释)。
4. **barman.lua**(`id=remixed_full_barman`, sprite=`shopkeeper`)
   - 源:`interact`→`chooseOption(dialog,"Test title","Go back","Yes","No")`;index0→`hero:handle(cell(x,y-3))` 传送;index1→`glog("okay...")`(注:remished 这是 demo 残留,title="Test title",内容极薄)
   - 降级:`onInteract`→`RPD.showDialog(selfId, <硬编码 ZH 酒馆寒暄,概述酒保身份>)`。chooseOption/传送 handle/cell/glog 全删(注释)。信息有损:原"传送"功能丢失,本批仅作氛围 NPC。
5. **bishop.lua**(`id=remixed_full_bishop`, sprite=`wandmaker`)
   - 源:`interact`→`chooseOption(4 选项:小祝福100金/大祝福500金/解咒200金/退出)`,金币按难度×等级缩放;`die`→英雄掉半血上限+受伤;`spawn`→`setAi("NpcDefault")`
   - 降级:`onInteract`→`RPD.showDialog(selfId, <硬编码 ZH 神职祝福对话>)` + `RPD.affectBuff(heroId, RPD.Buffs.Bless, <duration>)`(保留祝福 buff,**去掉金币计费**,免费施加)。chooseOption/spendGold/gold/lvl/uncurse/die/粒子全删(注释)。
6. **inquirer.lua**(`id=remixed_full_inquirer`, sprite=`mirror`)
   - 源:`interact`→`chooseOption(3 选项:问卷SDK/隐私故事窗/再见)`;index0→`PollfishSurveys` Java 问卷;index1→`showStoryWindow("Inquirer_privacyPolicy")`;index2→`say(bye)`
   - 降级:`onInteract`→`RPD.showDialog(selfId, <硬编码 ZH 调查者身份 + 隐私政策长文本>)`。chooseOption/PollfishSurveys/showStoryWindow 全删(注释)。问卷 SDK 是商业无关功能,删除合理。

### 文件头注释约定(每个 .lua)
```lua
-- remixed_full <name> NPC: <一句话定位>。
-- 源: remished scripts/npc/<PascalCase>.lua(<原行为摘要>)。
-- 降级: fork LuaNpc 仅暴露 onInteract 回调 + {showDialog/npcYell/giveItem/leaveTown/affectBuff} 子集。
--   原 <X API> 不支持 → 改为 <Y>;原 <die/act/particle> 因 LuaNpc 无敌/无 act 回调 → 删除。
-- 台词硬编码 ZH(fork 无 textById i18n)。引用方式: 关卡 json mobs[] 写 "lua_npc:remixed_full_<name>"。
```

### 测试
复用 `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/` 现有模式。新增 `TownNpcsTest.java`:加载 `remixed_full` mod → 断言 6 个 NPC id 经 `LuaNpcRegistry` register 成功、id 全局唯一、每个 NPC 表只含 `onInteract`(无 act/die 等会被静默忽略的字段也行,关键是 register 不抛)。若现有测试基础设施加载 mod 成本高,降级为只断言 6 个 lua 文件 parse + register_npc 调用成功(不启完整 LuaEngine)。`:core:test` 必须绿。

### 不改清单(硬约束)
- 不改 `LuaNpc.java`/`LuaEngine.java`/`RpdApi.java`(纯内容包,不扩 API)
- 不改 remished 原脚本(只读)
- 不动其他 mod 包
- 不 `git add -A`,只 add 本 feature 的新文件 + PLAN
- Innkeeper(showTradeWindow)/PlagueDoctor(showQuestWindow,7KB 复杂任务)/Mercenary(雇佣)明确留待,本批不碰

### 阶段1 评审结论(codex_reviewer terminal d375b341,1 轮 APPROVED)
4 核对点全 PASS(fork NPC 边界 / 6 NPC 降级清单无遗漏 / Bishop affectBuff 边界修正 / 测试策略可行)。3 条 nice-to-have,采纳 #2 #3,#1 经核实是误判:
- **#1(路径笔误)—— 误判,不改**。reviewer 担心 PLAN 写 `../remished-dungeon/`,实际 PLAN 正文(第42/52行)与姐妹目录都叫 `../remixed-dungeon/`(remixed 带 x),无笔误。feature 名 `remished_full`/`remished_lite` 易与 `remixed` 混淆。
- **#2(测试改进)—— 采纳**。删 fallback(register_npc 不启引擎无此符号),改为:(a) 扩展 `RemixedFullPackTest.enabled_loadsFullAlphaManifest` 加 6 NPC contains + `assertEquals("6 npcs", 6, LuaNpcRegistry.size())`(enableRemixedFull 已 disable 其他包,remixed_full 独占);(b) 新增 forbidden-token lint 测试,读 6 个 lua 文本断言不含 `chooseOption|showTradeWindow|showQuestWindow|showStoryWindow|spendGold|uncurse|pourSpeck|playExtra|textById|Sfx|luajava|playSound|speckEffectFactory`,钉死 Acceptance"不依赖缺失 API"(风格同 `luajavaBindClassStillUnreachable`)。不新建 TownNpcsTest.java,并入 RemixedFullPackTest。
- **#3(Bishop 免费祝福可重复)—— 采纳**。去金币后每次 interact 免费 affectBuff(Bless),加 session 级一次性守卫 `blessed[heroId]`(同 guide `granted[heroId]` 模式),避免"每次下洞前白嫖祝福"。 Bless 是 FlavourBuff(duration),守卫只放一次 Bless。
