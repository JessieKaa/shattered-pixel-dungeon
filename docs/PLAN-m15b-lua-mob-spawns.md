# PLAN: M15b — Lua mob 进主游戏刷怪池(让 remixed mob 在 dungeon 刷新)

## Goal
让 `MobSpawner.getMobRotation(depth)` 返回的 rotation 包含 Lua mob,使得玩家在正常地下城流程中遇到 remixed 怪物。当前 `MobSpawner` 只返回 vanilla `Class<? extends Mob>` 列表,且 `Level.createMob` 用 `Reflection.newInstance(cl)` 实例化;LuaMob 是单一 concrete class,不同 id 需要 hydrate。本 feature 解决这个结构 mismatch。**与 M15a/c/d/e 零文件冲突**(MobSpawner/Level/LuaMob 子系统 vs 其他)。

## Context(Explore 2026-07-09 核实)
- `MobSpawner.getMobRotation(int depth)`(`core/.../actors/mobs/MobSpawner.java:62-68`) → `standardMobRotation(depth)`(`:71-212`) 是 hardcoded switch + `Class` 列表。
- `Level.createMob()`(`core/.../levels/Level.java:515-523`) 调用 `Reflection.newInstance(mobsToSpawn.remove(0))` 然后 `ChampionEnemy.rollForChampion(m)`。
- `LuaMob`(`core/.../modding/LuaMob.java`) 是 `Mob` 子类;`LuaMobRegistry.create(id)` 创建实例并 `hydrate(id)`。当前 `RpdApi.spawnMob(id, pos)` 直接调 registry。
- `LuaMobRegistry`(`core/.../modding/LuaMobRegistry.java`) 只有 `create/get/ids/contains`。
- `ChampionEnemy.rollForChampion` 对任意 `Mob` 有效,包括 LuaMob。

**设计决策**:
- **结构 mismatch 的解决**:引入一个 **factory class `LuaMobPlaceholder extends Mob`**(或用现有 `LuaMob` 加静态字段)。把它放进 `MobSpawner` 的 rotation;当 `Level.createMob` 实例化它时,**从 LuaMobRegistry 随机选一个 id,创建真正的 LuaMob 实例,把占位 mob 替换掉**。
- 具体实现方案二选一(由 worker 读代码后定):
  - **方案 A:占位替换**:rotation 放 `LuaMobPlaceholder.class`。`Level.createMob` 检测到 `LuaMobPlaceholder.class` 时(或在 `LuaMobPlaceholder` 的 `restoreFromBundle`/`onCreate` 不可能,因为 createMob 立刻返回),改为返回 `LuaMobRegistry.create(randomId())`.**但这要改 `Level.createMob` 的分支**。
  - **方案 B:LuaMob 作为工厂类**:让 `LuaMob` 本身的 no-arg constructor 随机选一个已注册 id 并 hydrate。**最简单**:改 `LuaMob` 无参构造(或添加一个 `static` 池)使得 `Reflection.newInstance(LuaMob.class)` 出来的就是有效的随机 LuaMob。但 LuaMob 当前构造后需要显式 `hydrate(id)`,因为 bundle 反序列化需要知道 id。
  - **方案 C(推荐)**:新增 `class LuaMobFactory extends Mob`,no-arg constructor 随机从 `LuaMobRegistry` 取 id,调 `LuaMobRegistry.create(id)`,并**把自身的属性/状态复制到新 LuaMob?** 不,直接让 factory 不可存活,`Level.createMob` 里 if `cl == LuaMobFactory.class` → return `LuaMobRegistry.create(randomId())`.这是最小改 Level 的分支。
- 本 feature 采用 **方案 C 变体**:新增 `LuaMobFactory extends Mob`,把它塞进 rotation;`Level.createMob` 加一行:
  ```java
  if (cl == LuaMobFactory.class) {
      return LuaMobRegistry.create(randomEnabledMobId());
  }
  ```
  之前是 `Mob m = Reflection.newInstance(cl); ChampionEnemy.rollForChampion(m); return m;`。工厂 mob 本身不会被玩家看到(因为立刻替换成 LuaMob)。
- **概率来源**:同样用 `ModManifest.balance` key `lua_mob_spawn_prob`(单值,0=不刷)。ModRegistry 启用含该值的 mod 时,调用 `MobSpawner.setLuaMobSpawnProbability(depthRange, prob)` 或更简单地:在 `MobSpawner.getMobRotation` 末尾,如果 prob > 0,以概率 prob 把 rotation 中某个 vanilla 槽位替换为 `LuaMobFactory.class`(保持 rotation 总长度不变,不额外增加 mob 密度)。
- **深度适配**:Lua mob 没有 vanilla depth 分组。简单策略:所有 depth 共用同一 pool(随机一个 enabled Lua mob id),后续 milestone 再做 depth-tier 映射。或者让 `MobSpawner` 按 LuaMobRegistry ids() 均匀选。

## Files (worker-verified)
- **`core/.../modding/LuaMobFactory.java`**(新):`extends Mob`,空 no-arg constructor(或 throw if called outside createMob)。只是 rotation 占位,不实例化为真实 mob。
- **`core/.../actors/mobs/MobSpawner.java`**:
  - 新增 `public static void setLuaMobSpawnProbability(float prob)`(runtime setter,ModRegistry 调用)。
  - `getMobRotation` 末尾:若 prob > 0,以 prob 概率把 rotation 中 Random 一个位置替换为 `LuaMobFactory.class`。
  - 或更细:per-depth 加权,但 MVP 用 uniform。
- **`core/.../levels/Level.java`**:在 `createMob()` 加分支:若弹出的 class 是 `LuaMobFactory.class`,返回 `LuaMobRegistry.create(randomLuaMobId())` 并应用 champion。
- **`core/.../modding/LuaMobRegistry.java`**:新增 `static String randomId()`(uniform from ids()),空时返回 null。
- **`core/.../modding/ModManifest.java`/`ModRegistry.java`**:接受 `balance.lua_mob_spawn_prob`,ModRegistry 调 `MobSpawner.setLuaMobSpawnProbability(max)`。
- **测试**:`MobSpawnerLuaMobTest` — 设 prob=1.0,depth 1..26 调 `getMobRotation`,断言每个 rotation 至少含一个 LuaMobFactory(或调 createMob 若干次出 LuaMob)。C3:prob=0 时 rotation 不含 factory。

### 显式延后
- **depth-tiered Lua mob 池**:MVP 全 depth 均匀;后续按 mod.json 声明 depthRange。
- **Lua mob rare alts**:像 `RARE_ALTS` 那样替换;后续。
- **LuaMobFactory 的 bundle 安全**:factory 永不进 bundle(实例化即替换),无需序列化。

## Steps
1. 读 `Level.createMob` + `MobSpawner.getMobRotation` + `LuaMob` 构造/hydrate。
2. 新增 `LuaMobFactory` + `LuaMobRegistry.randomId()`。
3. 改 `MobSpawner.getMobRotation`:prob 驱动替换。
4. 改 `Level.createMob`:factory 分支替换为真实 LuaMob。
5. `ModManifest`/`ModRegistry` 接 balance key。
6. 写测试。
7. `:core:test` 全绿。
8. codex 评审(assign codex_reviewer,失败上报 dispatcher)。

## Acceptance
- [ ] `MobSpawner.getMobRotation` 在 `lua_mob_spawn_prob > 0` 时可能含 `LuaMobFactory`
- [ ] `Level.createMob` 遇到 factory 返回真实 LuaMob(随机 id)
- [ ] prob=0 时 rotation 与 vanilla 完全一致(C3)
- [ ] `ModManifest.balance` 接受 `lua_mob_spawn_prob`
- [ ] `:core:test` 全绿
- [ ] C3 不破:vanilla rotation 结构不被破坏(只替换,不加长)
- [ ] 与 M15a/c/d/e 零文件冲突

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- **新 codex 政策**:worker 用 `assign("codex_reviewer",...)`;失败 send_message 报 dispatcher 裁决,不自行 codex exec
- factory mob 只是占位,**不应被玩家看见或进 bundle**
- 替换逻辑在 `Level.createMob`,这是唯一合适入口(Reflection.newInstance 之后立刻替换)
- 与 M15a/c/d/e 零重叠
