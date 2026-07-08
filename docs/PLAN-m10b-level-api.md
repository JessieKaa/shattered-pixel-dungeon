# PLAN: M10b — 关卡 room/painter Lua API(路线 A:RegularLevel 单点注入)

## Goal
让 mod 能通过 Lua 自定义 procedural 关卡的 room 绘制(painter)和 trap 行为,超越 M4a SafeZone 的固定 JSON 地图。路线 A:在 `RegularLevel.painter()` 单点注入 `LuaPainterAdapter`,不改上游 Room/Painter/Trap 核心类。

## Context
M4a DataDrivenLevel 是固定地图(flat tile array),绕开 `RegularLevel.build()/painter()/rooms` 流程,无法改造主关卡(sewer/prison/...)的 procedural 生成。M10b 让 Lua 介入 procedural 关卡的 paint 阶段。

调研确认(见 M10b 调研报告):
- SPD `Room`/`StandardRoom`/`SpecialRoom`/`Painter` 硬编码注册(`StandardRoom.rooms` 列表 35 子类),无运行时 API
- `RegularLevel.painter()` 工厂方法返回硬编码 painter(`SewerLevel.painter() → new SewerPainter()`)
- Remished 模式:`CustomLevel`(.lua 描述)+ `LuaPainter`(按 room.type 从 property 拿脚本名分发)+ Trap 脚本化(`ScriptTrap`)

**路线 A(低侵入)**:
- `RegularLevel.painter()` 加 override 钩子,mod 模式下返回 `LuaPainterAdapter`
- `LuaPainterAdapter` 包装 `RegularPainter`,`paint()` 里把特定 room 路由到 Lua(按 room.type 或 mod 声明)
- Trap 脚本化:扩 `injectLevelNpcs` 模式 → `injectLevelTraps`
- 上游 Room/Painter/Trap 零改动

**风险**:Lua painter 只覆盖 `decorate` 或整 `Room.paint`;覆盖 door/water/grass/trap 丢上游布局保证 → PLAN 限定只覆盖 decorate + Room.paint,不碰 door/water/grass/trap。

## Files
- `core/.../modding/LuaPainterAdapter.java`(新)— **extends `Painter`**(不是 RegularPainter,见下),持 `Painter delegate`,paint 时先 `delegate.paint()`(完整上游 pipeline)再叠 Lua overlay
- `core/.../modding/LuaPainterRegistry.java`(新)— `Map<String, LuaTable>`,key = Room 类简名,`register_painter` Lua global
- `core/.../modding/LuaTrapRegistry.java`(新)— `Map<String, LuaTable>`,key = trap id,`register_trap` Lua global
- `core/.../modding/LuaTrap.java`(新,PLAN 原漏)— **extends `Trap`**,持 registry id,`activate()` 派发 Lua `onActivate(cell, charId)`;Bundle 持久化 `lua_trap_id`(Reflection.newInstance 恢复)
- `core/.../levels/RegularLevel.java` — **`build():120` 单点 wrap**(`painter()` 是 abstract,8 个子类各 override,不能单点改;build 是 painter() 唯一调用点)+ `createMobs()` 末尾加 `LuaLevelService.injectLevelTraps(this)`
- `core/.../modding/LuaLevelService.java` — `injectLevelTraps(Level)`(扩 `injectLevelNpcs` 模式,createMobs 调用,map 已就绪)
- `core/.../modding/LuaEngine.java` — `register_painter`/`register_trap` global + `loadPainterScripts()`/`loadTrapScripts()`(扫 `scripts/painters|traps/`)+ `installGlobalsForTests` 补两 global
- `core/.../modding/RpdApi.java` — `RPD.Terrain` 常量表(EMPTY/WALL/GRASS/HIGH_GRASS/EMBERS/EMPTY_DECO/EMPTY_SP/STATUE/WATER/DOOR 等子集)
- `core/.../modding/ModTestSupport.java` — `resetLuaState()` 补 `LuaPainterRegistry.clear()`+`LuaTrapRegistry.clear()`
- `assets/mods/test_mod/scripts/painters/demo_painter.lua` + `assets/mods/test_mod/scripts/traps/demo_trap.lua`
- 测试:`core/test/.../modding/LuaPainterRegistryTest.java`、`LuaTrapRegistryTest.java`、`LuaPainterAdapterTest.java`

## Steps

### A. Painter 侧
1. **LuaPainterRegistry**:`Map<String,LuaTable>`,`register(id,tbl)`/`getTable`/`contains`/`hasAny`/`clear`,镜像 `LuaItemRegistry`。`register_painter(table)` OneArgFunction:校验 `id`(string),`paint`/`decorate` 是可选 function(table entry,懒校验)。
2. **LuaPainterAdapter extends Painter**(不是 RegularPainter):`Painter` 仅一个 abstract `paint`,避免 stub abstract `decorate`。字段 `Painter delegate`。
   - `paint(level, rooms)`:`boolean ok = delegate.paint(level, rooms);`(跑完整上游:placeDoors→`r.paint`→paintDoors→water/grass/traps→decorate);若 `LuaPainterRegistry.hasAny()` → `applyLua(level, rooms);` `return ok;`
   - `applyLua`:遍历 rooms,key=`room.getClass().getSimpleName()`;registry 命中则构 per-call Lua 表并调回调:
     - `levelTbl`:`width/height/length` + `tileAt(cell)`(闭包只读 `level.map[cell]`)
     - `roomTbl`:`left/top/right/bottom/width/height/centerCell` + `cells`(1-indexed Lua 数组,**仅 `room.getPoints()` 中满足 `room.inside(p)` 的 interior cell**——排除 1-tile 边界/门)+ `randomCell()` + **`setTile(cell,terrain)`**(闭包写,见下)
     - **`setTile` 安全闸**(结构保证"不碰 door/water/grass/trap"):闭包持 `level`+当前 `room` 的 interior cell 集合;双重校验:(a) `cell ∈ interiorSet` 且当前 `level.map[cell]` 不在受保护地形集 {DOOR,OPEN_DOOR,LOCKED_DOOR,CRYSTAL_DOOR,SECRET_DOOR,WATER,TRAP,SECRET_TRAP,INACTIVE_TRAP,ENTRANCE,ENTRANCE_SP,EXIT,LOCKED_EXIT,UNLOCKED_EXIT,EMPTY_WELL,WELL,BOOKSHELF,BARRICADE,GRASS,HIGH_GRASS,FURROWED_GRASS};(b) **目标 terrain 必须在白名单** {EMPTY,EMPTY_DECO,EMPTY_SP,EMBERS}(全 PASSABLE,不破连通性;**WALL_DECO 排除**——flags 等同 WALL 是 SOLID|LOS_BLOCKING;TRAP 只能走 injectLevelTraps,DOOR/WATER/GRASS/STATUE 等一律拒);通过则 `level.map[cell]=terrain; level.updateCellFlags(cell);`(刷新派生 flags,**不调 GameScene**——build 期无 scene);拒绝则 log+no-op
     - 调 `LuaItemCallbacks.callOpt(tbl,"paint",levelTbl,roomTbl)` 再 `callOpt(tbl,"decorate",...)`(fire-and-forget,异常不炸 levelgen)
3. **RegularLevel.build():120 单点**:
   ```java
   Painter p = painter();
   if (LuaPainterRegistry.hasAny()) p = new LuaPainterAdapter(p);
   return p.paint(this, rooms);
   ```
   `painter()` abstract 不动,8 子类不动。**上游 Room/Painter/Trap 零改动。**
4. **RpdApi**:`RPD.Terrain` 常量表(curated 子集)。

### B. Trap 侧
5. **LuaTrapRegistry**:同 registry 模式。`register_trap(table)` 校验 `id`;`color/shape` optint(默认 GREY/DOTS);`onActivate` 可选 function。
6. **LuaTrap extends Trap**:public no-arg ctor(Reflection 恢复)+ `LuaTrap(LuaTable)` hydrate(id/color/shape/name)。`activate()`:`Char ch = Actor.findChar(pos);` `callOpt(tbl,"onActivate", LuaValue.valueOf(pos), LuaValue.valueOf(ch!=null?ch.id():0))`。storeInBundle `lua_trap_id`,restoreFromBundle 从 registry re-hydrate(脚本丢失则降级 active=false)。
7. **LuaLevelService.injectLevelTraps(Level)**:`if (!LuaTrapRegistry.hasAny()) return;` 收集合法候选 cell:`map[i]==Terrain.EMPTY` **且** `level.findMob(i)==null` **且** `i!=level.entrance() && i!=level.exit()` **且** `level.heaps.get(i)==null` **且** `level.plants.get(i)==null`(上游 trap 已把 trap cell 改 TRAP/SECRET_TRAP,天然不冲突;mob/物品/出入口排除防覆盖)。对 registry 每个 id 取 `LuaTrap`,随机选 N(≤ `validCells.size()/5`)cell:`level.setTrap(trap,pos)`(trap 对象入 traps 表)+ `trap.hide()` + `level.map[pos]=Terrain.SECRET_TRAP; level.updateCellFlags(pos);`(**刷新 secret/passable/solid 派生 flags**——隐藏 trap 搜索依赖 `secret[]`;**不调 GameScene**,createMobs 期 build 未完;绝不调 2-arg `Level.set(cell,terrain)`,它内部用 `Dungeon.level` 此时为 null)。Debug-agnostic(M9 已开 release modding),gate 纯 registry 非空。
8. **RegularLevel.createMobs()**:317 行 `injectLevelNpcs(this);` 后加 `LuaLevelService.injectLevelTraps(this);`。

### C. 接线 + 脚本 + 测试
9. **LuaEngine**:`initInternal` 加 `globals.set("register_painter",new RegisterPainterFunction())` + `register_trap`;init() 加 `loadPainterScripts()`+`loadTrapScripts()`(扫 `mods/<id>/scripts/painters|traps`);`installGlobalsForTests` 补两 global。
10. **ModTestSupport.resetLuaState** 补两 clear。
11. **demo_painter.lua**:`register_painter { id="<某 room 类简名>", decorate=function(level,room) ... level.setTile(cell, RPD.Terrain.EMPTY_DECO) end }`(decorate-only,只点缀不改布局)。**demo_trap.lua**:`register_trap { id="demo_trap", onActivate=function(cell,charId) RPD.GLog("lua trap!") end }`。
12. **测试**:
    - `LuaPainterRegistryTest`:register/get/contains/clear + register_painter 拒绝坏表。
    - `LuaTrapRegistryTest`:register/get/contains/clear + `LuaTrap.activate()` 派发 onActivate(用 callOpt 计数)。
    - `LuaPainterAdapterTest`:复用 `LuaLevelInjectTest` 的 `StubLevel` 模式构 `newStubLevel`;造假 `Room`(简名注册)→ 注册 painter → `adapter.paint` → 断言 Lua paint 回调被调 + `setTile` 真改 `level.map`(EMPTY→EMPTY_DECO);**setTile 安全闸测试**:改 DOOR/WATER/GRASS/HIGH_GRASS/TRAP cell 被 no-op(level.map 不变);改非 interior cell 被 no-op;未注册的 room 不被调。
    - `shippedPainterScriptsRegisterViaEngineInit`(镜像 `shippedTownScriptsRegisterViaEngineInit`):`LuaEngine.init()` 后 `LuaPainterRegistry.contains` + `LuaTrapRegistry.contains`。

## Acceptance
- [ ] `register_painter` / `register_trap` Lua global 工作
- [ ] LuaPainterAdapter 路由特定 room 到 Lua paint,不破 door/water/grass
- [ ] Trap 脚本化:Lua trap 能放置 + activate
- [ ] 上游 Room/Painter/Trap 核心类零改动(只 RegularLevel 单点 + 新 modding 类)
- [ ] `./gradlew :core:test` 全绿(458 现有 + 新增)
- [ ] C3 不破(不碰 vanilla loot/spawn pool)

## 注意
- **绝不 `git add -A`**:`.claude/` 不进 commit(只 `git add` 显式列出的文件)
- **codex 评审用 codex exec workaround**(不 assign codex_reviewer,CAO memory 已证 terminal mode 超时必失败)
- 新代码集中 `modding/` 子包(CLAUDE.md)
- **只覆盖 decorate + Room.paint overlay,不碰 door/water/grass/trap**(保上游布局保证):Lua overlay 跑在 `delegate.paint()` 之后,API 只暴露 room interior `cells`,`setTile` 不调 GameScene
- 参考 Remished `LuaPainter.java`(`remixed-dungeon/.../nyrds/pixeldungeon/levels/painters/LuaPainter.java`)按 room 分发模式;**但 SPD Room 无 `.type` enum,改用 `getClass().getSimpleName()` 作 key**

## 探索确认(worker 阶段1)
- `painter()` 是 abstract(RegularLevel.java:191),8 子类各 override;唯一调用点是 `build():120` → 单点 wrap 在 build,不动 painter() 签名/子类
- `RegularPainter.paint` 顺序:placeDoors→`r.paint(level)`→paintDoors→water/grass/traps→`decorate`;adapter `delegate.paint()` 跑完整链,Lua overlay 在最后
- `Dungeon.level` 在 `build()` 期间为 null(Dungeon.java:408 create → :490 才赋值)→ Lua 不能用 `Dungeon.level`-bound RPD helper;adapter 传 per-call `level` 表(closure 持真实 Level arg)做 `setTile/tileAt`
- `Painter` 仅 abstract `paint`(无 decorate stub 负担)→ adapter extends Painter 而非 RegularPainter
- `Trap`:abstract `activate()`,`trigger()` 调之,Reflection.newInstance 恢复(需 public no-arg ctor),Bundle 存 pos/visible/active
- 测试可复用 `LuaLevelInjectTest.StubLevel`/`newStubLevel` headless 模式构真实 map
- `LuaItemCallbacks.callOpt(tbl,fn,args)` fire-and-forget 派发(try/catch),复用
- gate 纯 registry 非空(M9 已开 release modding,不叠 isDebug)

## Pending Issues(codex 阶段1 第3轮未决,实施时已采纳)
- codex 第3轮 must-fix:setTile 需校验**目标 terrain**(不只当前 cell),否则 Lua 可 EMPTY→WATER/DOOR/TRAP 绕过约束,且 TRAP 缺 Level.traps 配套。**已在 Step 2 加目标 terrain 白名单 {EMPTY,EMPTY_DECO,EMPTY_SP,EMBERS,WALL_DECO},测试补 EMPTY→DOOR/WATER/GRASS/HIGH_GRASS/FURROWED_GRASS/TRAP no-op。** 实施时按此执行。
