# PLAN — M20e: remixed_full Lua painters 内容补全

## Goal
为 `remixed_full` mod 补全 **painters** 内容类型(当前 `scripts/painters/` 缺失)。交付 ≥2 个 remixed 风格房间绘制器:1 个铺地形(草/地毯),1 个放装饰(火把/标牌)。

## Context
- LuaEngine 的 M10b loader 自动扫描 `mods/remixed_full/scripts/painters/*.lua` → **只加文件,不碰 entry.lua**(`LuaEngine.loadPainterScripts`,扫描与 entry.lua 无关)。
- `register_painter { id=, paint=, decorate= }`:`id` = Room 子类 simpleName(overlay 按此 key 匹配);`paint(level, room)` / `decorate(level, room)` 均可选,adapter 先 paint 后 decorate。
- `RemixedFullPackTest` 计数(items/spells/mobs/npcs/shops)不含 painter → **不要改它**;且无任何现存测试在 remixed_full enabled 时断言 painter 数量 → 新增 2 个 painter 不会破坏它。
- test_mod PoC:`scripts/painters/demo_painter.lua`、`LuaPainterAdapterTest.java`(paint 调用 + setTile gate)、`LuaPainterRegistryTest.java`(注册断言)。

## PoC 核查结论(API 约束 + 方案适配)— worker 细化

**Goal 原文写"草/地毯"和"火把/标牌",经 PoC 核查不可行**,原因(代码事实):
- `LuaPainterAdapter.SetTileClosure` 的 `TARGET_WHITELIST` 只允许 `EMPTY / EMPTY_DECO / EMPTY_SP / EMBERS`(`LuaPainterAdapter.java:201-204`)。
- `PROTECTED_CURRENT` 含 `GRASS / HIGH_GRASS / FURROWED_GRASS`(`:187-195`)→ 草地既不能写也覆盖不掉。
- `RpdApi.terrainConstants()` 只暴露上述可写子集 + 只读参考常量(`RpdApi.java:274-293`),**无 torch/sign/carpet terrain**(SPD 无此地形,火把是墙面贴图非可放置地形)。
- 因此"铺草/放火把"在当前 painter API 下**结构性不可实现**(需改 Java gate,超出 content pack 范围)。

**方案适配(保留 Goal 的"铺地形 + 放装饰"方向,只换为 API 允许的地形)**:
- 铺地形 painter → 画 `EMBERS`(焦土/余烬覆地,passable,语义=覆地)。id 选 `BurnedRoom`(主题契合:焦土房再烧一遍)。
- 放装饰 painter → 散点 `EMPTY_DECO`(随机碎屑/装饰,passable,语义=放装饰)。id 选 `LibraryRoom`(主题契合:藏书室散落碎屑)。
- 这正是 PLAN Steps step 1 "或 PoC 确认的 API" 预留的细化路径,direction(2 个 painter:一铺地、一放装饰)不变。

## Files
- 新增 `core/src/main/assets/mods/remixed_full/scripts/painters/rf_ember_floor_painter.lua` — `id="BurnedRoom"`,`paint(level, room)` 遍历 `room.cells`,把 `tileAt==EMPTY` 的内部格设为 `RPD.Terrain.EMBERS`(经 setTile gate,非破坏)。注:`BurnedRoom.paint` 内部初始填 EMPTY(`BurnedRoom.java:71`),patch 格再随机化;overlay 只转仍是 EMPTY 的格 → 与残留 EMBERS/TRAP/GRASS 共存,gameplay 中性(reviewer 已认可)。
- 新增 `core/src/main/assets/mods/remixed_full/scripts/painters/rf_deco_scatter_painter.lua` — `id="LibraryRoom"`,`decorate(level, room)` 在前若干个 **`tileAt==EMPTY_SP`(或 EMPTY)** 的内部格散点 `RPD.Terrain.EMPTY_DECO`(上限 2 格,非破坏)。**guard 必须判 EMPTY_SP**:`LibraryRoom.paint`(`LibraryRoom.java:42`)内部填的是 EMPTY_SP(不是 EMPTY);旧 guard `==EMPTY` 永远 0 命中 → 死代码(reviewer round-1 must-fix)。
- 新增 `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RemixedFullPainterContentTest.java` — 4 个用例:
  - registration:enabled → `contains("BurnedRoom"/"LibraryRoom")` + `size()>=2`;disabled → 两个 id 均 absent。
  - behavior(防假阳性):用 StubLevel + 真实 `BurnedRoom`/`LibraryRoom` 实例(set bounds)+ no-op delegate 驱动 `LuaPainterAdapter.paint`,断言 ember painter 产出 ≥1 格 EMBERS、deco painter 产出 ≥1 格 EMPTY_DECO(否则 presence-only 测试抓不住死代码)。

## Steps
1. ✅ 读 PoC(`demo_painter.lua` / `regression_demo_painter.lua`)、`LuaPainterAdapter.java`、`RpdApi.java`、`LuaPainterAdapterTest.java`、`LuaPainterRegistryTest.java`、`RemixedFullPackTest.java`、`ModTestSupport.java`、`LibraryRoom.java`、`BurnedRoom.java` — 已确认 API 约束 + 目标房间真实地板 terrain(见上)。
2. 写 `rf_ember_floor_painter.lua`:
   ```lua
   register_painter {
       id = "BurnedRoom",
       paint = function(level, room)
           for i = 1, #room.cells do
               local cell = room.cells[i]
               if level.tileAt(cell) == RPD.Terrain.EMPTY then
                   room.setTile(cell, RPD.Terrain.EMBERS)
               end
           end
       end,
   }
   ```
3. 写 `rf_deco_scatter_painter.lua`(**guard 判 EMPTY_SP / EMPTY 两者**,修 must-fix):
   ```lua
   register_painter {
       id = "LibraryRoom",
       decorate = function(level, room)
           local placed = 0
           for i = 1, #room.cells do
               if placed >= 2 then break end
               local cell = room.cells[i]
               local cur = level.tileAt(cell)
               if cur == RPD.Terrain.EMPTY_SP or cur == RPD.Terrain.EMPTY then
                   room.setTile(cell, RPD.Terrain.EMPTY_DECO)
                   placed = placed + 1
               end
           end
       end,
   }
   ```
4. 写 `RemixedFullPainterContentTest.java`(headless harness + `@Before` reset,镜像 `RemixedFullPackTest` + `LuaPainterAdapterTest` 的 StubLevel 模式):
   - `enableRemixedFull()`:`ModRegistry.scanDir(ModTestSupport.realModsHandle())` + `setEnabled("remixed_full",true)` + 关闭 remished_lite/test_mod/regression_demo。
   - `enabled_bothPaintersRegister`:`LuaEngine.init()` → `assertTrue(contains("BurnedRoom"))`、`assertTrue(contains("LibraryRoom"))`、`assertTrue(size()>=2)`。
   - `disabled_paintersDoNotLoad`:全关 → `LuaEngine.init()` → `assertFalse(contains(...))`。
   - `emberPainter_paintsEmbersOnBurnedRoomInterior`:enable+init 载入 painter → StubLevel(8x8,内部填 EMPTY)+ 真实 `BurnedRoom`(left/top/right/bottom=1/1/6/6)→ `new LuaPainterAdapter(noopDelegate).paint(stub, [room])` → 断言至少 1 个内部格变为 EMBERS。
   - `decoPainter_scattersDecoOnLibraryRoomInterior`:同上,StubLevel 内部填 **EMPTY_SP** + 真实 `LibraryRoom` → 驱动 adapter → 断言至少 1 个内部格变为 EMPTY_DECO(直接证明 guard 修复有效、decorator 非死代码)。
5. 按 PLAN 约束按文件名 `git add`(绝不 `git add -A`,绝不 commit `.claude/`),`commit -m "feat(m20e-painters-content): ..."`。
6. `./gradlew :core:test` 绿(flaky: GeneratorLuaItemTest/GeneratorLuaSpellTest 概率断言,单独重跑)。

## Acceptance
- [ ] `mods/remixed_full/scripts/painters/` 下 2 个 `.lua`(ember_floor + deco_scatter),enabled 时注册成功。
- [ ] `RemixedFullPainterContentTest` 全部 4 个用例通过(含两个 behavior 用例)。
- [ ] `:core:test` 全绿(含 RemixedFullPackTest / LuaPainterRegistryTest / LuaPainterAdapterTest / RegressionDemoModTest 不回归)。
- [ ] 未改 `entry.lua` / `RemixedFullPackTest.java`(及任何 Java 源码)。

## Constraints(强制)
- 只在自己 worktree 改动。
- 绝不改 `entry.lua`(M20f 独占)、`RemixedFullPackTest.java`(M20g 独占)。
- 绝不 `git add -A` / commit `.claude/` / force push / reset --hard。

## 评审协议
完成 + 测试绿后,用 **`assign("codex_reviewer", ...)`** 评审(先 PLAN 再实现)。严禁直接 codex-cli。
- assign 失败/静默 → 跳过,回报说明,dispatcher 决定是否亲审。
- 复用同一 reviewer terminal。

## 回报协议
`send_message`(无 receiver_id)回报 caller:`[DONE]`/`[BLOCKED]` + commit hash + reviewer terminal_id/轮数(或跳过) + 测试结果 + 文件清单。
