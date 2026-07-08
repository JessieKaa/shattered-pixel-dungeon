# PLAN: M10a-spell — 24 个 Remished spell 脚本改写

## Goal
改写剩余 24 个 Remished spell 脚本到 SPD fork Lua API(M6d 移了 8,`remixed-dungeon/scripts/spells/` 共 32)。

## Context
M6d 移植 8 个 spell 样本(heal/haste/charm/lightning_bolt/town_portal/summon_beast/raise_dead/sprout),建立 spell 改写模式(`LuaSpell` + `register_spell` + M6d spell API + M7c targeting + M7d mana)。M10a-spell 补剩余 24 个。

**第一件事**:列剩余 24 个 spell —— 读 `../remixed-dungeon/scripts/spells/*.lua`(32 个)对比 M6d 已移的 8 个,列未移的 24 个。

**约束**:
- 基于 M6-M8 API(`LuaSpell` + `register_spell` + M6d spell API:`teleportChar`/`charAtCell`/`cellRay`/`zapEffect`/`spawnMobNear` + M7c targeting:`selectCell`/`onUseAt` + M7d mana:`spellCost`)
- 遇到需 M10b(关卡)或 M10c(buff)的 spell,**标注降级**。**不 [BLOCKED]**。
- 资产依赖(effect sprite/icon):优先复用 SPD;缺失则降级 + 标注
- mana 消耗:用 `spellCost`(M7d),默认值参考 remished

## Files
- `core/src/main/assets/mods/test_mod/scripts/spells/*.lua`(新 24 个;PLAN 原写 `assets/...`,实际在 `core/src/main/assets/...`)

## Steps
1. **列剩余 24 spell**(对比 remixed vs M6d 已移 8)
2. **逐个改写**:读 remixed `spell.lua` → 翻译到 SPD LuaSpell API(`register_spell` + spell API + targeting + mana)。参考 M6d 已移的 8 个作模板。
3. **targeting**:用 M7c `selectCell`/`onUseAt`(自瞄/手瞄)
4. **mana**:`spellCost`(M7d),参考 remished 默认
5. **资产**:复用 SPD effect sprite 或降级
6. **测试**:注册 + cast + effect(M7c targeting;需 M10b/c 的标注降级)

## Acceptance
- [ ] 24 spell 脚本注册成功(`register_spell`)
- [ ] cast 工作(targeting M7c + effect)
- [ ] mana 消耗(M7d spellCost)
- [ ] `./gradlew :core:test` 全绿(458 现有)
- [ ] **降级清单**(M10b/c 依赖 + 资产缺失)记录在 PLAN 末尾

## 注意
- **绝不 `git add -A`**
- **codex 评审用 codex exec workaround**
- **遇 M10b/c 缺:标注降级,不 [BLOCKED]**
- 新 spell 集中 `test_mod/scripts/spells/`(参考 M6d 模板)

## 实施记录

### 24 个脚本清单(32 remished - 8 M6d 已移)
22 个 `register_spell` + 2 个数据 stub(不注册):

| # | 文件 | id | targeting | 降级说明 |
|---|------|----|-----------|----------|
| 1 | anesthesia.lua | anesthesia | enemy | Anesthesia 自定义 buff → 纯 Sleep;skill 缩放丢 |
| 2 | backstab.lua | backstab | enemy | 无最近敌/武器伤害 API → 定点固定伤害 |
| 3 | blood_transfusion.lua | blood_transfusion | enemy | 无 owner 检查 → 敌方抽血回血 |
| 4 | body_armor.lua | body_armor | self | BodyArmor 自定义 buff → Barkskin |
| 5 | calm.lua | calm | enemy | Sleep;skill 缩放丢 |
| 6 | cloak.lua | cloak | self | Cloak 自定义 buff → Invisibility;敌近检查丢 |
| 7 | corpse_explosion.lua | corpse_explosion | cell | 无 heap/Carcass API(M10b)→ 直接 placeBlob |
| 8 | curse_item.lua | curse_item | self | 无 item-selector API → 零消耗 stub(GLogW 提示) |
| 9 | custom_spells_list.lua | —(数据) | — | affinity 元数据表,不注册 |
| 10 | dark_sacrifice.lua | dark_sacrifice | enemy | 无 owner 检查;LiquidFlame→Fire |
| 11 | dash.lua | dash | cell | 无 Ballistica/push/AoE(M10b)→ blink+单体伤害 |
| 12 | die_hard.lua | die_hard | self | DieHard 自定义 buff → Barkskin |
| 13 | exhumation.lua | exhumation | cell | 无 heap/TOMB API(M10b)→ spawnAlly("test_ally") 友方占位 |
| 14 | hide_in_grass.lua | hide_in_grass | self | 无 terrain API(M10b)→ 无条件 Invisibility |
| 15 | kunai_throw.lua | kunai_throw | enemy | 无随机敌 API → 单目标 |
| 16 | magic_arrow.lua | magic_arrow | enemy | zap+伤害;ht 缩放丢 |
| 17 | nature_armor.lua | nature_armor | self | Barkskin;skill 缩放丢 |
| 18 | order.lua | order | enemy | 无 mob-command API → 零消耗 stub |
| 19 | possess.lua | possess | enemy | 无附身/ControlledAi API → 零消耗 stub |
| 20 | roar.lua | roar | enemy | 无随机敌 API → 单目标 Terror |
| 21 | shoot_in_eye.lua | shoot_in_eye | enemy | Blindness+伤害;武器伤害丢 |
| 22 | smash.lua | smash | enemy | 无 AoE(M10b)→ 单体 Vertigo+伤害 |
| 23 | spells_by_affinity.lua | —(数据) | — | helper module,不注册 |
| 24 | remished_test_spell.lua | remished_test_spell | self | TestSpell 占位(id 与 M6d test_spell 区分) |

### 降级清单(M10b/c 依赖 + 资产/ API 缺失)

**M10b(level/heap/terrain/AoE)依赖:**
- corpse_explosion — 需 heap/Carcass 检测 → 降级直接放气
- exhumation — 需 heap/TOMB/SKELETON → 降级 spawnMobNear
- dash — 需 Ballistica/push/forCellsAround → 降级 blink+单体
- smash — 需 forCellsAround AoE → 降级单体
- hide_in_grass — 需 terrain map → 降级无条件

**M10c(custom buff)依赖:**
- anesthesia — Anesthesia(痛觉屏蔽)buff → 降级纯 Sleep
- body_armor — BodyArmor buff → 降级 Barkskin
- die_hard — DieHard(减伤)buff → 降级 Barkskin
- cloak / hide_in_grass — Cloak buff → 降级 Invisibility

**API 缺失(未规划里程碑):**
- curse_item — item-selector UI + item.cursed
- order — makePet + mob-execute(命令通用 mob)
- possess — makePet + ControlledAi + setControlTarget
- backstab / kunai_throw / roar — visibleEnemies/randomEnemy 敌查询 + belongings.weapon:damageRoll

**资产:**
- spell icon 复用 SPD items/spritesheet(image 0-3 索引,无新资产)
- effect sprite 依赖已降级(zapEffect 占位,无 custom particle)

**blob 白名单替换:**
- LiquidFlame → Fire(dark_sacrifice)
- MiasmaGas → 丢弃,ParalyticGas 顶替(corpse_explosion)

### 测试更新
- `LuaSpellTest.m6dRepresentativeSpellsRegisteredByEngineInit`:size 9 → 31
- `LuaSpellTest.m10aSpellsRegisteredByEngineInit`:新增,断言 22 个新 id 注册
- `ModToggleRegressionTest`:size 9 → 31
