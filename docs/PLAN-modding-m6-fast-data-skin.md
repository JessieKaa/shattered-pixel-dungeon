# PLAN: M6-fast — C 路径数据皮(Remished 纯数据 item 搬运)

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M6(可选并行支线 M6-fast)/ §9.4 路径 C
> **与 M6a(B 路径桥扩容)文件级隔离,可并行**
> 目标:验证 C 路径"只搬数据,行为套现有 wrapper"的真实成本,与 B 路径对照

## Goal

挑 2-3 个 Remished **纯数据材料 item**(无行为回调),走我们既有 `LuaItem` wrapper 嵌入 SPD,验证 C 路径搬运 pipeline 的成本与局限。产出与 B 路径(M6a/M6b)对照的成本数据。

## Context

### C 路径定义(§9.4)

- 只搬**数据**(名字/数值/贴图索引),行为套现有 `LuaItem` wrapper 的回调槽
- **不碰** `RpdApi` 桥扩容、**不引入** Remished `scripts/lib/`(commonClasses/item/serpent),纯用我们的 `register_item`
- 与 B 路径(改写行为脚本)对照:C 便宜但丢玩法特色,B 保玩法但贵

### Remished item 模式(已探查 `../remixed-dungeon/scripts/items/`)

22 个脚本,纯数据材料类最短(21 行),模式:

```lua
local RPD = require "scripts/lib/commonClasses"   -- 我们没有
local item = require "scripts/lib/item"           -- 我们没有
return item.init{
    desc = function() return {
        image = 4, imageFile = "items/materials.png",
        name = "RottenOrgan_Name",  -- i18n key
        info = "RottenOrgan_Info",
        price = 5, stackable = true
    } end
}
```

### 我们 LuaItem 模式

`register_item{id=, name=, tier=, image=, [attackProc/onEquip/onDeactivate]}`(见 `LuaEngine.RegisterItemFunction` L362-385)。C 路径只填数据字段,不填回调。

### Schema 差异(改写核心)

> **worker 探查结论(2026-07-06,基于 `LuaItem.java` L63-72 hydrate + `LuaEngine.RegisterItemFunction` L363-385)**

实际 hydrate 读取的字段(其余 table key 被静默忽略,不报错):
- `id`(required jstring)、`name`(required jstring)、`desc`(**optional** optjstring 默认 `""`)、`tier`(required int)、`image`(**optional** optint 默认 0)
- 回调槽 `attackProc`/`onEquip`/`onDeactivate`(optional,lazy 求值)
- **price / stackable / info / imageFile 完全不被读取** —— 填了也无效,但不报错(register 不校验这些 key)

| Remished | 我们 LuaItem | 处理(已核实) |
|---|---|---|
| `name`(i18n key) | `name`(硬编码字符串) | 翻成中文硬编码(fork i18n 约定,不走 Messages.get) |
| `image`(int)+ `imageFile`(spritesheet) | `image`(单一 int) | 占位 int(不搬 Remished spritesheet,版权+工作量) |
| `price`(5) | **不支持**(hydrate 不读) | 记为 C 路径局限。LuaItem 走 MeleeWeapon 的 tier-based `price()` |
| `stackable`(true) | **不支持**(LuaItem extends MeleeWeapon,Equip 类天生非 stackable) | 记为局限。材料语义丢失 |
| `info`(描述) | `desc`(**已支持**) | 直接映射 `info` → `desc`(PLAN 原表把 desc 列为缺口,**纠正:desc 已支持**) |
| 无 `tier` | `tier`(必填) | 材料类填 `tier=0` |

### 结构性错配(C 路径核心成本,PLAN 原稿未点名)

**`LuaItem extends MeleeWeapon`**(L45)。RottenOrgan/BoneShard/ToxicGland 在 Remished 是**材料**(stackable、非战斗、可出售/炼金)。把它们的数据皮套到 MeleeWeapon wrapper 上,产出的不是"材料",而是"**一把声称自己是 RottenOrgan 的近战武器**":
- 进武器槽(非背包材料堆)、有 STR 需求 / tier-based 伤害
- 不可堆叠、不能按材料定价、不能投入炼金

**结论**:C 路径(纯数据、套现有 wrapper)只能 skin **与 wrapper 类型匹配**的 item(武器)。材料/消耗品/护甲需新建 wrapper(`LuaMaterial` 等)—— 那是 B 路径量级的工作,违背 M6-fast「最小改动 + 与 M6a 隔离」。故本 feature 仍按 PLAN 推进(材料数据皮套武器 wrapper),把类型错配记为 C 路径**头号局限**,写入 D5 建议。

**worker 第一步已完成**:见上表,不再需要额外探查。

## Files

- `core/src/main/assets/mods/test_mod/scripts/items/`(新增 3 个 .lua):改写后的数据 item —— `rotten_organ.lua` / `bone_shard.lua` / `toxic_gland.lua`(纯 `register_item`,id 用 snake_case 与 test_sword/test_axe 一致)
- `core/src/test/java/.../modding/DataSkinItemTest.java`(新增):register+spawn+name/tier 单测 + 一条「price/stackable 填了但被忽略」的局限锁定测试
- `core/src/test/java/.../modding/ModToggleRegressionTest.java`(**小改,必要**):`enabled_mod_loadsAllTestContent` 的 exact-size `6 → 9`、并补 3 个新 ID 断言(此断言是 exact,新增 test_mod 内容必须同步更新;这是 expected evolution,非回归)。**隔离分析**:M6a 改 `RpdApi/LuaMob/LuaSandbox/LuaEngine` + 新增 bridge 测试,大概率不碰 ModToggleRegressionTest,冲突风险低
- `core/src/test/java/.../modding/GeneratorLuaItemTest.java`(**小改,必要**,codex round-1 发现):L77 `generatorRandomLuaItemReturnsLuaItem` 断言 `item.name().contains("(Lua)")`。`Generator.random(LUA_ITEM)` 从整个 registry 抽,抽中不带 `(Lua)` 的新数据皮会**随机失败**。`(Lua)` 是 debug marker,不应成内容约束 → 放宽为「name 已 hydrate(非空、非降级默认 `???`)」。`instanceof LuaItem`(L74)已是主契约,name 检查仅辅助
- **不改**:`LuaItem.java`(守最小改动 + 与 M6a 隔离)、`LuaEngine.java`、`RpdApi.java`、`LuaMob.java`、`LuaSandbox.java`
- **不引入**:Remished `scripts/lib/`(`commonClasses`/`item`/`serpent`)

## Steps

1. ✅ **已核实 schema**(见上「Schema 差异」+「结构性错配」):hydrate 读 id/name/desc/tier/image;price/stackable 不读但填了不报错
2. **挑 3 个 Remished 纯数据 item**:`RottenOrgan.lua`/`BoneShard.lua`/`ToxicGland.lua`(均 21 行,纯 desc table,无 `RPD.*` 调用、无行为回调,符合 C 路径挑选标准)
3. **改写**(每个 ~5-8 行):去掉 `require commonClasses/item` 与 `item.init/desc` 包裹,直翻为
   ```lua
   register_item {
       id = "rotten_organ",   -- snake_case,与 test_sword 一致
       name = "腐烂器官",       -- Remished key RottenOrgan_Name 的中文硬编码
       desc = "从瘟疫医生采集系统获得的腐烂器官(C 路径数据皮,材料语义未保留)。",  -- info → desc(已支持)
       image = 4,              -- 占位 int(原 Remished image,但不搬 materials.png)
       tier = 0,               -- 材料类填 0
       -- 以下两行填入但被 hydrate 忽略,作为「C 路径局限」的可观测记录:
       price = 5, stackable = true,
   }
   ```
   bone_shard(name=骨片碎片,image=5)、toxic_gland(name=毒腺,image=3)同构
4. **贴图占位**:沿用原 Remished image int(3/4/5),**不搬** materials.png。运行时映射到 SPD ItemSpriteSheet 的占位图(视觉错位记为已知局限,不修)
5. **新增 `DataSkinItemTest`**:enableTestMod+resetLuaState → `LuaEngine.init()` → 断言 3 个 id 已注册、`LuaItemRegistry.create` 非 null、`name()` 是中文、`tier()==0`、`image` 正确;再加一条「register 带 price/stackable 不抛错」锁定局限
6. **更新 `ModToggleRegressionTest.enabled_mod_loadsAllTestContent`**:item size `6 → 9`、补 3 个 ID contains 断言;`disabled_mod_loadsZeroLuaContent` 不需改(默认关闭仍 0)
7. **更新 `GeneratorLuaItemTest.generatorRandomLuaItemReturnsLuaItem`**(L77):放宽 `name().contains("(Lua)")` → `name()` 已 hydrate(非空且非降级 `???`),解除 debug-marker 内容约束(codex round-1)
8. **smoke**:`./gradlew :core:test` —— 既有 modding 196 个 @Test + 新增 + 更新全过;modding 以外的既有测试不受影响(mod 关闭零加载)
9. **回填**本 PLAN 末尾「C 路径成本数据」

## Acceptance

- [x] 2-3 个 Remished 数据 item 改写成 `register_item` 格式,在 SPD 内 register + spawn 成功(3 个,DataSkinItemTest 5/5 绿)
- [x] 改写**未引入** Remished `scripts/lib/`(纯 `register_item`)
- [x] **未碰** `RpdApi.java`/`LuaMob.java`/`LuaSandbox.java`/`LuaEngine.java`/`LuaItem.java`(diff 仅 3 .lua + 3 测试 + PLAN,与 M6a 文件隔离)
- [x] 产出 **C 路径成本数据**(见下节):3 item / ~5 min 机械改写 / 2 数据缺口 + 1 类型错配 / 占位 int / D5=C 是 B 窄补充
- [x] mod 关闭零影响(C3,`disabled_mod_loadsZeroLuaContent` 仍 0);既有测试不回归(core 223 tests,0 failures = 218 既有 + 5 新增)

## C 路径成本数据(完成后回填 — 已回填 2026-07-06)

- **改写的 item 数**:3(rotten_organ / bone_shard / toxic_gland,均来自 Remished Plague Doctor 材料类)
- **单 item 平均改写工时**:
  - 纯机械改写(去 `require commonClasses/item` + `item.init/desc` 包裹,翻 6 字段):**~5 min/item**
  - 第 1 个含 schema 探查(amortized ~15 min);2nd/3rd 各 ~5 min
  - 含写单测 + 调两处 exact/marker 断言的全流程摊销:**~15 min/item**(3 item 总实施 ~45 min)
- **LuaItem schema 缺口**:
  - `price` —— hydrate 不读(走 MeleeWeapon tier-based `price()`)
  - `stackable` —— hydrate 不读(Equip 类天生非 stackable)
  - `imageFile` —— 单 int 占位绕过(不搬 spritesheet)
  - `info` —— **非缺口**,直接映射到已支持的 `desc`
  - **结构性缺口 1 个**:`LuaItem extends MeleeWeapon`,材料/消耗品/护甲类型错配
  - 净数据缺口 = **2**(price/stackable)+ **1 类型错配**(头号成本)
- **贴图处理**:**占位 int**(沿用 Remished image 3/4/5,不搬 materials.png —— 版权)。运行时映射 SPD ItemSpriteSheet 占位槽,视觉错位为已知局限,不在本 feature 修

### **D5 建议**:C 是否值得作为 B 的补充 / 替代

**结论:C 是 B 的「窄补充」,不能替代 B。**

- **C 的甜蜜区(值得做)**:对**武器类** item 做纯数据 reskin —— 改名/改数值/改贴图索引,行为不变。此场景 LuaItem(MeleeWeapon)wrapper 类型匹配,~5 min/item,零 java 改动,与 M6a 隔离。批量武器皮(节日皮肤、难度变体)走 C 极划算。
- **C 的死穴(必须走 B)**:**非武器类型**(材料、消耗品、护甲、戒指、法术)。LuaItem 是 MeleeWeapon 子类,套这些类型会产生结构性错配(本 feature 的 RottenOrgan 即示范:材料变成了"自称材料的武器",不可堆叠/不能炼金/进武器槽)。要让 C 覆盖这些类型,需新建 `LuaMaterial`/`LuaConsumable`/`LuaArmor` wrapper —— 那是 B 路径量级的工作(桥扩容 + 新 wrapper 类),违背 C「最小改动」初衷。
- **对 M6b 的输入**:B 路径(M6b 行为脚本搬运)应**优先挑选非武器 + 有行为特色的 item**(C 处理不了的双雷区),与 C 的武器 reskin 形成互补,而非重叠。
- **一句话**:C 管"武器换皮",B 管"新类型 + 新行为"。两者**分工互补**,B 是主力,C 是武器皮的快车道。

## Risks

- **schema 不一致**:Remished 字段(imageFile/price/stackable/info)我们 LuaItem 可能不全支持 → 记局限不强改,保与 M6a 隔离
- **贴图版权**:Remished spritesheet 不搬,用占位 int。真实嵌入要补贴图(单独工作,M6-fast 不做)
- **i18n**:Remished name 是 key,我们硬编码 —— 选英文名或自译中文(fork i18n 约定)
- 与 M6a 合并:文件级隔离(`test_mod/scripts/items/` vs `modding/*.java`),预期零冲突
