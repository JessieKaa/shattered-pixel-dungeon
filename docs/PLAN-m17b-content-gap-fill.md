# PLAN: M17b — 内容补漏(remixed_fetid_rat + trap 甄别)

## Goal
两件收尾补漏:(1) 把 remished 唯一未搬的战斗 mob `remixed_fetid_rat` 落到 `test_mod`;(2) 从 10 个 gap trap 里甄别搬运"内容型"陷阱(避开系统机制),补 trap 内容。

## Context
M16 全量盘点了 remished 搬运 gap:mob/item/spell/buff 已搬完,真实 mob gap 仅 `remixed_fetid_rat`(M6a PoC 解剖样本,46 行,未正式落脚本);trap 是最大结构 gap(remished 12 / fork 2)。

**fetid_rat 调研**:`../remixed-dungeon/scripts/mobs/RemixedFetidRat.lua`(46 行),用 `RPD.Blobs` / `RPD.Buffs` / `RPD.glog` / `RPD.placeBlob` / `RPD.Sfx`。fork 已有 Blobs / Buffs / glog / placeBlob;Sfx 缺 → 降级去音效。可搬。

**trap 甄别**(调研 remished `scripts/traps` 10 个 gap,预判):
| trap | 行数 | 类型预判 |
|---|---|---|
| Chasm / Fall | 19 / 16 | 系统机制(坠落) — 不搬 |
| Counter | 28 | 系统机制(计数) — 不搬 |
| CutScene | 36 | 系统机制(过场) — 不搬 |
| LevelActor | 15 | 系统机制(关卡角色) — 不搬 |
| Message | 16 | 系统机制(提示) — 不搬 |
| Sandbox | 23 | 测试沙盒 — 不搬 |
| Spawner | 41 | 系统机制(刷怪器) — 不搬 |
| DemoTransmute | 58 | 炼金演示 — 不搬 |
| **Disarm** | 49 | **内容型候选**(解除武装陷阱) |
| **Drain** | 18 | **内容型候选**(抽取陷阱) |
| **SpecialBeacon** | 25 | **内容型候选**(信标/传送) |

(上表为调研预判。**worker 实施前必须读每个候选 trap 源码确认其语义**,再定搬/不搬 —— 若候选读后判定为系统机制,诚实剔除。)

## Worker 调研结论(读源码 + fork API 核对后)

### fetid_rat —— 可搬(一处适配)
源 `RemixedFetidRat.lua`:3 变体(paralytic/confusion/toxic),stats() 随机选 kind 并 `mob.storeData` 持久化,act() 每帧 placeBlob 对应气体,addImmunity 对应免疫。`kinds[].speck`(RPD.Sfx)只赋值从不读取 → 直接删字段,无降级副作用。

**适配点**:fork `register_mob` 回调仅 `spawn/act/attackProc/defenseProc/die`,**无 `storeData/restoreData`**(LuaMob.storeInBundle 只持久化 `lua_mob_id`/`lua_spawned`/`lua_immunity_classes`/`stolen_loot`,不持久化任意 Lua 数据)。故随机 kind 跨存档会丢。
**适配方案**:kind 由 actor id 确定性派生 `(selfId % 3) + 1`(id 跨存档稳定,无需持久化);免疫在 `spawn(selfId)` 用 `RPD.addImmunity` 加,LuaMob 已持久化 `luaImmunityClassNames` 并在 restore 重建。行为等价(三变体、各自气体、各自免疫),仅把"随机+持久化"换成"id 派生"。

API 核对(均在 fork 白名单):
- Blobs:`ParalyticGas`/`ConfusionGas`/`ToxicGas` ✓(BlobRegistry 14 项含此三者)
- Buffs:`Paralysis`/`Vertigo` ✓(BuffWhitelist);kind3 免疫用 `Blobs.ToxicGas`(AddImmunity 先查 BlobRegistry 再查 BuffWhitelist,ToxicGas 命中)✓
- `RPD.placeBlob(blobId, pos, amount)` / `RPD.charPos(selfId)` / `RPD.addImmunity(charId, id)` ✓

### trap 候选 —— 3 个均为内容型,但 fork API 不足,不可忠实移植(0 trap)
读源码确认 Disarm/Drain/SpecialBeacon **语义上都是玩家可踩的内容型陷阱**(有触发+效果),不是系统机制;但 fork RPD 缺必要 API,在"不改 fork Java"约束下无法忠实搬运:

| 候选 | 内容语义 | 缺失的 fork API | 降级可行性 |
|---|---|---|---|
| **Disarm** | 解除英雄装备(武器/护甲/戒指)+ 背包物品散落周边格 | 无装备槽访问(`belongings.weapon/armor/ring1/ring2/leftHand`)、无 `unequip`/`removeItemFrom`;`dropItem(cell,id,qty)` 是按 id **新建**物品而非搬运现有实例(会丢升级/附魔/诅咒) | 否 —— 仅丢背包物品+新建物品=语义错乱,不再是"解除装备"陷阱 |
| **Drain** | 英雄 −1 力量(永久) | 无 STR getter/setter | 否 —— 用 damageChar/debuff 模拟会变成"掉血/临时减速",丢失"永久掉力量"身份 |
| **SpecialBeacon** | 把关卡内指定类名 mob 全部 beckon 到本格 | 无 mob 列表迭代、无 `beckon`;且 fork `register_trap` 的 `onActivate(cell,charId)` **无 data 参数**(LuaTrap 只持久化 `lua_trap_id`),`data`=nil 时 `mobClassName==nil` 匹配空 → 空操作 | 否 —— 无 data 即 no-op |

**结论**:`Notes` 的 escape clause 覆盖此情形(0-1 trap、不硬凑)。诚实只搬 fetid_rat,0 trap。若 dispatcher 后续要补 trap 内容,需先在 fork RPD 扩 API(独立 milestone,不在本 feature 范围)。

## Files
- 新增:`core/src/main/assets/mods/test_mod/scripts/mobs/remixed_fetid_rat.lua`(从 remised `RemixedFetidRat.lua` 适配:id 派生 kind、删 Sfx、中文化 name)
- 不新增 trap 文件(3 候选 API-blocked,见上)
- 参考(不改):
  - 既有写法:`test_mod/scripts/mobs/test_blob_rat.lua`(M6a 单气体 PoC,本 port 的三气体泛化)、`rat.lua`(register_mob 最小骨架)
  - remished 源:`../remixed-dungeon/scripts/mobs/RemixedFetidRat.lua`
- 测试:`./gradlew :core:test`(既有 LuaMobRegistry/LuaTrapRegistry 加载测试覆盖 register 无冲突;不新增测试——脚本无新 Java 路径)

避免改动:
- 不改 fork Java(`register_mob`/`register_trap` API 已就绪)
- 系统机制型 trap 不搬;内容型但 API-blocked 的 trap 不搬(诚实,不硬凑)

## Steps
1. 写 `test_mod/scripts/mobs/remixed_fetid_rat.lua`:
   - `local KINDS = { {blob=ParalyticGas, immunity=Paralysis}, {blob=ConfusionGas, immunity=Vertigo}, {blob=ToxicGas, immunity=ToxicGas} }`(用 `RPD.Blobs.*` / `RPD.Buffs.*` 常量)
   - `local function kindOf(selfId) return (selfId % 3) + 1 end`(确定性派生,替代原 random+storeData)
   - `register_mob { id="remixed_fetid_rat", name="腐臭鼠", hp=20, ht=20, attack=8, defense=3, sprite="rat", spawn=function(selfId) RPD.addImmunity(selfId, KINDS[kindOf(selfId)].immunity) end, act=function(selfId) RPD.placeBlob(KINDS[kindOf(selfId)].blob, RPD.charPos(selfId), 50); return false end }`
   - 文件头注释:源(remised `RemixedFetidRat.lua`)+ 适配说明(id 派生替 storeData;Sfx 删除;fork register_mob 无 desc 字段故只设 name)
2. trap 候选(Disarm/Drain/SpecialBeacon)已读源码 + 核对 fork API,三者均 API-blocked(详见上节),**不搬**。在回报列明每个的缺失 API。
3. `./gradlew :core:test` 绿(确认新脚本 register 不破坏既有测试)。
4. **codex 评审**:必须 `assign("codex_reviewer", ...)`;若 assign 失败或 reviewer 不可用,**跳过该评审阶段并在最终回报告知 dispatcher 裁决,不要直接调用 codex-cli / codex exec**。

## Acceptance
- [ ] `remixed_fetid_rat` 在 `test_mod` 落地,register 成功;API 仅用 fork 已有子集(Blobs/Buffs/placeBlob/addImmunity/charPos);kind 由 id 派生(无 storeData 依赖);Sfx 删除。
- [ ] 0 个 trap 落地(3 候选均为内容型但 API-blocked,已在 Portability Assessment 列理由);系统机制型不搬。
- [ ] `:core:test` 绿;新 id `remixed_fetid_rat` 全局不冲突(与既有 `rat`/`test_blob_rat` 不同名)。
- [ ] 不改 fork Java;不提交 `.claude/`;不用 `git add -A`。

## Pending Issues
(阶段1/2 第 3 次仍未决时追加于此)

## Notes
- 若 3 个 trap 候选读源码后发现全是系统机制/测试(非内容),则**诚实只搬 fetid_rat + 0-1 trap**,在回报说明 —— 不硬凑数量。
- `remixed_fetid_rat` 放 `test_mod`(与其他 Remished mob 同包,它是 Remished 移植主力);不放 `remixed_full`(那是精选 alpha 内容包)。
