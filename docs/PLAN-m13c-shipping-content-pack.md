# PLAN: M13c — 内置内容包 remished_lite(default-enabled showcase 关卡)

## Goal
一个 `default_enabled=true` 的 builtin mod `remished_lite`,提供一个**curated showcase 自定义关卡**(hub/town 或挑战地牢),内含放置好的 Lua mob/item/spell/npc/shop,玩家经 M13b 的「自定义关卡」入口进入体验。让 fork **开箱即有一个可玩的内容展示**(不是空平台)。**main game 保持原版**(C3 纯净)—— 所有内容置于关卡内,不污染 vanilla 掉落/刷怪/平衡。

## Context(Explore 2026-07-09 关键结论:什么 auto-manifests)
Explore 核实:**几乎无内容在 vanilla main game 自动出现**。
| 类别 | main game auto-manifest? | 机制 |
|---|---|---|
| item | ❌ | `Generator.LUA_ITEM` firstProb/secondProb=0(`Generator.java:261`),不掉落 |
| spell | ❌ | `LuaSpell extends Item`,无 spellbook UI,不自动入背包 |
| mob | ❌ | `LuaMobRegistry` 不进 spawn 池(`Level.createMob` 不动) |
| buff | ❌ | 只经 RPD API / 回调 attach |
| **trap** | ✅ | `LuaLevelService.injectLevelTraps`(`:388`)**非 debug-gated**,`LuaTrapRegistry.hasAny()` 即全局放置 |
| level | ❌ | 经 `enterLevel`(M13b 放开门)或 debug |
| npc/shop | debug-only | `injectLevelNpcs` debug-gated |

**设计推论**:
- **只 register 的内容在 main game 是 inert**(注册了但不出现)→ 安全(不污染 vanilla 掉落/刷怪)。
- **唯一例外:trap auto-manifest** → 为保 C3(main game 原版),**remished_lite 不 register_trap**(避免全局放陷阱改变 vanilla 关卡)。陷阱若需在 showcase 关卡里,经关卡 json 放置(非 register)。
- **内容置于 showcase 关卡内**:关卡 json 的 `mobs`/`items` 数组(DataDrivenLevel schema,M4a 支持 `type:"lua_npc:<id>"`/`"lua_shop:<id>"` + item id)放置 Lua 实体 → 玩家进入关卡才接触内容。main game 零变化。
- **default_enabled=true**:pack 启用 → entry.lua `register_level` 注册 showcase 关卡 → M13b 列表自动出现该关卡 → 玩家一键进入。**无需玩家手动启用 mod**(开箱即用)。

**设计决策**:
- showcase 关卡 = 一个小规模、可玩、展示平台能力的自定义关卡(建议 hub/town 风格,放 NPC(对话)+ shop(买卖 curated item)+ 几个 mob(战斗)+ 散落 item(拾取)+ spell(获取))。worker 参考现有 `mods/levels/test_safezone.json`(32×32 padding 规范)+ M4b NPC / M4c shop / M6 mob/item/spell 脚本。
- 关卡 json 放 `core/src/main/assets/mods/levels/remished_lite_hub.json`(builtin classpath;register_level 默认 path 解析到此,M12d builtin 分支 fromAsset 加载)。
- `mod.json`:`default_enabled=true`,id=`remished_lite`,spd_version=versionCode,balance 字段谨慎(关卡内数值可玩即可)。
- **C3 核心保证**:enabled 时 main game 原版一周目不受影响(注册的 item/spell/mob 不掉落/不刷/不入背包 —— Explore 已证 inert);唯一 main-game 可见变化 = M13b 暂停菜单的「自定义关卡」按钮(有关卡才显示)。

## Files (worker)
- **`core/src/main/assets/mods/remished_lite/mod.json`**:id=`remished_lite`,`default_enabled=true`,entry=`entry.lua`,description。
- **`core/src/main/assets/mods/remished_lite/entry.lua`**:`register_level{id="remished_lite_hub", name="..."}` + `register_item`/`register_mob`/`register_spell`/`register_npc`/`register_shop`(showcase 关卡放置的实体)。**不 `register_trap`**(避免 auto-manifest)。
- **`core/src/main/assets/mods/remished_lite/scripts/{items,mobs,spells,npcs,shops}/*.lua`**:curated 代表实体(worker 从 M6-M11 现成 Remished 脚本选,保证签名正确;数值可玩即可)。
- **`core/src/main/assets/mods/levels/remished_lite_hub.json`**:showcase 关卡几何(32×32 padding,镜像 test_safezone 规范)+ `mobs`/`items` 数组放置 Lua 实体(`type:"lua_npc:<id>"`/`"lua_shop:<id>"`/item id)+ entrance/exit。
- **`core/src/test/.../modding/RemishedLitePackTest.java`**(新):
  - enabled(默认)时:`LuaLevelRegistry.contains("remished_lite_hub")` true + 各实体 registry 含对应 id。
  - **C3 守护**:enabled 时 main game 原版不掉 Lua item(`Generator.LUA_ITEM` 仍 0 prob,不受 pack 影响)+ spawn 池不含 Lua mob。镜像 ModToggleRegressionTest 的 disabled-empty 断言思路,但此处证 "enabled 也不污染 main game 集成路径"。
  - showcase 关卡 json 可加载(DataDrivenLevel.fromAsset 不抛)。

### 显式延后
- **main-game item 掉落**:给 `Generator.LUA_ITEM` 非 0 prob 让 curated item 在 main 掉落(C3 风险 + 全局影响所有 enabled mod)。**本 feature 不做**,内容置于 showcase 关卡内。留后续(独立 hook feature)。
- **starter spell / spellbook UI**:让 curated spell 在 main 可用。留后续。
- **trap 放置 showcase 内**:若需,经关卡 json(非 register_trap)。worker 视关卡设计定。
- **规模/平衡**:本 feature 是 showcase(展示平台 + 可玩),非大规模内容/精细平衡(留后续 content milestone)。

## Steps
1. **读参考建 correct-lua + 关卡 schema**:`mods/levels/test_safezone.json`(32×32 padding)+ M4a DataDrivenLevel schema(mobs/items 数组 type 格式)+ M4b NPC / M4c shop / M6 mob/item/spell 现成脚本 + regression_demo(M11e,M6-M11 全 API 现成参考)。
2. **设计 showcase 关卡**:小规模 hub/town(NPC 对话 + shop + 几 mob + 散落 item + spell)。worker 定具体布局(参考 test_safezone 几何)。
3. **写 mod.json(default_enabled=true)+ entry.lua(register_level + 各实体)+ scripts/ + 关卡 json**。
4. **C3 关键核实**:确认 `Generator.LUA_ITEM` prob 不被改、spawn 池不含 Lua mob、pack enabled 不改 main game 集成路径(Explore 已证 inert,测试守)。
5. **测试**:`RemishedLitePackTest`(registry 填充 + C3 main-game 不污染 + 关卡 json 可加载)。
6. **`./gradlew :core:test`** 全绿。
7. **codex 评审**(Phase 1/2):重点 —— C3(enabled 不污染 main game,内容只在关卡内)、`default_enabled=true` 的范围影响(只 register + 关卡,无全局 hook)、关卡 json schema 合法、lua 签名正确(对照 wrapper)。
8. **desktop 手动验证**(依赖 M13b 合并后):暂停菜单 → 自定义关卡 → 进入 remished_lite_hub → NPC/shop/mob/item/spell 正常 → 能返回。

## Acceptance
- [ ] `remished_lite` mod 存在,`default_enabled=true`,entry 注册 showcase 关卡 + 实体
- [ ] showcase 关卡 json 合法(32×32 padding,mobs/items 放置 Lua 实体)
- [ ] **不 register_trap**(避免 main game auto-manifest,C3 守)
- [ ] **C3**:enabled 时 main game 原版不掉 Lua item / 不刷 Lua mob / spell 不入背包(Explore inert 结论 + 测试守)
- [ ] `RemisedLitePackTest`:registry 填充 + C3 不污染 + 关卡可加载
- [ ] `./gradlew :core:test` 全绿(583 + 新增)
- [ ] 与 M13a/b 零文件冲突(只加 assets + 一个测试)

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,**不 assign codex_reviewer**(memory:必超时;若 502/503 按政策 B 以 :core:test 硬验收)
- **C3 是本 feature 核心**:`default_enabled=true` 改变了"默认关闭"路线(roadmap §1.4),但内容只在 showcase 关卡内(main game 原版),C3 守住 —— worker 必须测试证 "enabled 不污染 main game 集成路径"
- **不 register_trap**(唯一 auto-manifest 类别,会改 main game)
- **不改 Generator/Hero/spawn 核心**(main-game item/spell 掉落留后续独立 hook feature)
- lua 签名对照 wrapper Java + 现成脚本(regression_demo/test_mod/demo_m58 已绿)
- 与 M13a(ModInstaller/WndModManager)/ M13b(LuaLevelService/WndGame/新窗口)零重叠;showcase 关卡经 M13b 入口进入(runtime 依赖,非 code 冲突)

---

## Refinements (worker 探索后细化,2026-07-09)

探索代码后,3 处 PLAN 假设需校正为可执行粒度(direction 不变,补充实现约束)。

### R1. DataDrivenLevel 关卡 json 的 placement 比 PLAN 假设窄
核对 `DataDrivenLevel.java` 静态白名单(`MOB_TYPES` / `ITEM_TYPES`,行 396-408):
- `mobs[].type` 仅支持:`rat_king`(vanilla)、`lua_npc:<id>`、`lua_shop:<id>`。**无 `lua_mob:` 前缀** —— 敌对 Lua mob 无法经关卡 json 放置。
- `items[].type` 仅支持:`gold`(vanilla)。**无任意 item id / lua item id** —— Lua item 无法做地板拾取。

获得路径(代码核实):
- Lua item:`RPD.giveItem(heroId, itemId, qty)`(`RpdApi.java:1392`)走 `LuaItemRegistry.createItem` —— **可给 Lua item,不能给 spell**(spell 在 LuaSpellRegistry,独立)。
- Lua spell:**无 giveSpell API**,且无 spellbook/starter 路径(PLAN 已显式延后)。→ 注册的 spell 是 registered-but-inert(C3 演示 + 覆盖度),文档注明。
- 敌对 Lua mob:**无 lua_mob: 前缀,无 spawn API**。→ 注册的 mob 同样 registered-but-inert(C3 演示:注册了但 main game spawn 池不读 LuaMobRegistry,showcase 关卡也无法放置)。

**结论**:showcase 关卡能直接 host 的 Lua 实体 = NPC + shop(+ vanilla rat_king/gold)。item/spell/mob 注册用于「覆盖度 + C3 演示」,其中 item 经 NPC giveItem 可玩;spell/mob inert(文档注明,不假装可玩)。

### R2. Showcase 具体设计(全部原创最小 Lua,镜像 regression_demo 已绿签名)
| 实体 | 文件 | registry | 可玩? |
|---|---|---|---|
| weapon | `scripts/items/remished_lite_lantern_blade.lua` | LuaItem | ✅ guide NPC `RPD.giveItem` 发放(tier 2 + attackProc) |
| spell | `scripts/spells/remished_lite_spark.lua` | LuaSpell | inert(targeting=self,onUse;无获取路径,PLAN 延后) |
| mob | `scripts/mobs/remished_lite_marauder.lua` | LuaMob | inert(无 lua_mob: 放置;C3 演示) |
| npc | `scripts/npcs/remished_lite_guide.lua` | LuaNpc | ✅ 对话 + giveItem(weapon) |
| npc | `scripts/npcs/remished_lite_sage.lua` | LuaNpc | ✅ 对话(lore) |
| shop | `scripts/shops/remished_lite_shop.lua` | LuaShop | ✅ 卖 vanilla consumables(potion/scroll/food 白名单) |
| level | `assets/mods/levels/remished_lite_hub.json` | LuaLevel | ✅ 32×32 hub(镜像 test_safezone),放 guide/sage/shop/rat_king + gold×2 |

- `mod.json`:id=`remished_lite`,`default_enabled=true`,`spd_version=896`,`entry=entry.lua`,desc。
- `entry.lua`:`RPD.GLog` banner + `register_level{id="remished_lite_hub", name="Remished Lite Hub"}`。(**不**在 entry 里 register_item/mob/spell/npc/shop —— 这些由各 scripts/<type>/*.lua 自带 register,目录扫描自动加载,见 LuaEngine.loadScriptsFrom。entry 只承载 banner + register_level,因为 level 无目录扫描。)
- **无 `scripts/traps/`** —— 不 register_trap(LuaTrapRegistry 保持空 → injectLevelTraps 的 `hasAny()` 守为 false → main game 不 auto-manifest,C3)。
- 关卡几何:32×32,墙边界 + 内部 open floor(镜像 test_safezone 的 (9,9)-(22,22) 房间),entrance(9,9)=297、exit(22,22)=726,guide(11,11)、sage(18,11)、shop(11,18)、rat_king(20,20)、gold×2,`safe=true`。

### R3. default_enabled=true 会破坏现有测试的精确计数断言(必须修测试 harness)
`ModRegistry.isEnabled`(`ModRegistry.java:68`)= `GameSettings.getBoolean("mod_enabled_<id>", m.default_enabled)`。现有测试用 fresh `FakePreferences`(无 entry)→ remished_lite 默认 **true** → 在任何 scanDir 真实 mods 的测试里自动加载,撑大精确 size 断言(如 ModToggleRegressionTest items=26/mobs=18/spells=31、RegressionDemoModTest items=3、LuaSpellTest spells=31)。

**修法(集中)**:
- `ModTestSupport.enableTestMod()` 末尾 += `ModRegistry.setEnabled("remished_lite", false);` —— 一次覆盖所有走 enableTestMod 的测试(ModToggleRegressionTest/LuaSpellTest/LuaMobTest/LuaNpcTest/LuaShopTest/LuaAllyTest/LuaHeroTest/LuaItemCallbackTest/GeneratorLuaItemTest 等 ~15 个)。
- `RegressionDemoModTest.enableRegressionDemo()` 同样 += disable remished_lite。
- `DemoM58LoadTest` @Before(line 88 scanDir 后)同样 += disable remished_lite。
- `ModScannerTest` 不受影响(用合成 mod.json fixture,不扫真实 mods)。

**这扩大了 PLAN 说的「只加 assets + 一个测试」的改动面 —— 但这是 default_enabled=true 的必然结果**(只要注册任意 item/mob/spell/npc/shop 到默认开启 mod,就会撑大这些计数)。direction 不变,仅补 harness 更新。Flag 给 codex。

### R4. RemishedLitePackTest 具体断言(镜像 RegressionDemoModTest / SafeZoneEnterTest)
headless setup(Game.versionCode=896)+ @Before:FakePreferences + scanDir 真实 mods + resetLuaState(此测试 **要** remished_lite 默认开启,不 disable)。
- `test_defaultEnabled_loadsShowcase`:assertTrue(`ModRegistry.isEnabled("remished_lite")` 默认 true);`LuaEngine.init()`;断言 item/spell/mob/2 npc/shop 各 registry 含对应 id + level 在 LuaLevelRegistry。因只 remished_lite enabled(其他默认 false),可断精确 size(item=1,spell=1,mob=1,npc=2,shop=1)。
- `test_c3_enabledDoesNotPolluteMainGame`(C3 核心):enabled 状态下 —— `Generator.Category.LUA_ITEM.firstProb==0f && secondProb==0f`(deck 永不选 Lua item);`LuaTrapRegistry.size()==0`(无 traps/ → 无 auto-manifest);mob 在 LuaMobRegistry 但 vanilla spawn 不读它(levels/actors grep 证 LuaMobRegistry 零引用,注释引此架构事实)。
- `test_levelJsonLoadable`:`DataDrivenLevel.fromAsset("mods/levels/remished_lite_hub.json","remished_lite_hub")` 非 null(镜像 SafeZoneEnterTest:74)。
- `test_disabled_loadsNothing`:`setEnabled("remished_lite",false)`;init;断言所有 remished_lite id absent + 各 registry size==0(镜像 RegressionDemoModTest.loadDisabled)。

### Steps(执行序,替换原 Steps)
1. 写 `mod.json` + `entry.lua` + 6 个 scripts(items/spells/mobs/npcs×2/shops)+ 关卡 json(按 R2)。
2. 修 harness:ModTestSupport.enableTestMod + RegressionDemoModTest + DemoM58LoadTest 各 += disable remished_lite(按 R3)。
3. 写 `RemishedLitePackTest`(按 R4)。
4. `./gradlew :core:test` 全绿(原 583 + 新增 4 test method,减去被 disable 影响的 0 个断言失败)。
5. codex exec 评审(Phase 1 PLAN / Phase 2 实施)。
6. commit(绝不 `git add -A`;只 add 本 feature 的 assets + 2 harness 文件 + 1 test)。
