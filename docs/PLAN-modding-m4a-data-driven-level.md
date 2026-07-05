# PLAN: M4a — DataDrivenLevel(JSON 数据驱动关卡)

## Goal

让 SPD 能加载 JSON 描述的固定地图关卡,作为 Lua/Tiled 关卡和城镇枢纽的**运行时基础**。MVP 不改关卡图流转(`InterlevelScene`),用 debug 入口验证可行性。

## Context

M0-M3 已让 Lua 定义物品/mob/宠物/职业/spell,但**关卡仍是硬编码程序化生成**(`RegularLevel.build()` + Painter/Room)。M4 要让 Lua 也能定义关卡。第一步是数据驱动 Level 的运行时机制——参考 Remixed `PredesignedLevel.java:26`(从 JSON 读 map/entrance/exit/mobs/items)。

**为何先做这个(不先做图结构)**:数据驱动 Level 是所有自定义地图(城镇/副本/Lua 关卡)的共同基础;图结构(M4b)和城镇(M4c)都消费它。先做最小 MVP,**不动 `InterlevelScene`**(C4 上游 merge 友好),用 debug 入口验证。

**调研结论**(来自 Explore 报告):
- SPD `Level` 类层次:`Level`(abstract,`Level.java:123`),抽象方法 `build()`/`createMobs()`/`createItems()`;`RegularLevel` extends Level,五大区 + Boss 关
- SPD 关卡硬编码线性(`Dungeon.depth++/--` + `branch` int),**无节点图抽象**
- SPD **无城镇/Hub/SafeZone** 概念
- Remixed `PredesignedLevel` 从 JSON 读 5 层(Base/Deco/Deco2/Roof_Base/Roof_Deco)+ objects + multiexit
- **运行时不解析 `.tmx`**(Remixed 也没做),离线 tmx→JSON 工具链后续

## Level Lifecycle Notes(R1 调研产出 —— 实现时务必遵循,并写入代码注释)

### `Level.create()` 调用链(`Level.java:215-317`)
```
create():
  Random.pushGenerator(Dungeon.seedCurDepth())
  // ↓ 仅当 !bossLevel() && branch==0 时:随机投放食物/力量药/升级卷/印章 + roll feeling
  // ↓ DataDrivenLevel 必须 override create() 跳过这段(fixed map,无随机)
  do { 初始化 collections } while (!build())   // build() 内必须 setSize()
  buildFlagMaps()      // 由 map[] 推导 passable/losBlocking/solid/water/.../openSpace(自动)
  cleanWalls()         // 推导 discoverable[](自动)
  createMobs()         // abstract
  createItems()        // abstract
  Random.popGenerator()
```

### `build()` 必须做什么
- **必须**先调 `setSize(w, h)`(`Level.java:319`)—— 它分配 `map[]`/`visited[]`/`heroFOV`/所有 flag 数组 + `PathFinder.setMapSize`。`create()` 不替你分配。
- 然后 `map[i] = tileNameToId(...)` 铺 tiles。
- **不挂任何 LevelTransition**(SafeZone 落点靠 `entranceCell` 字段 + override `entrance()`,见下)。
- 返回 `true`(固定地图不重试)。

### 入口/出口 = override `entrance()`,**不加任何 LevelTransition**(codex round-1 must-fix)
- `Level.entrance()`(`Level.java:522`)默认扫 `transitions` 找 REGULAR_ENTRANCE。但**任何 transition 都是可交互的** —— 踩到 transition cell → `activateTransition()`(`Level.java:566`)→ 切 `InterlevelScene`,且 `LevelTransition` 构造器(`LevelTransition.java:65`)会按当前 `Dungeon.depth/branch` 自动填 destDepth/destBranch。SafeZone 里 depth 是真实层(如 5),REGULAR_ENTRANCE 会把 destDepth 填成 4,hero 走回入口格就被传到真实层 4 = 污染。
- **修正**:SafeZone **完全不挂 transition**(`transitions` 留空),改为:
  - `private int entranceCell;` 字段(JSON 解析时填)
  - `@Override public int entrance(){ return entranceCell; }`(直接返回,绕开 transitions 扫描)
  - exit 同理:只放视觉 tile(Terrain.EXIT),不挂 transition,踩了无反应
- `getTransition(int cell)` 在 transitions 为空时返回 null,所以 `activateTransition` 路径自然失效 —— 玩家在 SafeZone 任意格走动都不会触发场景切换,只能用 debug 按钮离开。
- Bundle:`entranceCell` 进 store/restore(round-trip 测试要 assert 它)。

### 进入/离开 SafeZone 的场景编排
- 进入:`Dungeon.saveAll()`(先持久化真实进度)→ 构造 DataDrivenLevel + `level.create()` → `Dungeon.switchLevel(level, level.entrance())`(`Dungeon.java:474`,内含 `Actor.init()`+`addRespawner()`)→ `Game.switchScene(GameScene.class)`。
- GameScene.create() 读 `Dungeon.level`(tilesTex/waterTex/addVisuals/width/height)—— 只要 DataDrivenLevel 这些都有效就能渲染。
- 离开:`InterlevelScene.Mode.CONTINUE` + `Game.switchScene(InterlevelScene.class)` —— CONTINUE 走 `Dungeon.loadGame`(全量从存档恢复 hero+level+depth),零污染。**不改 InterlevelScene 代码**(R5),只是用它。

### 防存档污染(关键,R3/R5)
- `GameScene.onPause()`(`GameScene.java:809`)在切场景/退后台时调 `Dungeon.saveAll()` —— 会把 SafeZone 当当前 depth 存下来,覆盖真实关卡。
- **守卫**:在 `Dungeon.saveAll()` 加 `if (level != null && level.isEphemeral()) return;`。`Level` 加 `public boolean isEphemeral(){ return false; }`,DataDrivenLevel override 为 `true`。
- 自清:一旦 CONTINUE 把 `Dungeon.level` 换回真实关卡,`isEphemeral()` 自然变 false,存档恢复。无需手动 flag,无状态机。
- **上游改动**:Level.java(+1 方法,fork 注释)+ Dungeon.java(+1 行守卫,fork 注释)。PLAN 原约束 "≤1 文件" 放宽到 2 文件,理由:无守卫则 SafeZone 必然污染存档(R5 acceptance 直接挂)。codex 重点 review 这个取舍。

### SafeZone 不刷怪 + 死亡守卫(R4)
- override `addRespawner()` 为 no-op(`Level.java:711`)—— 不挂 MobSpawner。
- override `mobLimit()` 返回 0。
- test_safezone.json 只放被动 NPC(RatKing)+ 金币,**不放敌对 mob** —— 自然不会因敌对怪死。
- **死亡守卫(codex Phase-2 round-1 must-fix)**:英雄可能带着饥饿/燃烧/毒/流血进 SafeZone 而死,而 `Hero.die`→`reallyDie`→`Dungeon.deleteGame` + `Dungeon.fail`→`Rankings.submit` 会删真实存档+污染排行榜。守卫:
  - `LuaLevelService.interceptDeath(hero, cause)`:`inDataLevel()` 时 `Game.runOnRenderThread(leaveLevel)` 并 return true(镜像 save-slot 的 interceptDeath 模式)。
  - `Hero.die()` 在 save-slot 守卫**之前**插 `if (LuaLevelService.interceptDeath(this, cause)) return;`。
  - `Dungeon.fail()` 首行 `if (level!=null && level.isEphemeral()) return;`(belt-and-suspenders)。
  - 效果:SafeZone 内死亡 → 直接 CONTINUE 恢复进入前存档(hero 存活),不触发 Rankings/Bones/deleteGame。

### Bundle 持久化(R3,主要给单元测试 round-trip 用)
- `Level` 的 store/restore 已经 round-trip `map/w/h/transitions/mobs/heaps/...`。
- restoreFromBundle **不调 build()** —— map 从 bundle 拿。所以 `lua_level_id` 只为标记类型 + 重新挂 JSON 定义(无关键非持久字段,沿用范式保持一致)。
- DataDrivenLevel override store/restore 加 `lua_level_id`,hydrate 从 `LuaLevelRegistry` 拿定义。
- 生产路径下 SafeZone 永不被存(isEphemeral 守卫),bundle 路径只被单元测试 exercise。

### Terrain tile 名 → 常量映射(R2,完整)
`chasm=0 empty=1 grass=2 empty_well=3 wall=4 door=5 open_door=6 entrance=7 exit=8 embers=9 locked_door=10 pedestal=11 wall_deco=12 barricade=13 empty_sp=14 high_grass=15 secret_door=16 secret_trap=17 trap=18 inactive_trap=19 empty_deco=20 locked_exit=21 unlocked_exit=22 custom_deco=23 well=24 statue=25 statue_sp=26 bookshelf=27 alchemy=28 water=29 furrowed_grass=30 crystal_door=31 custom_deco_empty=32 region_deco=33 region_deco_alt=34 mine_crystal=35 mine_boulder=36 entrance_sp=37 hero_lkd_dr=38`
- JSON 用字符串名(`wall`/`floor`(=`empty`)/`grass`/`door`/`entrance`/`exit`/`water`/...),`floor` 作为 `empty` 的友好别名。
- 未知名 → 默认 `wall`(安全,不会越界或崩)。

### JSON 解析
- 用 `com.badlogic.gdx.utils.JsonReader.parse(String)` → `JsonValue`(纯 Java,headless 测试可用,不依赖 Gdx.files)。
- DataDrivenLevel 提供 `fromJsonValue(JsonValue)` 入口;`LuaLevelService` 从 `Gdx.files.internal("mods/levels/<id>.json")` 读文件后调它。测试直接传 JsonValue,绕过文件 I/O。

## Files

- `core/.../modding/DataDrivenLevel.java`(新,extends Level)— 核心;内含 `create()`/`build()`/`createMobs()`/`createItems()` override + tile 名映射 + isEphemeral + Bundle lua_level_id
- `core/.../modding/LuaLevelRegistry.java`(新)— Registry Map<id, def>,沿用 LuaMobRegistry 范式
- `core/.../modding/LuaLevelService.java`(新)— `enterLevel(id)`/`leaveLevel()`:场景编排 + isEphemeral 守卫协调
- `core/.../modding/LuaEngine.java`(改)— 加 `register_level` 全局 + `loadLevelScripts`? 否:level 走 JSON 文件,不走 Lua 脚本目录。`register_level` 仅注册元数据(id→json 路径或内联),实际渲染读 JSON。MVP 可只走 JSON 文件,`register_level` 留作 Lua 注册入口(M4b 图结构用)。
- `core/.../modding/LuaDebugService.java`(改)— 加 "进入 Test SafeZone" / "离开 SafeZone" debug 按钮
- `assets/mods/levels/test_safezone.json`(新)— 测试关卡(16×16:围墙+地板+入口楼梯+RatKing+金币)
- **上游改动**:Level.java(+1 方法 isEphemable)+ Dungeon.java(+saveAll 守卫 +fail 守卫)+ Hero.java(+die 守卫,沿用 SaveSlot interceptDeath 模式),均带 fork 注释。proguard 仅注释(C5)

## Steps

### 1. ✅ Level 生命周期调研(已完成,见上 "Level Lifecycle Notes")

### 2. JSON 格式(MVP)

```json
{
  "id": "test_safezone",
  "name": "Test SafeZone",
  "width": 16, "height": 16,
  "tiles": ["wall","wall",...,"floor",...],
  "entrance": 144,
  "exit": 16,
  "safe": true,
  "mobs": [{"type":"rat_king","pos":80}],
  "items": [{"type":"gold","pos":100,"quantity":50}]
}
```
- `tiles.length` 必须 == `width*height`,否则抛 `IllegalArgumentException`。
- tile 用字符串名(映射见上)。`floor`=`empty` 的别名。未知名 → `wall`。
- `entrance`/`exit` 是 cell index(`y*width+x`)。
- `mobs[].type` 走小白名单(MVP:`rat_king`→RatKing.class,未知名跳过并 log,不崩)。`items[].type` 同理(`gold`→`new Gold(quantity)`)。Lua 注册的 mob/物品接入留 M4b。
- `safe:true` → DataDrivenLevel 设 `isEphemeral` 行为 + 不刷怪 + 不加 respawner。

### 3. DataDrivenLevel 实现

字段:
- `private String luaLevelId;`(Bundle TAG `lua_level_id`)
- `private int jsonWidth, jsonHeight;`(供 build() setSize;恢复时从 bundle w/h 拿)
- `private String[] jsonTiles;`(build 用,恢复路径不需要)
- mob/item spec 列表(构造时持有,build 后消费)

方法:
- `static DataDrivenLevel fromJsonValue(JsonValue root, String id)` — 解析 JSON,填字段,返回未 create 的实例
- `@Override public boolean isEphemeral(){ return true; }`
- `@Override public void create()` — 镜像 `Level.create()` 但**跳过** random item pre-spawn + feeling 块(固定地图);调 build/buildFlagMaps/cleanWalls/createMobs/createItems
- `@Override protected boolean build()` — `setSize(jsonWidth,jsonHeight)`;铺 tiles;**不挂任何 LevelTransition**(见上"入口/出口");设 `entranceCell` 字段
- `@Override public int entrance(){ return entranceCell; }` —— 绕开 transitions 扫描,落点直接来自 JSON(codex round-1 must-fix)
- `@Override protected void createMobs()` — 按 JSON spec 反射创建(RatKing 等),设 pos,加入 `mobs`
- `@Override protected void createItems()` — 按 JSON spec 创建(Gold 等),`drop(item, pos)`
- `@Override public void addRespawner(){}` — no-op(safe 不刷怪)
- `@Override public int mobLimit(){ return 0; }`
- `@Override public String tilesTex(){ return Assets.Environment.TILES_SEWERS; }`(MVP 复用下水道贴图)
- `@Override public String waterTex(){ return Assets.Environment.WATER_SEWERS; }`
- `@Override storeInBundle/restoreFromBundle` — super + `lua_level_id` + `entranceCell`;restore 时从 `LuaLevelRegistry` 拿定义 re-hydrate(无关键状态,沿用范式)
- `private static final Map<String,Integer> TILE_NAMES` — tile 名→Terrain 常量(见上完整映射);`private static int tileNameToId(String)`(`floor`→`empty`,未知名→`wall`+log)
- `private static final Map<String,Class<? extends Mob>> MOB_TYPES` — `rat_king`→RatKing.class
- `private static final Map<String,ItemFactory>` ITEM_TYPES — `gold`→(qty)->new Gold(qty)

### 4. LuaLevelRegistry

沿用 `LuaMobRegistry` 范式:`Map<String, LuaTable>`,register/getTable/contains/clear/size。MVP 主要给 Bundle re-hydrate 用;Lua `register_level` 把(id → JSON 路径/内联)注册进来。`DataDrivenLevel.restoreFromBundle` 通过 `lua_level_id` 查它。

### 5. LuaLevelService

- `enterLevel(String id)`:
  1. guard:`DeviceCompat.isDebug()` + hero!=null && alive + `Dungeon.level` 非空
  2. `Dungeon.saveAll()` —— 先持久化真实进度
  3. 从 `mods/levels/<id>.json` 读 JSON(`Gdx.files.internal`)→ `DataDrivenLevel.fromJsonValue`
  4. `level.create()`
  5. `Dungeon.switchLevel(level, level.entrance())`
  6. `Game.switchScene(GameScene.class)`
  7. 记录 `inLuaLevel=true`(可选,主要靠 isEphemeral 自清)
  - 全程 try/catch,error → `Gdx.app.error` + 不切场景(绝不半个状态)
- `leaveLevel()`:
  1. guard:当前 `Dungeon.level instanceof DataDrivenLevel`
  2. `InterlevelScene.mode = InterlevelScene.Mode.CONTINUE`
  3. `Game.switchScene(InterlevelScene.class)` —— CONTINUE 全量 loadGame 恢复,`Dungeon.level` 换回真实关卡,isEphemeral 自清

### 6. LuaEngine.register_level

加 `globals.set("register_level", new RegisterLevelFunction())`,镜像 `RegisterMobFunction`:校验 `id` + `name`,可选 `json`(内联)或 `path`(assets 路径)。MVP 范围:`register_level{id=..., name=..., path="mods/levels/x.json"}` → `LuaLevelRegistry.register(id, table)`。实际渲染时 `LuaLevelService.enterLevel` 优先读 table 里的 path,从 assets 加载 JSON。

### 7. LuaDebugService debug 按钮

- `addMenuButton` 加第二个按钮 `Lua: 进入 Test SafeZone (debug)` → `wnd.hide()` + `LuaLevelService.enterLevel("test_safezone")`
- 当前 `Dungeon.level instanceof DataDrivenLevel` 时,按钮 label 改为 `Lua: 离开 SafeZone (debug)` → `LuaLevelService.leaveLevel()`
- 仍 `DeviceCompat.isDebug()` 守卫

### 8. 上游 hook(2 处,均 1 行 + fork 注释)

- `Level.java`(abstract `Level`):加 `public boolean isEphemeral(){ return false; }`(默认非临时;DataDrivenLevel override)
- `Dungeon.saveAll()` 首行:`if (level != null && level.isEphemeral()) return;`
- proguard-rules.pro:加注释说明 `-keepnames com.shatteredpixel.**` + `-keep class * implements Bundlable` 已覆盖,无需新规则(C5)

### 9. test_safezone.json

16×16:外圈 `wall`,内部 `floor`,一格 `entrance`(楼梯上,pos 144 = row9,col0 附近),一格 `exit`(楼梯下,视觉,pos 16),一个 `rat_king`(pos ~80),一堆 `gold`(pos ~100, qty 50)。worker 实际写时核对 pos 在地图内且 passable。

### 10. 单元测试

`core/src/test/java/.../modding/DataDrivenLevelTest.java`:
- `testJsonRoundTrip`:JSON 字符串 → `DataDrivenLevel.fromJsonValue` → 手动 `create()`(headless,需 mock Dungeon.level? 见下)→ `storeInBundle` → `restoreFromBundle` → assert tiles/width/height/entrance 一致。
- **headless 难点**:`Level.create()`/`buildFlagMaps`/`switchLevel` 依赖 `Dungeon` 静态 + `PathFinder` + `Random`。测试改为:只测 (a) JSON 解析正确性(tiles 数组→Terrain 常量映射、entrance/exit cell) 和 (b) `tileNameToId` 映射;round-trip 若需完整 Level 状态则降级为 store/restore `lua_level_id` + 手构 map[] 的最小验证。worker 实现时按 headless 可行性裁剪,但至少覆盖 JSON→tile 映射 + entrance cell 解析。

### 11. 回归验证

- `./gradlew :desktop:debug` 编译过
- `./gradlew :core:test` 全绿(含新测试)
- 装机:debug 入口能进 SafeZone,看到正确 tiles/entrance/RatKing/金币
- SafeZone 内不刷怪、不存档(故意切后台再 Continue,仍回原关卡)
- 离开 SafeZone → 回原关卡,depth/Rankings/GamesInProgress 无污染
- **原版一周目可正常开局**(C3):`git diff master...HEAD -- 'core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/(?!modding/)'` 应只有 Level.java + Dungeon.java 各 1 处 fork 注释行

## Acceptance

- ✅ 游戏里通过 debug 按钮进入 JSON 定义的 SafeZone 关卡
- ✅ 关卡 tiles/entrance/exit/mobs/items 正确渲染
- ✅ SafeZone 不刷怪
- ✅ 离开 SafeZone 回原版关卡,无 depth/Rankings/GamesInProgress 污染
- ✅ 原版一周目可正常开局(C3 回归基线)
- ✅ ≥1 单元测试:JSON 解析 + DataDrivenLevel round-trip(tiles/entrance/exit 不变)
- ✅ modding/ 子包,C2 隔离
- ✅ 上游改动 = 3 文件(Level.java +isEphemeral、Dungeon.java +saveAll/fail 守卫、Hero.java +die 守卫),均带 fork 注释、沿用 SaveSlot interceptDeath 模式。理由:无守卫则 SafeZone 死亡会触发 Rankings/deleteGame(R4/R5 acceptance 挂)
- ✅ codex_reviewer APPROVED(走 M3 既有的 tmux send-keys 评审方式)

## 风险 + 注意

- **R1: Level 生命周期复杂**。SPD Level 进入流程涉及多步(build/paint/decorate/createMobs/createItems/视觉)。DataDrivenLevel 必须 fit,不能只 build。**worker 第一步必须调研清楚,这是最大风险点**。
- **R2: Terrain tile 编码**。tiles[] 用 `Terrain.java` 常量。worker 调研后建 tile 名→常量映射,JSON 用字符串名避免裸 int。
- **R3: Bundle 持久化**。DataDrivenLevel 走 Bundle。hydrate 保留 `lua_level_id`(沿用 `lua_<type>_id` 范式),恢复时重新 build。
- **R4: SafeZone 与 Rankings**。玩家在 SafeZone 死,Rankings 怎么记?MVP 阶段 SafeZone 设为无敌或屏蔽死亡提交。参考 save slot 的 `WndResurrect.instance` 守卫思路。
- **R5: 不动 InterlevelScene 代码**(C4)。debug 入口直接 build+switchLevel+切 GameScene(不走 depth++ 链路);离开 SafeZone 复用 InterlevelScene 的 CONTINUE 模式(用,不改)。防存档污染靠 `Level.isEphemeral()` + `Dungeon.saveAll` 守卫(见上),需 2 处 1 行级上游 hook。M4b 再做图结构。
- **R6: C5 proguard**。DataDrivenLevel 走反射 Bundle,加 keep 规则到 `android/proguard-rules.pro`。

## 参考

- Remixed `PredesignedLevel.java:26`(JSON 关卡运行时)
- Remixed `CustomLevel.java:48-60`(Lua 关卡 = Lua 生成 JSON)
- SPD `Level.java:123`(抽象基类,build/createMobs/createItems)
- SPD `SewerLevel.java` + Painter(具体 Level 实现)
- modding 范式:`LuaMob.java` + `LuaMobRegistry.java`(Registry + hydrate + lua_<type>_id)
- 约束 C1-C5 + CLAUDE.md(modding 子包 + 上游最小 hook + proguard)

## Review Log

### Phase 1 Round 1(codex terminal e8180d35)— 1 must-fix → 已修
- **[must-fix] entrance transition 仍可触发 InterlevelScene**:REGULAR_ENTRANCE transition 踩到会走 activateTransition(),且 LevelTransition 构造器按当前 depth 自动填 destDepth,会污染真实层。
- **修复**:SafeZone 完全不挂 transition,改用 `entranceCell` 字段 + override `entrance()` 直接返回(见 "入口/出口" 节 + Step 3)。transitions 为空时 getTransition(cell) 返回 null,activateTransition 路径自然失效。

### Phase 1 Round 2 — APPROVED(codex 确认 must-fix 已解决,无新阻断)

### Phase 2 Round 1(codex terminal e8180d35)— 1 must-fix → 已修
- **[must-fix] SafeZone 内死亡仍会提交 Rankings 并删除真实存档**:`isEphemeral()` 只守住 `Dungeon.saveAll()`,没守住死亡链路。英雄带饥饿/燃烧/毒/流血进 SafeZone 可死,`Hero.die`→`reallyDie`→`Dungeon.deleteGame` + `Dungeon.fail`→`Rankings.submit` 会删档+污染排行榜。
- **修复**:`LuaLevelService.interceptDeath`(镜像 SaveSlot 模式,runOnRenderThread→leaveLevel);`Hero.die` 在 save-slot 守卫前插 ephemeral 守卫;`Dungeon.fail` 首行 ephemeral 早返。上游扩到 3 文件(见 Acceptance/Files)。

### Terrain 名映射子集(已知限制,codex Phase-2 round-1 提示,非 must-fix)
- 当前 TILE_NAMES 覆盖常用 tile(含 floor→empty 别名),未覆盖 SECRET_TRAP/TRAP/INACTIVE_TRAP/CRYSTAL_DOOR/REGION_DECO*/MINE_*/ENTRANCE_SP/HERO_LKD_DR 等。未知名安全降级为 wall 并 log。MVP test_safezone 只用 wall/floor/entrance/exit,不受影响。M4b 扩展图结构时补全映射。


