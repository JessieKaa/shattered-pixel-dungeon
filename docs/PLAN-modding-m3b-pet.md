# PLAN — modding-m3b-pet

> 里程碑:**M3b 宠物系统(M3 第二弹)**
> 路线图:`docs/MODDING-ROADMAP.md` §4 M3 | 前置:M0+M1+M2-Item+M3a 已合 master
> M3 决策:D1 消耗性 spell / D2 复用天赋 / D3 做宠物 / D4 保留硬编码

---

## Goal

`LuaAlly extends DirectableAlly`(friendly mob)+ `RPD.spawnAlly(allyId, pos)` 注入 + Java 命令面板(`followHero`/`defendPos`/`targetChar`/`expel`)+ Lua `onCommand` 回调。让 Lua 能定义可召唤、可指挥、能战斗的宠物。

## Context

- **M3a 已交付**:`LuaMob extends Mob`(敌对)+ `LuaMobRegistry` + `RPD.spawnMob`(`GameScene.add` 注入,不动 `Level.createMob`)+ AI 回调(`act`/`attackProc`/`die`,super-then-Lua)。**M3b 把这套范式映射到 friendly mob**。
- **M3b 调研关键发现**(worker 必读):
  1. **`DirectableAlly extends NPC`**(`actors/mobs/npcs/DirectableAlly.java`):`alignment=ALLY` + `intelligentAlly=true`;核心方法 `followHero()`/`defendPos(int)`/`targetChar(Char)`/`directTocell(int)`;命令靠实例字段 `defendingPos`+`movingToDefendPos` 驱动,覆盖 `Wandering`/`Hunting` 状态;持久化存 `defend_pos`/`moving_to_defend`
  2. **`DriedRose.GhostHero extends DirectableAlly`**(SPD 唯一持久友方)+ **`PowerOfMany.LightAlly extends DirectableAlly`**——都是 M3b LuaAlly 的范本
  3. **`MirrorImage`/`PrismaticImage` extends NPC**(非 Directable,临时召唤,HP=1)——M3b 不参考(它们不可指挥)
  4. **`intelligentAlly`**(`Mob.java:273`):让友方 mob 在英雄无敌人时也主动游荡/参战;被 `PowerOfMany` 识别为"可赋能盟友"
  5. **跨层**:SPD 友方不自动跟随下楼(`Level.MOBS` bundle 按层存,GhostHero 靠 `rose.ghost` + UI 每层重召唤)。**M3b 简化为同层常驻**,跨层自动跟随留后续
- **范围决策(基于调研,已定)**:
  - **背包不做**(SPD 物品体系 Bundle+Belongings 18 槽,照搬 Remixed `PetInventoryManager` 工作量大且偏离主线)→ 留 M3c+
  - **跨层不做**(同层常驻;进下一层消失或走 DriedRose 式重召唤,跨层自动跟随留后续)
  - M3b 聚焦:**召唤 + 指挥 + 战斗** + Lua `onCommand` 回调

## 关键决策(worker 实施前确认;有疑问 `[BLOCKED]`)

### D1 LuaAlly 基类(extends DirectableAlly)

`LuaAlly extends DirectableAlly`(不是 NPC/Mob)——白拿 follow/defend/attack 状态机 + `intelligentAlly` + 命令字段持久化。默认 `alignment=ALLY`(DirectableAlly 已设)。从 Lua table 读 `hp`/`ht`/`attack`/`defense`/`name`/`sprite`(复用现有 mob sprite 白名单,M3a 已有 7 项)+ 回调字段。

### D2 命令系统(Java 面板 + Lua onCommand 回调)

- **Java 命令面板**(主):`RPD` 暴露 `commandAlly(allyId, cmd, targetId)` 等窄函数,内部调 `ally.followHero()`/`defendPos(pos)`/`targetChar(enemy)`/`expel()`(DirectableAlly 现成 API)
- **Lua `onCommand(selfId, cmd, targetId)` 回调**(辅):Java 命令执行后回调 Lua,让 Lua 做反馈/特效(如 GLog、particle)。Lua **不接管调度**(与 M3a `act` super-then-Lua 范式一致)
- **expel**:`RPD.expelAlly(allyId)` → `ally.destroy()`/`ally.die(null)`(参考 DirectableAlly/GhostHero 释放路径,worker 核对)

### D3 spawnAlly 不动上游(沿用 M3a 范式)

`RPD.spawnAlly(allyId, pos)` → `LuaAllyRegistry.create(allyId)` → `ally.pos = pos` → `GameScene.add(ally)`(与 `RPD.spawnMob` 同注册点)。**不触及 `Level.createMob`/`MobSpawner`**(C3/C4)。

### D4 持久化(沿用 LuaMob + DirectableAlly)

`LuaAlly` 固定 Bundle className `LuaAlly` + `lua_ally_id` 字段。`storeInBundle`/`restoreFromBundle`:`super`(DirectableAlly 存 `defend_pos`/`moving_to_defend`)+ `lua_ally_id`。restore 时 `LuaAllyRegistry.getTable(id)` re-hydrate。**HP/HT 在 ctor 设**(M3a codex must-fix:hydrate 不覆盖存档 HP/HT)。

### D5 AI 回调(沿用 M3a)

`LuaAlly` override `act()`/`attackProc`/`defenseProc`/`die`:super-then-Lua(复用 `LuaItemCallbacks`)。`act()` 短路语义同 M3a(Lua 返回 true 接管,但 Java 兜底 `spend(TICK)`)。

## Files

`✚` 新增 / `✎` 修改(均在 `core/.../modding/`,C2)

- `✚ core/.../modding/LuaAlly.java` — `extends DirectableAlly`,hydrate 数值 + 回调字段,override `act/attackProc/defenseProc/die`(super-then-Lua),持久化 `lua_ally_id`(D4),HP/HT 在 ctor 设
- `✚ core/.../modding/LuaAllyRegistry.java` — `Map<String, LuaTable>` + register/getTable/create/ids(1:1 类比 `LuaMobRegistry`)
- `✎ core/.../modding/LuaEngine.java` — `register_ally(table)` global(校验 `id`/`name`/`hp`/`ht`/`attack`/`defense` 必填)+ `scripts/allies/*.lua` 枚举(沿用 M3a 的 loadScriptsFrom 共用)
- `✎ core/.../modding/RpdApi.java` — 加 `spawnAlly(allyId, pos)`(`GameScene.add`,不动 Level)+ 命令函数 `commandAlly(allyId, cmd, targetId)`(分发 follow/defend/attack)+ `expelAlly(allyId)`
- `✚ core/src/main/assets/scripts/allies/test_ally.lua` — 测试宠物:hp/ht/attack + `onCommand(selfId, cmd, targetId)` 回调(GLog 反馈)+ `attackProc` 回调
- `✚ core/src/test/java/.../modding/LuaAllyTest.java` — 注册 + spawn + 命令 + onCommand 回调 + 持久化(headless,GameScene 路径降级为 Registry/hydrate/回调调度测试)
- `✎ android/proguard-rules.pro` — keep `LuaAlly`(若验证 release;M3a 已有 `modding.**` 规则可能已覆盖)

## Steps

1. **写 LuaAllyRegistry** — 1:1 类比 LuaMobRegistry。单测 register + create 返回 LuaAlly。
2. **写 LuaAlly(extends DirectableAlly)** — 无参构造(Bundle)+ hydrate(数值 + 回调字段缓存)+ ctor 设 HP/HT(M3a must-fix)+ storeInBundle/restoreFromBundle(`lua_ally_id` re-hydrate,super 存 defend_pos/moving_to_defend)。单测:hydrate + Bundle round-trip(非满血 HP 保持,M3a 教训)。
3. **AI 回调 override(D5)** — `act`(super + callOptInt,selfId;true 短路 + spend 兜底)/`attackProc`/`defenseProc`/`die`(super + callOpt,selfId+enemyId)。复用 `LuaItemCallbacks`。单测:有/无回调字段行为正确。
4. **register_ally global** — `LuaEngine` 加 `globals.set("register_ally", ...)`,校验必填字段后 `LuaAllyRegistry.register`。`scripts/allies/*.lua` 枚举(沿用 M3a 共用逻辑)。
5. **RPD.spawnAlly + 命令(D2/D3)** — `RpdApi` 加 `spawnAlly(allyId, pos)`(`LuaAllyRegistry.create` + `GameScene.add`,不动 Level)+ `commandAlly(allyId, cmd, targetId)`(分发 `followHero`/`defendPos`/`targetChar`,回调 Lua `onCommand`)+ `expelAlly(allyId)`。单测:spawnAlly 不触发 Level.createMob;commandAlly 分发正确(用 spy/mock 或检查 ally 状态字段)。
6. **测试 Lua ally** — `test_ally.lua`(friendly,hp/ht/attack + onCommand + attackProc 回调)+ LuaAllyTest 全链路。
7. **沙箱回归** — Lua 不能 luajava.bindClass(M1 沙箱);charId 范式(Lua 不碰 Char 对象)。
8. **回归(C3)** — M0/M1/M2/M3a 测试全过(LuaEngineTest/LuaSandboxTest/GeneratorLuaItemTest/LuaItemCallbackTest/LuaMobTest);原版 spawn 不变。
9. **(加分)release proguard**(C5)。

## Acceptance

- [ ] LuaAllyRegistry 工作
- [ ] LuaAlly hydrate + 持久化(`lua_ally_id` re-hydrate,**HP/HT 不被 hydrate 覆盖**——M3a must-fix 教训)
- [ ] AI 回调:`act`(true 短路 + spend 兜底)/`attackProc`/`die` 工作
- [ ] `register_ally` global 工作
- [ ] `RPD.spawnAlly` 注入(`GameScene.add` 级),不动 `Level.createMob`/`MobSpawner`(测试 + Grep)
- [ ] **命令系统**:`RPD.commandAlly(allyId, cmd, targetId)` 分发 `followHero`/`defendPos`/`targetChar`;`expelAlly` 释放;Lua `onCommand` 回调触发
- [ ] 沙箱不破(luajava.bindClass 回归测试)
- [ ] M0/M1/M2/M3a 测试无回归
- [ ] 原版 spawn/平衡不变(C3)
- [ ] fork 代码在 `modding/` 子包(C2);**上游零改动**(C4:不碰 Level/Mob/DirectableAlly/MobSpawner,只新增 modding/ 文件)
- [ ] `:core:compileJava` / `:desktop:debug` / `:android:assembleDebug` 通过
- [ ] codex 评审通过

## 风险

- **`act()` 短路 + spend**(M3a 已踩过):Lua 接管必须 `spend(TICK)` 推进时间,否则 ally 卡死 Actor 队列。worker 必做(M3a LuaMob 已有兜底范式,复用)。
- **HP/HT hydrate 覆盖**(M3a codex must-fix):hydrate 只设定义字段(name/attack/defense/sprite/lua_ally_id),HP/HT 在 ctor 设,restoreFromBundle 不碰 HP/HT。**Bundle round-trip 测试必做**(非满血 HP 保持)。
- **GameScene.add 在 headless 不可用**:LuaAllyTest 降级测 Registry/hydrate/回调/命令分发(不调 GameScene.add),spawnAlly 的 GameScene 路径靠 desktop 实测或代码审查。
- **expel 路径**:worker 核对 DirectableAlly/GhostHero 释放(`destroy`/`die`/`remove`),别泄漏 Actor。
- **命令面板 UI**:M3b 只做 RPD 命令函数(供 Lua/调试调用),**不做 UI 命令面板**(留后续)。worker 别扩大范围。
- **proguard**(C5):release keep LuaAlly。

## 参考

- `docs/MODDING-ROADMAP.md` §4 M3
- `docs/PLAN-modding-m3a-mob-spawn.md`(M3a 上下文,范式 1:1 映射)
- `core/.../actors/mobs/npcs/DirectableAlly.java`(继承基类 + 命令 API)
- `core/.../items/artifacts/DriedRose.java`(GhostHero 持久友方范本)
- `core/.../actors/buffs/PowerOfMany.java`(LightAlly 命令面板范本)
- M3a 已有:`core/.../modding/{LuaMob,LuaMobRegistry,LuaItemCallbacks,RpdApi,LuaEngine}.java`

## 范围决策记录

- **背包不做**(留 M3c+,SPD 物品体系适配成本高)。
- **跨层自动跟随不做**(同层常驻;跨层留后续,可能走 DriedRose 式重召唤)。
- **命令面板 UI 不做**(只做 RPD 命令函数供 Lua/调试调用,UI 留后续)。
- LuaAlly extends DirectableAlly(白拿 follow/defend/attack + intelligentAlly + 持久化)。
