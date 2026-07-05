# PLAN: M4d — 城镇可达(主线注入传送 + 状态保留)

## Goal

主线某层 spawn 传送 Lua NPC,interact 进 SafeZone;SafeZone 加回程 NPC 回主线。**关键:修复 m4a leaveLevel 的状态保留缺陷**——玩家在 SafeZone 的行为(买的物品/扣的金币/对话)必须带回主线,否则 m4c 商店无意义。isDebug 守护(M5 前不破坏原版 release)。

## Context

m4a-c 完成(SafeZone + Lua NPC + 商店,debug 入口可达)。但有两个缺口:
1. **可达性**:目前只能 debug 按钮进 SafeZone,主线无入口
2. **状态保留缺陷(R4,关键)**:m4a `leaveLevel` 走 `InterlevelScene.CONTINUE` → `Dungeon.loadGame` 从存档全量恢复;而 `isEphemeral` 守卫让 `Dungeon.saveAll` 在 SafeZone 跳过存档。结果:进 SafeZone 时 saveAll 存的是"进 SafeZone 前"状态,玩家在 SafeZone 买东西/对话后 leaveLevel → CONTINUE 恢复进 SafeZone 前状态 → **SafeZone 内的变化全丢**

M4d 同时解决两个:主线注入传送 NPC + 修复 leaveLevel 状态保留。

**调研要点**:
- `RegularLevel.createMobs:220`(原版 mob 生成,注入点)
- `Level.java:435`(`mobs.add(mob)` spawn API)+ `Level.java:749`(`spawnMob`)
- m4a `LuaLevelService.enterLevel:79` / `leaveLevel:115` / `inDataLevel:71`(现状)
- m4a `Dungeon.saveAll` 的 isEphemeral 守卫 + `InterlevelScene.CONTINUE` 恢复链路
- `DeviceCompat.isDebug()`(isDebug 守卫,沿用 LuaDebugService 模式)

## Files

- `core/.../modding/LuaLevelService.java`(改)— 新 `injectLevelNpcs(level)`(isDebug + depth 路由)+ **重写 `leaveLevel` 保留 hero 状态**
- `core/.../modding/LuaLevelRegistry.java`(可能改)— depth→NPC 配置(MVP 硬编码或简单 JSON)
- `core/.../levels/RegularLevel.java`(改,上游 +1 行)— `createMobs` 末尾 hook
- `assets/scripts/npcs/town_portal.lua`(新)— 传送 NPC(interact → enterLevel)
- `assets/scripts/npcs/town_return.lua`(新)— 回程 NPC(SafeZone 内 interact → leaveLevel)
- `assets/mods/levels/test_safezone.json`(改)— 加回程 NPC
- `core/src/test/.../modding/LuaLevelInjectTest.java`(新)— inject + 状态保留测试
- **上游改动**:`RegularLevel.java`(+1 行 hook,fork 注释);可能 `Dungeon.java`/`InterlevelScene.java`(若 leaveLevel 重写需要,最小 hook,C4)

## 调研结论(worker Phase 1 产出,2026-07-06)

### R4 缺陷代码级确认

- `LuaLevelService.leaveLevel:115-122` 设 `InterlevelScene.Mode.CONTINUE` → `InterlevelScene.restore():733` → `Dungeon.loadGame(curSlot):739`
- `Dungeon.loadGame:739-840` 从存档**替换**:`hero=(Hero)bundle.get(HERO)`(L828-829)、`gold=bundle.getInt(GOLD)`(L834)、`depth`(L831)、`branch`(L832)
- 该存档是 `enterLevel:93` 进 SafeZone **之前**的 `saveAll()` 快照;`saveAll:716-722` 因 `level.isEphemeral()` 早返回,SafeZone 期间无任何存档
- 结论:SafeZone 内 `LuaShopNpc.attemptBuy:217`(扣 `Dungeon.gold`)+ `:223` `item.doPickUp(hero)`(进 `hero.belongings`)的改动,leaveLevel 后**全丢**。缺陷确认。

### 关键字段可见性

- `Dungeon.hero`(L185)、`Dungeon.gold`(L199)、`Dungeon.depth`(L190)、`Dungeon.branch`(L194)全是 `public static`,可直接赋值
- `Actor.init():194` 只把 `Dungeon.hero` + `Dungeon.level.mobs` + blobs 加入 actor 队列,**不改动** hero 的 HP/belongings/buffs
- `Actor.clear()`(loadGame:751、loadLevel:845 各调一次)只清 actor 队列,不销毁 hero 对象引用 —— 只要外部强引用 `liveHero`,对象存活

### 注入时机确认

- `RegularLevel.createMobs()` 末尾(L308-314 for-loop 后,L316 闭括号前)。此时 `passable[]` 已由 `buildFlagMaps()` 填好(createMobs 内 L268-275 已在用 `passable[mob.pos]`)
- `Dungeon.depth` 在 level 构建前已设(`InterlevelScene.descend` 先 `depth++` 再 `newLevel`),createMobs 时 depth 正确
- `DataDrivenLevel` 自己 override `createMobs`(L204),不走 RegularLevel 路径 —— SafeZone 不会被 inject(MVP 不需要)

## R4 状态保留方案(定稿:Option C — 同步切场景 + hero 内存引用保留)

**核心洞察**:leaveLevel 时 `Dungeon.hero` 这个 Java 对象**就是**带有 SafeZone 改动(belongings/gold/HP/buffs)的英雄。不需要 bundle 序列化 —— 只要**不被 loadGame 替换掉**即可。

**实施**(全部在 `LuaLevelService`,不动 Dungeon/InterlevelScene,C4 零改动):

```java
public static void leaveLevel() {
    if (!inDataLevel()) { log; return; }
    if (Dungeon.hero == null) { log; return; }
    // 死亡逃逸(interceptDeath 路径):hero 已死,无可保留,走原 CONTINUE 全量恢复
    if (!Dungeon.hero.isAlive()) {
        InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
        Game.switchScene(InterlevelScene.class);
        return;
    }
    // 活着:保留 hero 内存状态,只从存档恢复 level/depth/branch
    Hero liveHero = Dungeon.hero;        // 带 SafeZone 改动的对象引用
    int liveGold = Dungeon.gold;
    QuickSlot liveQS = Dungeon.quickslot;     // codex round-1 must-fix:保 quickslot 引用
    Bundle liveNextId = new Bundle();
    Actor.storeNextID(liveNextId);            // codex round-1 should-fix:保 nextID
    try {
        Dungeon.quickslot = new QuickSlot();  // throwaway:loadGame 会 reset()+restorePlaceholders 到它,用完即弃
        Dungeon.loadGame(curSlot);       // hero/gold/quickslot/nextID/depth/branch 全被存档覆盖
        Level lvl = Dungeon.loadLevel(curSlot);  // Actor.clear() 再清一次
        int mainPos = Dungeon.hero.pos;  // 存档恢复出的 hero 在主线的 pos(有效)
        // 装回 live 状态
        Dungeon.hero = liveHero;         // 带 SafeZone 改动的 hero(belongings/HP/buffs 全在)
        Dungeon.gold = liveGold;         // 扣减后的金币
        Dungeon.quickslot = liveQS;      // live quickslot(slot 引用指向 liveHero.belongings 的 Item)
        Actor.restoreNextID(liveNextId); // 防止后续 actor ID 与 SafeZone 内分配的碰撞
        Dungeon.switchLevel(lvl, mainPos);  // 装 lvl + 设 liveHero.pos + Actor.init() 重排
        Game.switchScene(GameScene.class);
    } catch (IOException e) {
        log; 兜底:先恢复 live 状态再走 CONTINUE(避免半切状态)
    }
}
```

**为什么对**:
1. `liveHero` 是同一对象 —— belongings(买的物品)/HP/buffs 全在,不需要 bundle round-trip
2. `loadGame` 新建的 hero 只为取 `mainPos`(存档里 hero 在主线的合法坐标),用完即弃
3. `switchLevel(mainPos)` 把 liveHero 放回主线 entrance/exit 附近,`Actor.init()` 重新入队
4. depth/branch 由 loadGame 从存档恢复(m4a 没在 SafeZone 改 depth,存档值 == 进入前值,正确)
5. **quickslot**:loadGame 内部 `Dungeon.quickslot.reset()` + `restorePlaceholders()` 只会打到 throwaway 实例;liveQS 的 `slots[]` 引用指向 liveHero.belongings 的真实 Item 对象,装回后 quickslot 槽位仍指向玩家实际拥有的物品(含 SafeZone 买的并 quickslot 的物品)
6. **nextID**:loadGame 把 `Actor.nextID` 重置为存档值(进入 SafeZone 前);SafeZone 内分配的 buff/actor id 比它大。storeNextID/restoreNextID 用 live 值覆盖回去,避免后续新 actor 拿到已占用的 id

**死亡分支保留原 CONTINUE**:hero 死了没什么可保留,全量恢复逃出 SafeZone(沿用 m4a interceptDeath 语义)。

**为什么不走 InterlevelScene 的 loading 界面**:enterLevel 本身就是同步切(`switchLevel` + `switchScene(GameScene)`),leaveLevel 对称即可;单 level 加载很快,无需 loading 屏。换来 C4 零改动(RegularLevel 外不动上游)。

**保留范围(M4d MVP 显式边界,codex round-1 must-fix #2)**:
- ✅ 保留:`hero.belongings`(买的物品)+ `Dungeon.gold`(扣减后)+ `hero.HP`/buffs(对象引用)+ `Dungeon.quickslot`(槽位)+ `Actor.nextID`
- ❌ 不保留(接受丢失):SafeZone 内的 item identify / consume、`LimitedDrops`、quest 状态、`Statistics`、`Badges`、`Notes`、`Generator` —— 这些在 SafeZone 商店 hub 场景下不会发生有意义变化(无怪、无 quest NPC、玩家不会在 SafeZone 喝药/读卷)。验收只要求"买的物品 + 金币带回主线",此边界与之匹配

**可测缝隙**(headless 不能跑真实 loadGame/switchScene,但能测核心机制):
- `captureLiveState()` → `LiveHeroState(hero, gold, quickslot, nextIdBundle)`
- `applyLiveState(state)` → 把 4 个字段装回 Dungeon/Actor
- 测试:setup hero(带物品 + quickslot 占位)+ gold=100 → capture → 模拟 loadGame 覆盖(`Dungeon.hero=new Hero(); Dungeon.gold=0; Dungeon.quickslot=new QuickSlot(); Actor.resetNextID()`)→ applyLiveState → 断言 hero 回到原对象(含物品)、gold=100、quickslot 同一引用、nextID 回到 live 值

## 主线注入方案(定稿)

- `RegularLevel.createMobs()` 末尾 +1 行:`LuaLevelService.injectLevelNpcs(this);`(`// FORK(modding-M4d)` 注释)
- `injectLevelNpcs(Level)`:`if (!DeviceCompat.isDebug()) return;` → 委托 `spawnForDepth(level, Dungeon.depth)`
- `spawnForDepth(Level, int depth)`(package-visible,测试直接调,绕过 isDebug):
  - MVP:`depth == 1` → spawn `town_portal` NPC;其他 depth 不 spawn
  - `LuaNpc npc = LuaNpcRegistry.create("town_portal"); if (npc == null) return;`
  - **pos(codex round-1 should-fix #4)**:`findSpawnPosNearEntrance(level)` 扫 `entrance()` 周围(NEIGHBOURS9 外扩到半径 2),要求 `passable[pos] && !solid[pos] && level.findMob(pos)==null && pos != entrance()`;**找不到就 log + skip spawn**(绝不 fallback 到 entrance 本身 —— 避免与 hero 入口占位重叠)
  - `npc.pos = pos; level.mobs.add(npc);`

## NPC + Lua 桥方案(定稿)

- `town_portal.lua`(`register_npc{ id="town_portal", name="城镇传送", sprite="imp", onInteract=... }`):onInteract 调 `RPD.enterTown("test_safezone")`
- `town_return.lua`(`register_npc{ id="town_return", name="返回主线", sprite="imp", onInteract=... }`):onInteract 调 `RPD.leaveTown()`
- `RpdApi` 加两个函数:
  - `enterTown(levelId)`(OneArgFunction)→ `Game.runOnRenderThread(() -> LuaLevelService.enterLevel(id))`
  - `leaveTown()`(OneArgFunction,忽略 arg)→ `Game.runOnRenderThread(() -> LuaLevelService.leaveLevel())`
  - 均 render-thread 切换(onInteract 在 actor 线程触发)
- `test_safezone.json` mobs 加 `{"type":"lua_npc:town_return","pos":<entrance 17 附近 passable>}` —— pos=18(entrance 右邻 floor)

## Steps

### 1. 调研(worker 先做,产出笔记)

- 读 `RegularLevel.createMobs:220` 完整实现 + `Level.mobs.add` 时机(createMobs 在 GameScene.create 之前/之后?)
- 读 m4a `LuaLevelService.enterLevel/leaveLevel/inDataLevel` + `Dungeon.saveAll` isEphemeral 守卫
- 读 `InterlevelScene.Mode.CONTINUE` → `Dungeon.loadGame` 恢复链路(确认哪些状态被恢复)
- 读 m4a `Level.isEphemeral` + `Dungeon.saveAll/fail` 守卫(M4a 加的)
- 读 m4c `LuaShopNpc.attemptBuy`(看买的物品如何进 hero.belongings + Dungeon.gold 扣减)
- **产出**:leaveLevel 状态保留方案(见 R4)+ inject spawn 时机方案

### 2. leaveLevel 状态保留(R4 关键,先做)

**问题**:leaveLevel 走 CONTINUE 全量 loadGame,丢 SafeZone 内变化。

**方案选项(worker 调研后定)**:
- **选项 A(推荐)**:leaveLevel 不走 CONTINUE,改为"主动保存当前 hero 状态(含 SafeZone 变化)→ 切主线 level"。具体:
  1. enterLevel 时,记录 `prevDepth/prevBranch`(主线坐标)+ saveAll(存主线状态,已有)
  2. SafeZone 内 hero 变化(物品/金币)在内存
  3. leaveLevel:把 hero 当前内存状态(belongings + gold + hp 等)持久化(强制 saveAll 绕过 isEphemeral,或单独存 hero),然后切回主线 level(从存档 loadMain level + 应用最新 hero 状态)
- 选项 B:SafeZone 不 isEphemeral(让 saveAll 正常存 SafeZone),leaveLevel 用 depth 切回主线。但这会污染 depth(R5 风险,m4a 已避免)
- 选项 C:Custom transition 机制(不依赖 InterlevelScene.CONTINUE),手动切场景 + 保留 hero 内存状态

**建议 A**:hero 状态是关键(买的物品/金币),level 状态从存档恢复(主线 level 静态)。即"hero 内存 + level 存档"合并。需要绕过 isEphemeral 单独存 hero,或临时切非 ephemeral 再 saveAll。

worker 必须验证:m4c 商店买的物品 + 扣的金币,leaveLevel 后在主线 hero 身上保留(测试覆盖)。

### 3. 主线注入(RegularLevel.createMobs hook)

- `RegularLevel.createMobs:220` 末尾 +1 行:`LuaLevelService.injectLevelNpcs(this);`(fork 注释)
- `LuaLevelService.injectLevelNpcs(level)`:
  - `if (!DeviceCompat.isDebug()) return;`(M5 前不破坏 release)
  - 按 `Dungeon.depth` 查配置(MVP:depth==1 时 spawn 传送 NPC)
  - spawn:`LuaNpc`/`LuaShopNpc` 实例 + `npc.pos = entrance 附近 passable 格` + `level.mobs.add(npc)`(参考 Level.java:435)
- 配置(MVP 硬编码):depth=1 → town_portal NPC。配置驱动(JSON)留 M5

### 4. 传送 NPC + 回程 NPC

- `town_portal.lua`(m4b register_npc 范式):id="town_portal" / spriteKey / onInteract(heroId) → `LuaLevelService.enterLevel("test_safezone")`(Lua 调 Java,RpdApi 加 `enterTown(heroId)` 或 NPC interact 直接调)
- `town_return.lua`:id="town_return" / onInteract(heroId) → `LuaLevelService.leaveLevel()`
- `test_safezone.json`:mobs 加 `{"type":"lua_npc:town_return","pos":<entrance 附近>}`

### 5. 测试

- `LuaLevelInjectTest`:
  - injectLevelNpcs(isDebug=false → 不 spawn;isDebug=true + depth=1 → spawn 传送 NPC;depth≠1 → 不 spawn)
  - **状态保留**:模拟 enterLevel → hero 在 SafeZone 买物品 / 扣金币 → leaveLevel → 主线 hero 仍有该物品 + 金币已扣(R4 验收核心)
- 回归:174 既有测试不破

### 6. codex 评审 + 回归验证

- 原版一周目不受影响(C3,isDebug 守卫)
- debug 模式:主线 depth=1 有传送 NPC → 进 SafeZone → 买东西 → 回程 NPC → 主线 hero 带物品
- release 模式:无传送 NPC,原版体验不变

## 实施顺序(M4d 定稿)

1. **NPC Lua + Registry**:`town_portal.lua`、`town_return.lua`(scripts/npcs/,自动被 LuaEngine 扫描)
2. **RpdApi**:`enterTown` + `leaveTown`(runOnRenderThread 委托 LuaLevelService)
3. **LuaLevelService**:
   - 重写 `leaveLevel`(Option C 同步切 + hero/gold/quickslot/nextID 引用保留;死亡分支保留 CONTINUE)
   - 加 `LiveHeroState(hero, gold, quickslot, nextIdBundle)` + `captureLiveState`/`applyLiveState`(可测缝隙)
   - 加 `injectLevelNpcs(Level)`(isDebug 守卫)+ `spawnForDepth(Level, int)`(routing)
   - 加 `findSpawnPosNearEntrance(Level)`(passable + 非占用 + 排除 entrance;找不到 skip 不 fallback)
4. **上游 hook**:`RegularLevel.createMobs()` 末尾 +1 行 `LuaLevelService.injectLevelNpcs(this);`(`// FORK(modding-M4d)` 注释)—— 唯一上游改动
5. **test_safezone.json**:mobs 加 town_return NPC(pos=18)
6. **测试** `LuaLevelInjectTest`:
   - `spawnForDepth_depth1_withRegisteredNpc_spawnsOne`(注册 town_portal → depth=1 → 1 LuaNpc in mobs,pos 在 entrance 邻接 passable 且非 entrance)
   - `spawnForDepth_depth2_spawnsNothing`
   - `spawnForDepth_depth1_emptyRegistry_spawnsNothingGracefully`
   - `findSpawnPos_excludesEntranceAndOccupied`(构造 entrance 全被占用的场景 → 返回 -1 → spawn skip,不 fallback 到 entrance)
   - `injectLevelNpcs_respectsIsDebugGuard`(Game.version 切 INDEV/RELEASE,验证 guard)
   - `injectLevelNpcs_skipsDataDrivenLevel`(SafeZone 不应被 inject —— DataDrivenLevel 自带 createMobs 不调 injectLevelNpcs,此测试断言 injectLevelNpcs 对 DataDrivenLevel 也安全 no-op)
   - `liveHeroState_captureAndApply_preservesHeroGoldQuickslotNextId`(R4 核心:setup hero A + gold=100 + quickslot QA + nextID=N → capture → 模拟 loadGame 覆盖(hero=new, gold=0, quickslot=new, nextID=1)→ applyLiveState → 断言 Dungeon.hero==A、Dungeon.gold==100、Dungeon.quickslot==QA、Actor.nextID 恢复到 N)
   - `leaveLevel_deadHeroRoutesToContinue`(reflection 设置 hero.HP=0 → leaveLevel 走 CONTINUE 分支;由 Game.switchScene 不可 headless 验证,断言在 captureLiveState 不被调用或用 seam mock)
7. **回归**:`./gradlew :core:test` 全过(原 174+ 测试不破)
8. **codex 评审**(`codex exec --sandbox read-only` 非 CAO 管道,绕过 v0.142.0 失效 bug)+ desktop debug 验证

## Acceptance

- ✅ debug 模式主线 depth=1 有传送 Lua NPC,interact 进 SafeZone
- ✅ SafeZone 有回程 NPC,interact 回主线
- ✅ **SafeZone 内买的物品/扣的金币带回主线 hero**(R4 状态保留,核心验收)
- ✅ release 模式无传送 NPC(DeviceCompat.isDebug 守卫),原版一周目不受影响(C3)
- ✅ 离开 SafeZone 回主线,无 depth/Rankings/GamesInProgress 污染(沿用 m4a 守卫)
- ✅ ≥1 单元测试:inject(isDebug/depth 路由)+ 状态保留(买物品 round-trip)
- ✅ 上游改动最小(RegularLevel.java +1 hook;若 leaveLevel 重写需要,Dungeon/InterlevelScene 最小 hook)
- ✅ codex_reviewer APPROVED

## 风险 + 注意

- **R1: spawn 时机**。createMobs 在 level.build 阶段(GameScene.create 之前)。`mobs.add` 直接加到 level.mobs,GameScene 后续激活。需确认 NPC 在 GameScene 起来后正确显示 + interact。参考 m4a DataDrivenLevel.createMobs(mobs.add 直接)。
- **R2: NPC pos**。传送 NPC 放 entrance 附近(玩家必经)。pos = entrance 相邻 passable 格。回程 NPC 放 SafeZone entrance 附近。
- **R3: isDebug 守卫**。`DeviceCompat.isDebug()` release=false。测试环境(debug)能 spawn。但 CI/headless 测试需 mock 或绕过(测试直接调 injectLevelNpcs 不走 isDebug 守卫的入口)。
- **R4: leaveLevel 状态保留(最关键)**。m4a CONTINUE 全量恢复丢 SafeZone 变化。必须重写 leaveLevel 保留 hero 内存状态。**worker 第一步调研 + 设计**,这是 M4d 核心。若发现要大改 InterlevelScene/C4 风险高,[BLOCKED] 上报拆分(状态保留单独 feature)。
- **R5: enterLevel/leaveLevel 闭环**。enterLevel saveAll 主线 → SafeZone;leaveLevel 保留 hero → 主线。depth/branch 不污染(m4a isEphemeral 已守 saveAll/fail,leaveLevel 不走 depth++)。
- **R6: C4 上游 hook**。RegularLevel +1 hook 必须 fork 注释。若 leaveLevel 重写需要动 Dungeon/InterlevelScene,最小化 + 标注。
- **R7: C5 proguard**。无新反射入口(沿用 m4a-c keep)。

## 参考

- SPD `RegularLevel.java:220`(createMobs 注入点)
- SPD `Level.java:435`(`mobs.add`)+ `Level.java:749`(`spawnMob`)
- m4a `LuaLevelService.java:79/115/71`(enterLevel/leaveLevel/inDataLevel)+ `ac5fa76f6`(isEphemeral + saveAll/fail 守卫)
- m4a `Level.isEphemeral` + `Dungeon.saveAll` 守卫(M4a 加的)
- m4b `LuaNpc.java`(传送/回程 NPC 基类)+ `LuaNpcRegistry`
- m4c `LuaShopNpc.attemptBuy`(买的物品进 hero.belongings + Dungeon.gold 扣减,R4 验证用)
- `InterlevelScene.Mode.CONTINUE` → `Dungeon.loadGame`(恢复链路)
- `DeviceCompat.isDebug()`(isDebug 守卫,沿用 LuaDebugService)
- modding 范式 + 约束 C1-C5 + CLAUDE.md
