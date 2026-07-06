# PLAN: M4e — SafeZone 进入稳定化(M4a 遗留 bug 修复)

## Goal

修复 SafeZone(debug 入口 `LuaDebugService` → `enterLevel`)进入的 M4a 遗留崩溃链,让 isDebug 模式下能稳定进入 SafeZone + 走动 + 交互 + 回程不崩。M5c 实机验证首次完整暴露(M5c **没引入** —— M5c 只动 LuaEngine + assets;这些是 M4a DataDrivenLevel × SPD 集成的遗留缺陷,M4d worker 当时报"桌面 golden-path 未跑"即指此)。

## Context

M5c 实机(2026-07-06,设备 `20210119085654`)debug 入口进 SafeZone 闪退。崩溃链 4+1 bug:

- **Bug 0(已修,本 worktree commit)**:`Actor.init()` 只 add 不 clear(L194-212)。SPD 标准切换在 switchLevel 前 `Actor.clear()`;M4a `enterLevel` 漏了 → 主线 mob(pos=主线坐标)残留进 SafeZone(256-cell)→ `findPassable vis[mainpos]` 越界(`length=256 index=284/902`)。**修复**:enterLevel 在 switchLevel 前加 `Actor.clear()`。
- **Bug 1(observe/tilemap 越界)**:`Dungeon.observe` L956 `level.visited[hero.pos+i]` for `PathFinder.NEIGHBOURS9` **无 bounds check**;`WallBlockingTilemap.wall`(L205 `map[cell]`)在 `updateArea` 遍历算出 `cell=269`(=16×16+13,y=16 越界)。SPD 上游假设 level ≥32×32 + 边缘 padding;SafeZone 16×16 hero 在 entrance=17(坐标 (1,1))触发。
- **Bug 2(enterLevel 失败非事务)**:`switchLevel` L490 `Dungeon.level=SafeZone` + L499 `Actor.init()` 已执行,L520 `observe()` 抛越界异常 → `enterLevel` L127 catch,但 state 已半切换(主线 level 丢失,actor 队列是 SafeZone + 残留)。
- **Bug 3(RatKing sprite null)**:`test_safezone.json` 有 `rat_king pos=136`。Bug 1+2 后 GameScene 没起来(`switchScene` 没到或半途),RatKing sprite 未建 → actor thread 跑 `RatKing.act → Mob.act:235 CharSprite.hideAlert()` NPE。
- **附带**:test_mod default_enabled=false(M5c)→ `lua_npc:test_npc/test_shop/town_return` 未注册,DataDrivenLevel log "unknown id skipping"(graceful,非 bug,但 SafeZone 进入后是空的)。

**根因**:SafeZone 16×16 太小,SPD observe/tilemap/PathFinder 系统假设 level ≥32×32 + 边缘 padding。DataDrivenLevel 没处理小 map 兼容。Bug 2+3 是 Bug 1 的后果(observe 越界 → 失败链)。

**修复策略**:修 Bug 0(已做)+ Bug 1(小 map 兼容)→ observe 不越界 → switchLevel 不抛 → enterLevel 不 catch → Bug 2+3 自解。Bug 2 额外加事务性防御(catch 回滚),Bug 3 加 sprite null 防御(可选)。

## 调研要点(worker Phase 1 先做,产出笔记)

- **SPD Level padding 机制**:`Level.length()` vs `width*height`(是否有 +1 边缘 padding?)`PathFinder.setMapSize` 与 `level.map` size 一致性。SPD 标准 level sewers 32×32 = 1024 是否含 padding。
- **observe 越界点全清单**:`Dungeon.observe` L955-957(NEIGHBOURS9)+ L950 `BArray.or(visited, heroFOV, pos, width, visited)`(pos 起点)+ `GameScene.updateFog` → 各 tilemap `updateArea`。小 map 边缘哪些算越界。
- **WallBlockingTilemap.updateArea/wall**:L216-240 `updateArea(cell, radius)` 用 `mapWidth`(=level.width)。小 map 边缘 cell+radius 越界?L197 `cell+mapWidth < size` 短路保护是否够。
- **DataDrivenLevel build/setSize/buildFlagMaps**:确认 setSize(16,16) 后 length=256,width/height=16,map[] 256。buildFlagMaps/buildWalls 是否正确。
- **test_safezone.json 改大可行性**:现有 16×16(256 tiles)→ 32×32(1024 tiles),现有内容居中 + 外围 wall 包围。entrance/mobs pos 调整(M4b/M4c 测试引用 pos 102/120/136/18)。
- **SPD 上游是否 Level 有 shadow padding**(`width+1`?`length = (width+1)*(height+1)`?查 Level.java length() 定义)。

## Files

- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaLevelService.java`(已改 Bug 0 + 改 Bug 2)— `enterLevel` 加 `Actor.clear()`(Bug 0,已 commit)+ catch 事务性回滚(Bug 2)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/DataDrivenLevel.java`(改 Bug 1)— 小 map bounds 保护(若选 X2/X3),或 build 加 padding
- `core/src/main/assets/mods/levels/test_safezone.json`(可能改)— map 改大 32×32(若选 X1)+ entrance/mobs pos 调整
- `core/src/test/.../modding/SafeZoneEnterTest.java`(新)— headless 测 enter 流程不越界(模拟小 map + 边缘 hero,测 DataDrivenLevel.create + switchLevel 核心逻辑;tilemap 渲染 headless 测不到,留实机)
- **上游改动**:0(SPD observe/tilemap 是上游,**不动**;DataDrivenLevel fork 加保护)

## 修复方案(定稿方向,worker 调研后细化 + codex 评审)

### Bug 1(observe/tilemap 越界)— 核心,worker 定方案

**X1(推荐):SafeZone map 加 padding**。test_safezone.json 改 32×32(外围 wall 包围现有 16×16 内容,entrance/mobs 内移)。符合 SPD level 假设。**最小代码改动(只改 JSON + pos)**。
- 现有 16×16 内容居中放 32×32 的 (8..23, 8..23) 区域,外围全 wall
- entrance: 17 → 内移(如 17+8*32+8 = 281?worker 按 32×32 重算)
- mobs pos 重算:rat_king 136 → +offset;lua_npc/shop/town_return 同(M4b/M4c 测试引用 pos,同步改测试)
- 验证:observe/tilemap 在 32×32 + hero 不触绝对边缘 → 不越界

**X2:DataDrivenLevel bounds 保护**。override 或在 buildFlagMaps 后修复边缘 cell;`wall(cell)`/observe 调用前 bounds check。**复杂,侵入 fork level,且 SPD observe 在上游(Dungeon.java)不易 override**。

**X3:X1 + X2 组合**。X1 主修(改 JSON),X2 防御(其他小 map 场景)。

**建议 X1**(改 JSON 到 32×32),worker 验证。若 worker 调研发现 observe 越界不止 padding 问题,补 X2 防御。

### Bug 2(enterLevel 事务性)

Bug 1 修后 observe 不越界 → switchLevel 不抛 → enterLevel 不 catch → Bug 2 自解。**额外防御**(worker 评估):enterLevel catch 时回滚 —— 保存 `prevLevel = Dungeon.level`(switchLevel 前),catch 恢复 `Dungeon.level = prevLevel` + Actor 重建。或更简:switchLevel 前预验证 level(create + dry-run observe?)。

### Bug 3(RatKing sprite null)

Bug 1+2 修后自解(GameScene 起来 → sprite 建)。**可选防御**:Mob.act sprite null 检查(SPD 上游,不动)。M4e 不直接修 Bug 3(靠 Bug 1+2)。

### Bug 0(Actor.clear,已修)

enterLevel L121 后加 `Actor.clear()`(本 worktree 已 commit)。**保留**。

## Steps

### 1. 调研(worker Phase 1)
- SPD Level padding(L.length() vs width*height;Level.java 源码)
- observe 越界全清单(L956 + BArray.or pos + updateFog tilemap updateArea)
- WallBlockingTilemap L195-240 小 map 边界行为
- DataDrivenLevel setSize/build/buildFlagMaps(16×16 是否正确)
- test_safezone.json 改 32×32 可行性 + pos 重算
- 产出:X1/X2/X3 选型 + pos 重算表

### 2. Bug 0(已 commit Actor.clear)— 保留,worker 确认无误

### 3. Bug 1 修复
- X1:test_safezone.json 改 32×32 + 外围 wall + entrance/mobs pos 内移
- 或 X2/X3:DataDrivenLevel bounds 保护
- worker 同步改 M4b/M4c/M4d 测试引用的 pos(LuaNpcTest/LuaShopTest/LuaLevelInjectTest 等用 test_safezone pos)

### 4. Bug 2 防御(可选)
- enterLevel catch 回滚 prevLevel,或预验证

### 5. 测试 SafeZoneEnterTest
- headless:DataDrivenLevel.fromAsset(test_safezone) + create + 模拟 observe/switchLevel 核心,断言不越界
- 注意:tilemap 渲染 headless 测不到,留实机

### 6. codex 评审 + 实机回归
- `codex exec --sandbox read-only`(沿 M4d/M5 workaround)
- `./gradlew :core:test`(216 + M4e 新增 + M4b/c/d 测试 pos 改后)
- **实机必做**(supervisor 设备 20210119085654):debug 入口进 SafeZone + 走动 + 回程,确认不崩

## Acceptance

- ✅ **debug 入口进 SafeZone 不崩**(实机验证,核心)
- ✅ SafeZone 内走动 / interact 不崩
- ✅ 回程(town_return / leaveLevel)不崩(若 test_mod on)
- ✅ observe/tilemap 在 SafeZone 不越界(Bug 1)
- ✅ enterLevel 失败时 state 一致(Bug 2,或 observe 不崩 → 不失败)
- ✅ 主线 mob 不残留(Bug 0,Actor.clear 已修)
- ✅ headless 测试覆盖 + 216 既有零回归(M4b/c/d pos 改后同步)
- ✅ 0 上游改动(SPD observe/tilemap 不动)
- ✅ codex_reviewer APPROVED

## 风险 + 注意

- **R1: 小 map × SPD 假设边界**。observe/tilemap/PathFinder 可能多处假设 ≥32×32。worker 调研全面越界清单,不只 L956 + wall。X1(改大)规避大部分;X2(防御)兜底。
- **R2: 实机验证必要**。headless 测不到 tilemap/observe 完整渲染。worker 桌面 GLFW 可能无显示(M4d 起已知)→ 实机由 supervisor 设备做。
- **R3: X1 pos 重算影响 M4b/c/d 测试**。LuaNpcTest/LuaShopTest/LuaLevelInjectTest 等引用 test_safezone pos(102/120/136/18)。改 JSON 后同步改测试 pos。
- **R4: C2/C4**。0 上游改动(SPD observe/tilemap 不动)。DataDrivenLevel fork 加保护 + test_safezone.json 改。Bug 0 的 Actor.clear 在 LuaLevelService(fork,已 commit)。
- **R5: test_mod default off**(M5c)。SafeZone 内 lua_npc/shop/town_return 不加载 → SafeZone 进入后空(只有 rat_king)。这是预期(M5c)。M4e 验收只要求"进入不崩",不要求 NPC 在(开 test_mod 才有,单独验证)。

## 参考

- 实机崩溃 stacktrace(2026-07-06,20210119085654):
  - `findPassable length=256 index=284/902`(Bug 0,Actor.clear 已修)
  - `WallBlockingTilemap.wall length=256 index=269`(Bug 1,observe→updateFog→updateArea)
  - `enterLevel failed`(Bug 2,catch)
  - `RatKing.act → CharSprite.hideAlert null`(Bug 3,失败后 GameScene 没起)
- `Dungeon.observe`(L927-960,L955-957 NEIGHBOURS9 无 bounds)
- `Dungeon.switchLevel`(L474-528,L520 observe)
- `WallBlockingTilemap.wall/updateArea`(L195-240)
- M4a `DataDrivenLevel.java`(setSize/build/create/fromJsonValue)
- `LuaLevelService.enterLevel`(L100-130)+ `LuaDebugService`(debug 按钮,L58 onClick → enterLevel)
- test_safezone.json(16×16,entrance=17,mobs: rat_king 136 / lua_npc:test_npc 102 / lua_shop:test_shop 120 / lua_npc:town_return 18)
- M5c 目录化(test_mod default off → lua_npc/shop skip,DataDrivenLevel log "unknown id skipping",graceful)
- modding 范式 + 约束 C1-C5 + CLAUDE.md

## 调研结论(Worker Phase 1 定稿)

### SPD padding 机制(澄清)

- `Level.setSize(w,h)`(L319-345):`length = w*h`(**无 +1 padding**)。`map/visited/heroFOV/passable/losBlocking/solid/...` 全是 `new T[length]`。`PathFinder.setMapSize(w,h)` 同步,NEIGHBOURS9 = `{-w-1,-w,-w+1,-1,0,+1,+w-1,+w,+w+1}`。
- 标准 SPD level 尺寸跨度大:`DeadEndLevel` 7×7、`LastLevel` 16×64、`PrisonBossLevel` 32×32、`DeadEndLevel` SIZE=5 内 EMPTY。**小 map 是 SPD 原生支持模式**,前提是布局正确(边缘 padding + entrance 不触绝对边缘)。
- `Level.cleanWalls()`(L921-939)遍历 NEIGHBOURS9 **有** bounds(`n >= 0 && n < length()`)→ `discoverable[]` 任意尺寸安全初始化。

### Bug 1 越界点全清单 + 根因再判定

1. `Dungeon.observe` L955-957 `visited[hero.pos+i]` for NEIGHBOURS9 —— **无 bounds**。hero 在边缘(pos < width 顶行 / pos%width==0 左列 / 底行 / 右列)→ `pos+i` 可 <0 或 >=length。
2. `WallBlockingTilemap.updateMapCell(cell)`(L68-192)大量 `wall(cell±mapWidth)`、`wall(cell±1±mapWidth)`、`fogHidden(cell±mapWidth)`,而 `wall(cell)`=`map[cell]`、`fogHidden(cell)`=`visited[cell]` **均无 bounds**。底行 cell(`cell+mapWidth >= length`)越界。
   - 缓解:`updateMap()` 全量(L50-60)把顶行(`cell-mapWidth<=0`)/底行(`cell+mapWidth>=size`)设 CLEARED → 不进 updateMapCell。但 `updateArea(x,y,w,h)`(L229-237,observe→`GameScene.updateFog` 增量路径)的 `data[cell]!=CLEARED` guard 依赖 updateMap 先跑过;边缘 cell / 首帧仍可能进 updateMapCell → 越界。
3. `BArray.or(visited, heroFOV, pos, width, visited)`(observe L950)—— pos 起点 `l + t*width`,l/t/width 全 clamp(L939-942)→ **bounded**。
4. `GameScene.updateFog(l,t,w,h)`(L1425-1429)→ `wallBlocking.updateArea(x,y,w,h)` + `fog.updateFogArea` —— 见 #2。
5. `GameScene.updateFog(cell,radius)`(L1432-1435)→ `wallBlocking.updateArea(cell,radius)`(L216-227 内部 clamp 后仍转 #2)。

**实机 stacktrace `wall index=269`(row=16 越界)+ `findPassable index=284/902` 都是 Bug 0 主线 mob 大 pos 残留触发**(主线 level 大,mob pos 是主线坐标,进 SafeZone 256-cell map 越界)。**Bug 0 修后,hero 在 entrance=17(16×16,坐标(1,1))初始 observe NEIGHBOURS9 = [0..34]、tilemap 访问全合法 —— 初始进入不崩**。

**但 16×16 活动区(col/row 1..14)距边缘仅 1 格**:玩家向下/左走 1 步即触顶行/左列 → NEIGHBOURS9 越界 / `wall(cell+mapWidth)` 越界。16×16 是"能进不能动"。这就是 X1 的根治价值。

### 选型定稿:X1(改 32×32),否定 X2/X3

- **X1 采用**:test_safezone.json 改 32×32,外围 8 格全 wall,现有 16×16 内容居中到 (8..23)×(8..23)。hero 活动区(col/row 8..23)距绝对边缘 ≥8,NEIGHBOURS9 与 SafeZone 内走动的增量 fog/tilemap 更新都有 padding。observe/tilemap 完整渲染仍留实机验证。
- **X2 否定**:`Dungeon.observe`/`WallBlockingTilemap` 是上游(C2/C4 硬约束 0 上游改动),fork 不易 override;且 X1 让所有 cell 访问合法,无需 bounds 保护。`DataDrivenLevel` 也无需改:`buildFlagMaps`/`cleanWalls` 已 bounded,`createMobs` pos 校验已有 `pos >= 0 && pos < length() && passable[pos]`。
- **X3 否定**:X1 已根治,X2 多余。
- **0 fork Java 改动**(纯 JSON + 新测试)。Bug 0 的 `Actor.clear()` 已 commit(LuaLevelService,本 worktree)。

### pos 重算表(16×16 → 32×32,内容偏移 (+8,+8),新 width=32)

| 项 | 旧 pos | 旧(col,row) | 新(col,row) | 新 pos | 新 tile |
|---|---|---|---|---|---|
| entrance | 17 | (1,1) | (9,9) | **297** | entrance |
| exit | 238 | (14,14) | (22,22) | **726** | exit |
| rat_king | 136 | (8,8) | (16,16) | **528** | floor |
| lua_npc:test_npc | 102 | (6,6) | (14,14) | **462** | floor |
| lua_shop:test_shop | 120 | (8,7) | (16,15) | **496** | floor |
| lua_npc:town_return | 18 | (2,1) | (10,9) | **298** | floor(与 entrance 相邻,语义保持) |
| gold(item) | 90 | (10,5) | (18,13) | **434** | floor |

新 pos 全在 [0,1024),col/row 全在 [8,23] → 距边缘 ≥8,passable(floor)。tiles 生成:32×32=1024,外围(row<8 或 row>23 或 col<8 或 col>23)全 `wall`,中心 16×16((8..23)×(8..23))放原 test_safezone 16×16 tiles(顶行全 wall、entrance 在 (9,9)、exit 在 (22,22)、其余 floor)。

### 测试影响(澄清 supervisor message)

- **无任何测试 `fromAsset` 读 test_safezone.json 文件**(全 grep 确认:`fromAsset`/`test_safezone.json` 在 `core/src/test/` 零命中)。`DataDrivenLevelTest` 用内嵌 `sampleJson()`(16×16,独立);`LuaLevelInjectTest` 用 `newStubLevel`(5×5/7×7);`LuaNpcTest`/`LuaShopTest` 不引用 test_safezone;`ModScannerTest` 只检查 "test_safezone" 不在 mod ids。
- **改 JSON 不影响现有 216 测试**。supervisor message 的"同步改 M4b/c/d 测试 pos"基于误判 —— 实际无需改测试 pos。
- `DataDrivenLevelTest` 内嵌 sampleJson 仍 16×16(测 parse 正确性)—— **保留不动**(它正好佐证 16×16 parse/build 正常,Bug 在 SPD tilemap/observe runtime 而非 DataDrivenLevel)。

### Bug 2 防御:不加(YAGNI)

Bug 0(已修)+ Bug 1(X1)修后,observe 不越界 → switchLevel 不抛 → enterLevel 不进 catch。现有 catch(LuaLevelService L132-136 LOG only)足够。加事务性回滚(prevLevel 保存 + Actor.clear/重建)复杂且难 headless 测(要 mock switchLevel 抛异常),YAGNI。**codex 评审确认此判断**。

### Bug 3:不直接修(Bug 1+2 修后自解)

Bug 1 修后 switchLevel 完整执行 → `Game.switchScene(GameScene)` 起来 → RatKing sprite 建立 → `Mob.act` 不 NPE。

### SafeZoneEnterTest 覆盖(headless,新增)

- `DataDrivenLevel.fromAsset("mods/levels/test_safezone.json","test_safezone")` 非 null
- `create()` 成功;`width()/height()/length()` = 32/32/1024
- `entrance()`=297、mobs pos(rat_king 528 等)全在 [0,1024) 且 `passable[pos]`
- `cleanWalls()`(discoverable 初始化)不抛
- 模拟 observe 核心:hero 在 entrance(297)+ 活动区四边缘(col=8/23、row=8/23 各取代表点)的 `hero.pos + PathFinder.NEIGHBOURS9[i]` 全在 [0,1024)
- tilemap 渲染 headless 测不到,留实机(supervisor 设备 20210119085654)
