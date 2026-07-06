# PLAN: M6e — M6 平衡收尾 + C3 全量回归

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M6(line 165 M6e 条目)
> 前置:M6a / M6-fast / M6b / M6c / M6d 全部已合 master(merge HEAD `6b07c32e5`,`:core:test` 271 tests 绿)
> **D5 = (a) B 全量;D5' = (a) 禁 luajava**(全程守住)
> 本 feature 是 M6 收尾:**不新增 Remished 内容**,而是 (1) 验证累积内容不破 C3,(2) 处置 M6c/M6d PLAN 预测的平衡风险,(3) 闭合 M6 路线图

## Goal

M6 全量收尾:(1) 验证 M6a-d 累积内容在 mod 开/关下都守住 **C3**(原版一周目零污染);(2) 处置 M6c/M6d PLAN 末尾预测的平衡风险清单(`giveItem` 刷分 / LuaMaterial 池归属 / combat hook 降级决策等);(3) 更新 roadmap 把 M6 状态从 `[ ]` 翻 `[x]` + changelog/评估附录归档。

## Context

### M6 已交付(master `6b07c32e5`)

| 子里程碑 | 内容 | 平衡/降级状态 |
|---|---|---|
| M6a 桥子集 | 5 surface(`placeBlob`/`addImmunity`/`Blobs`/`Buffs`/LuaMob `spawn`)+ LuaMob spawn 回调 | PoC,默认不进生成池 |
| M6-fast | 3 材料 C-path 数据皮 | 已被 M6d 改 `type=material` 修正结构错配 |
| M6b mob PoC | 6 mob(`shaman_elder`/`spider_elite`/`deep_snail`/`hydra`/`maze_shadow`/`buffer`)+ 5 primitive(`setMobAi`/`enemyOf`/`cellDistance`/`emptyCellNextTo`/`blink`)+ `Ooze` | PoC,默认不进生成池 |
| M6c buff | `LuaBuff`/`LuaBuffRegistry`/`register_buff` + 16 buff 脚本 + `affectBuff`(透传 Lua id)/`removeBuff`/`detachBuff`/`permanentBuff`/`setBuffLevel`/`buffLevel` | **4/16 高保真,12/16 降级** |
| M6d item/spell | `LuaMaterial` + 2 weapon 代表(`hooked_dagger`/`kunai`)+ 8 spell + 11 item/spell API(`giveItem`/`randomBackpackItem`/`itemName`/`removeBackpackItem`/`stealRandomItem`/`stolenLootName`/`teleportChar`/`charAtCell`/`cellRay`/`zapEffect`/`spawnMobNear`) | item 5/22,spell 8/32 |

### M6c 的 M6e 预测(已回填 `docs/PLAN-modding-m6c-buffs.md`)

- **高保真 4/16**:`GasesImmunity`/`Counter`/`Cloak`/`ChampionOfEarth`(一次性 heal)
- **降级 12/16**:`defenceProc`/`attackProc`/`drBonus`/`speedMultiplier`/`stealthBonus`/`charAct`/`regenerationBonus`/`hasteLevel`/`sprite glow`/`source-item shield` —— Remished-only hook 未接 SPD `Char`/`Mob` 核心
- 平衡风险**中高**:多数战斗强度 hook 未接入;若追高保真需设计 **source-aware combat hooks**;若做内容包可先用现有 lifecycle + Java whitelist 调参

### M6d 的 M6e 预测(已回填 `docs/PLAN-modding-m6d-items-spells.md`)

- item 5/22,spell 8/32
- 平衡风险清单:
  - **`giveItem` 无 cooldown 可刷**(Lua 脚本可无限给 hero 物品)
  - **stolen loot 不持久化**(`Mob.storeInBundle` 不存 loot 字段)
  - **summon/raise_dead 复用 `test_mob` 无专属 sprite**
  - **targeting UI 全 self 占位**(spell cast 无真正目标选择)
  - **LuaMaterial 进 LUA_ITEM 池会与武器混掉**(生成池未按 type 分类)

## Files

- `docs/MODDING-ROADMAP.md`:M6 状态 `[ ]`→`[x]`(line 157 + line 210 状态总表);§8 changelog(line 225-)加 M6b/M6c/M6d/M6e 行;§9 加 M6 全量完成度附录(9.6)
- `core/src/main/java/.../modding/RpdApi.java`:`GiveItem` 加 per-hero-per-depth 配额 + `resetGiveQuota()` 测试钩子(M6d 平衡风险 #1)
- `core/src/main/java/.../modding/LuaItemRegistry.java`:新增 public `isMaterial(String id)`(复用既有 private `isMaterial(LuaTable)`)
- `core/src/main/java/.../modding/LuaItemPool.java`:`random()` 默认 weapons-only(跳过 material id,M6d #5)
- `core/src/test/java/.../modding/RpdApiItemSpellTest.java`:`giveItem` 配额单测(超限 → false;reset 钩子)
- `core/src/test/java/.../modding/GeneratorLuaItemTest.java`:新增"材料不出现在默认 random() 池"断言
- `core/src/main/assets/mods/test_mod/mod.json`:`default_enabled=false`(既有,不动 — C3 守卫)
- `core/src/test/java/.../modding/ModToggleRegressionTest.java`:既有 8 registry 断言保持绿(M6c/d 已含 buff registry;无需改)
- `docs/PLAN-modding-m6c-buffs.md` / `docs/PLAN-modding-m6d-items-spells.md`:末尾"M6e 预测"段标注处置结果

## Steps

> **M6e 决策总览(worker 细化,2026-07-06)**:2 项轻修 + 4 项接受降级/留 M7。守 C3 / D5' / scope。
> - **修**:`giveItem` per-hero-per-depth 配额(#1)、`LuaItemPool.random()` 默认排除 material(#5)
> - **接受降级/留 M7**:stolen loot 持久化(#2,LuaMob 已 override bundle,M7 廉价补)、summon/raise_dead 专属 sprite(#3,资产缺口)、targeting UI 真实目标选择(#4,UI 工作)、M6c combat hook 12/16(选 (a),source-aware Char/Mob 全局 hook = M7 级)

1. **C3 全量回归**(核心,必做):
   - 跑 `ModToggleRegressionTest`(已覆盖 8 registry disabled=0/enabled=full);确认 M6a-d 累积后仍绿
   - 验证 `test_mod/mod.json` `default_enabled=false` 仍是 false(C3 守卫未被动)
   - 既有 `GeneratorLuaItemTest` 4 测试在 LuaItemPool 改动后仍绿(weapons-only 池仍有 ≥8 武器,≥2 distinct 满足)

2. **平衡风险处置**(逐项决策结果):
   - **`giveItem` 刷分(#1)= 修**:`GiveItem` 加 per-hero-per-depth 累计配额(`GIVE_ITEM_CAP_PER_DEPTH = 20`),fork-local static `Map<heroId, Map<depth, count>>`,超限 → `valueOf(false)`(区别于 bad-input 的 NIL);不触 Hero 字段/不进 bundle;`RpdApi.resetGiveQuota()` 测试钩子
   - **LuaMaterial 池分类(#5)= 修**:`LuaItemRegistry` 暴露 public `isMaterial(String id)`;`LuaItemPool.random()` 默认 weapons-only(跳过 material id);`Generator.randomLuaItem()` 复用默认路径 → 材料 no longer 从 LUA_ITEM 掉落
   - **combat hook 降级决策(M6c 12/16)= 选 (a) 接受降级**:`defenceProc`/`attackProc`/`drBonus`/`speedMultiplier`/`stealthBonus`/`charAct`/`regenerationBonus`/`hasteLevel`/`sprite glow`/`source-item shield` 需 source-aware Char/Mob 全局 hook = M7 级(>1 天);12 buff 保留 metadata + lifecycle 可测行为;`Charm`/`Terror` 未进 whitelist(source id 语义,M7)
   - **stolen loot 持久化(#2)= 接受降级,留 M7**:`Mob.storeInBundle` 不存 generic Item loot;LuaMob 已 override bundle,M7 廉价补(存 STOLEN_LOOT key + createLoot 兼容);thief 闭环还需 stolen-item 返回 UI(M7)
   - **summon/raise_dead sprite(#3)= 接受降级,留 M7**:复用 test_mob,需 skeleton/zombie sprite + 平衡数据(资产缺口,非 M6e 范围)
   - **targeting UI(#4)= 接受降级,留 M7**:spell cast 全 self 占位,真实目标选择需新 UI 工作(M7)

3. **M6 路线图闭合**:
   - roadmap line 157 M6 状态 `[ ]` → `[x]`
   - line 210 状态总表 M6 行翻 `[x]`,备注更新为"M6a-e 全合并"
   - §8 changelog 加 M6c/M6d/M6e 三行
   - §9 评估附录:M6 全量完成度(mob 7、buff 4 高保真 + 12 降级、item 5/22、spell 8/32)+ luajava 禁下摩擦点总结 + D5/D5' 守住确认

4. **测试**:跑 `./gradlew :core:test --no-daemon`;若新增 `BalanceRegressionTest`,确认绿;既有 271 tests 不下降

5. **回填 M6c/M6d PLAN**:在两份 PLAN 末尾"M6e 预测"段标注每项风险的处置结果(已修 / 接受降级 / 留 M7)

## Acceptance

- [x] **C3 守住**:test_mod disabled 时 8 registry 全空,原版一周目不受 M6 内容影响(`ModToggleRegressionTest` 绿 + `mod.json` `default_enabled=false` 确认)
- [x] M6d 平衡风险清单 5 项每项有明确处置(修 #1/#5 / 接受降级留 M7 #2/#3/#4),不得"未处理"
- [x] M6c combat hook 降级决策明确(选 (a) 接受,理由:source-aware hook 工时 >1 天,M7 级)
- [x] roadmap M6 状态翻 `[x]`,changelog + 评估附录(§9.6)归档
- [x] `:core:test` 通过(273 tests = 271 baseline + 2 新),既有 271 tests 不回归
- [x] M6c/M6d PLAN 末尾风险段回填处置结果

## M6 总结(完成后回填)

- M6 全量交付 Lua-facing surface 数:**~45**(M6a 5 surface + M6b 5 primitive + M6c 6 buff API + M6d 11 item/spell API + LuaMaterial/LuaBuff wrapper + spawn/act 回调槽)
- mob / buff / item / spell 完成度:**7 / ~18**(mob,default 不进生成池)、**16/16**(buff,4 高保真 + 12 降级)、**5 / ~22**(item)、**8 / ~32**(spell)
- 降级接受项(留 M7):M6c combat hook 12/16(source-aware Char/Mob 全局 hook)、M6d stolen loot 持久化、summon/raise_dead 专属 sprite、spell targeting UI、`Charm`/`Terror` whitelist
- D5' (禁 luajava) 是否在 M6 全程守住:**是**(无任何 `scripts/lib/` / luajava 引入;Lua 仅返回 bool/int/string/nil/table,不持 Java 对象句柄)
- M6 总 feature 数(M6a/M6-fast/M6b/M6c/M6d/M6e):**6**

## Risks

- 平衡风险 #3 combat hook 降级决策若选 (b) 引入 source-aware hook,工时可能超 1 天 → 触发"M6e 是否拆 M6e1/M6e2"评估;默认走 (a) 接受降级
- `giveItem` cooldown 实现若改 Hero 状态字段,注意 bundle 持久化(transient vs persisted)
- `LuaItemPool`/`Generator` 若结构改动,回归既有 weapon/test item 生成(`DataSkinItemTest`/`GeneratorLuaItemTest`)
- 守 fork 约束:新代码进 `modding/` 子包,不散回上游根包
- C5 proguard:cooldown 字段若无反射不需 keep
