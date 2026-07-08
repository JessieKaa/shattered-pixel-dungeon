# PLAN: M10a-item — 15 个 Remished item 脚本改写

## Goal
改写剩余 Remished item 脚本到 SPD fork Lua API(M6d 移了 5,`remixed-dungeon/scripts/items/` 共 22)。

## Context
M6d 移植 5 个 item 样本(hooked_dagger/kunai/bone_shard/rotten_organ/toxic_gland),建立 item 改写模式(`LuaItem` weapon wrapper + `LuaMaterial` plain item + `register_item` + M6d API:`giveItem`/`randomBackpackItem`/`itemName` 等)。M10a-item 补剩余真实 item。

**计数对账(15 vs PLAN 原写 17)**:`../remixed-dungeon/scripts/items/` 实有 21 个 `.lua`(maxdepth 1)+ `test/TestItem.lua` = 22 文件。减去 M6d 已移 5(BoneShard/HookedDagger/Kunai/RottenOrgan/ToxicGland)= 17,但其中:
- `ArmorTemplate.lua` —— 代码模板(返回 placeholder image),非游戏 item,**跳过**
- `test/TestItem.lua` —— 引擎 API 测试夹具(BitmapData/action dispatch/pcall 测试),非游戏 item,**跳过**

→ 实际需改写的 **真实 item = 15**。已在回报里向 dispatcher 说明;若要求凑满 17,再补 2 个占位即可,不影响主线。

## API 现状(决定降级面)
`register_item` 二分派(`LuaItemRegistry.createItem`):
- `type/kind = "material"` → `LuaMaterial`(可堆叠 plain Item:`id/name/desc|info/image/price/quantity/stackable`)。**无 eat/use/throw 回调**。
- 否则 → `LuaItem extends MeleeWeapon`(`id/name/desc/tier/image` 必填 + 回调 `attackProc(attackerId,defenderId,baseDamage)→int` / `onEquip(heroId)` / `onDeactivate(heroId)`)。

**不存在**:`LuaArmor` / item 级 `drBonus` / `defenseProc` / `left_hand`/artifact 槽 / 食物 eat 派发 / `damageRoll`/`accuracyFactor` 自定义公式 / per-instance Lua 状态字段(`self.data`)。这些 = **M10c 依赖**,标注降级。

**关于 `register_spell`/`LuaSpell`(不在本 feature 用)**:fork 另有 `register_spell`→`LuaSpell`(`extends Item`,有 `USE` action + `onUse(heroId)` + `useMode` `"consumable"`(detach-on-use)/`"mana"`(spend MP) + `castTime`/`hero.busy`,见 `LuaSpell.java:99`)。**不用于 fish/tengu_liver**,理由:(1) supervisor 本 feature API 范围是 `LuaItem`+`register_item`(+M6d/M7b),未含 spell;(2) LuaSpell 带 cast 语义(`castTime`/`busy`/独立 `LuaSpellRegistry`)——fish 是可堆叠食物掉落,落 spell 槽/注册表会改掉落语义;(3) eat 真正落地需 Hunger API(M10c+)。故 fish 走 `LuaMaterial`(可堆叠、按价定价),eat 标降级。

**Buff id 约定**(mask 回调用):`RPD.affectBuff/permanentBuff/removeBuff` 取 **注册 `id`(snake_case)**,非显示 `name`。如 `gases_immunity` buff:注册 id = `gases_immunity`,显示 name = `GasesImmunity` —— 回调必须传 `"gases_immunity"`。

RPD API 可用(选):`affectBuff/removeBuff/permanentBuff`、`damageChar/healChar`、`GLog/GLogW`、`charHP/charPos/charName`、`giveItem/itemName/removeBackpackItem`、`addShield/charShield/absorbShield`、`yell`、`Buffs/Blobs` 常量表。

## Files
- `core/src/main/assets/mods/test_mod/scripts/items/*.lua`(新 15 个,snake_case 文件名,对齐 M6d)
- `core/src/test/java/.../modding/M10aItemsRegisteredTest.java`(新 1 个注册冒烟测试)
- `core/src/test/java/.../modding/ModToggleRegressionTest.java`(**改 1 行** :145 — `LuaItemRegistry.size()` 11 → 26,文案改 "10 + 15 M10a item dir scripts + 1 entry item"。是唯一硬编码 item 计数的断言;其余 size 断言为 init 前 0 或动态 `itemsAfterFirst`)

## 15 个 item 改写清单

| 文件名 | 原件 | 类型 | tier/price | 可移植回调 | 降级(M10c/资产) |
|---|---|---|---|---|---|
| `bone_saw.lua` | BoneSaw | weapon | tier 3 | `attackProc`→`RPD.affectBuff(defender,"Bleeding",3)` | `damageRoll/accuracyFactor/attackDelayFactor` 自定义公式→tier 基线;Doctor 暴击+采集掉落+麻痹加成(无 heroClass/itemFactory API) |
| `remixed_pickaxe.lua` | RemixedPickaxe | weapon | tier 3 | `attackProc` 返 base(占位) | 挖矿 `actions/execute`(ACMine + terrain 改写 + DarkGold 掉落)、`self.data.bloodStained` per-instance 状态、`glowing`、STR 检查 say |
| `tomahawk2.lua` | Tomahawk2 | weapon | tier 2 | — | `left_hand` 投掷槽、stackable 投掷语义 |
| `plague_doctor_mask.lua` | PlagueDoctorMask | weapon(占位) | tier 1 | `onEquip`→`RPD.permanentBuff(hero,"gases_immunity")` / `onDeactivate`→`RPD.removeBuff(hero,"gases_immunity")` | **artifact 槽**(无 LuaAccessory);`Accessory.equip` Java 桥;当前落 weapon 槽(语义降级) |
| `wooden_shield.lua` | WoodenShield | weapon(占位) | tier 1 | — | **drBonus/格挡**(M10c);`left_hand` 槽;`shields.makeShield` blockChance/blockDamage/recharge;image 用占位 |
| `tough_shield.lua` | ToughShield | weapon(占位) | tier 2 | — | 同上(level=2) |
| `strong_shield.lua` | StrongShield | weapon(占位) | tier 3 | — | 同上(level=3) |
| `royal_shield.lua` | RoyalShield | weapon(占位) | tier 4 | — | 同上(level=4) |
| `chaos_shield.lua` | ChaosShield | weapon(占位) | tier 3 | — | 同上 + `ownerTakesDamage/ownerDoesDamage` 充能升级/降级 + 动态 image(M10c) |
| `raw_fish.lua` | RawFish | material | price 7 | — | `eat`(Poison + Hunger)、`burn/freeze/poison` 物品转换、`defaultAction` |
| `fried_fish.lua` | FriedFish | material | price 30 | — | `eat`(Hunger STARVING)、`poison`→RottenFish 转换 |
| `frozen_fish.lua` | FrozenFish | material | price 15 | — | `onThrow`(水里召唤 Piranha)、`burn`→FriedFish 转换 |
| `rotten_fish.lua` | RottenFish | material | price 0 | — | `eat`(Poison + Hunger/4) |
| `tengu_liver.lua` | TenguLiver | material | price 0 | — | `eat`(选子类 GUARDIAN/WITCHDOCTOR)、`onPickUp` Badge |
| `vile_essence.lua` | VileEssence | material | price 10 | — | `glowing`(makeGlowing) |

**盾类统一处理**(supervisor 指示"基础属性先写,drBonus 回调标注降级"):注册为 weapon-type LuaItem,`tier = shieldLevel`(占位基础属性),在 Lua 表里声明 `shieldLevel`/`price` + 一个 `drBonus` 函数字段(标 `-- DEGRADED: needs M10c`,当前不被 Java 调用 = 惰性无害),M10c 的 armor 桥接落地后直接读这些字段。

**image**:SPD `items.png` 单图集(无 remished `shields.png/food.png/swords.png`)。每项给一个合理 int 索引(无校验,降级占位),在文件头注资产降级。

## Steps
1. **(done) 对账 15 item**,确认 M6d 5 个已移、ArmorTemplate/TestItem 跳过。
2. **逐个改写**:按上表。weapon 走 `register_item{id,name,desc,image,tier, [attackProc/onEquip/onDeactivate]}`;material 走 `register_item{id,type="material",name,desc,image,price,stackable}`。中文 name(M6d 先例:"苦无/钩刃匕首")。
3. **盾类**:tier=level + `drBonus` 降级字段 + 头注。
4. **测试**:`M10aItemsRegisteredTest` —— `LuaEngine.init()` 后断言 15 id 全注册 + name 不以 "???" 开头 + 材料分类正确(fish/vile_essence/tengu_liver 是 material;shields/weapon/mask 不是)。**另改** `ModToggleRegressionTest.java:145` item 计数 11 → 26(唯一硬编码 item 计数断言)。
5. **回归**:`./gradlew :core:test` 全绿(现有 458 + 新增 1 类)。`GeneratorLuaItemTest` 自动覆盖新 item 入池。

## Acceptance
- [ ] 15 item 脚本 `register_item` 成功(测试断言)
- [ ] weapon `attackProc`(bone_saw Bleeding)/`onEquip`(mask GasesImmunity)可走通
- [ ] 盾类 drBonus 标降级(不崩、不 [BLOCKED])
- [ ] `./gradlew :core:test` 全绿
- [ ] **降级清单**记录在本文末 `## Degradation`

## Degradation(M10c 回调依赖 + 资产缺失)
**M10c 回调依赖(阻塞完整语义,不阻塞注册)**:
- 5 盾:`drBonus`/blockChance/blockDamage/recharge + `left_hand` 槽;ChaosShield 额外 `ownerTakesDamage/ownerDoesDamage` 充能 + 动态 image
- `plague_doctor_mask`:artifact 槽(无 LuaAccessory,当前落 weapon 槽);`Accessory.equip/unequip` Java 桥
- 武器自定义公式:`bone_saw`/`remixed_pickaxe` 的 `damageRoll/accuracyFactor/attackDelayFactor`(LuaItem 用 tier 基线)
- `remixed_pickaxe`:挖矿 action(`execute/actions/ACMine` + terrain + 掉落)、`self.data.bloodStained` per-instance Lua 状态、`glowing`
- 食物 5 项 + `tengu_liver`:`eat`/`onThrow`/`burn/freeze/poison` 转换(LuaMaterial 无 use 派发)
- `bone_saw`:Doctor 暴击/采集掉落/麻痹加成(无 heroClass/itemFactory API)
- `vile_essence`/`tengu_liver`:`glowing`

**资产缺失**:
- remished `items/shields.png`、`items/food.png`、`items/swords.png`、`items/chaosShield.png`、`items/mastery_items.png`、`items/artifacts.png`、`items.png(81+16)` 未导入 → image 用 SPD `items.png` 占位 int

## 注意
- **绝不 `git add -A`**(只 add 新 15 .lua + 1 test)
- **codex 评审用 codex exec workaround**(codex_reviewer terminal 超时,见 CAO memory)
- **遇 M10c 回调缺:标注降级,不 [BLOCKED]**
- 新 item 集中 `test_mod/scripts/items/`(参考 M6d 模板 kunai/hooked_dagger/bone_shard)
