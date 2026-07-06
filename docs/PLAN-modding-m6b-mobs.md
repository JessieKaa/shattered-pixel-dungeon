# PLAN: M6b — Remished mob 改写 PoC(B 全量第一步)

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M6
> 前置:M6a 桥子集 + M6-fast 已合 master(`fb50ddc31`)
> **D5 = (a) B 全量 + D5'-(a) 禁 luajava**(用户 2026-07-06 定)
> 本 feature 是 B 全量第一步:扩桥 + 改写 5-6 mob,验证全量可行性,产出 M6c/d 成本数据

## Goal

扩 `RpdApi` 桥(M6a 预测的 3-5 个 id primitive + 扩 `BuffWhitelist`)+ 改写 5-6 个 Remished mob,验证 B 全量在 luajava 禁下可行。产出 M6c(buff)/M6d(item+spell)全量成本数据,指导后续并行。

## Context

### D5 决策(已定,2026-07-06)

- **D5 = (a) B 全量**:mob + buff + item + spell 全移植(M6b/c/d/e)
- **D5' = (a) 禁 luajava**:守 M1 沙箱,用 id-based API 替代 `luajava.bindClass`

### M6a 已交付(master `fb50ddc31`)

5 个 Lua surface:`RPD.placeBlob` / `RPD.addImmunity` / `RPD.Blobs`(14 项)/ `RPD.Buffs`(9 项)/ LuaMob `spawn` 回调。守 luajava 禁。

### M6a 的 M6b 预测(详见 `docs/PLAN-modding-m6a-bridge-subset.md` 末尾,worker 回填)

- 单 mob 倒逼 4/5 surface
- **5-6 mob 还需补 3-5 个新 id primitive**:`nearestCell` / `pathDistance` / `setMobAi` / `blink` 之类
- 持续扩 `BuffWhitelist`(FetidRat 未覆盖的 Ooze buff 等)
- **luajava 禁下唯一摩擦点 = 自定义 wander AI**(Remished 用 `MobAi:getStateByTag` 直调 Java;我们用新 id API `setMobAi(mobId, aiTag)` 解决)
- worker 建议:逐 mob 手建可担(id-based 翻译"预定义效果"类行为良好)

## Files

> Phase 1 核对(worker):`BuffWhitelist` 实为 `RpdApi` 的**内部类**(`RpdApi.BuffWhitelist`),非独立文件;`BlobRegistry` 同理。扩 buff 在 `RpdApi.java` 内完成,不新建 `BuffWhitelist.java`。

- `core/src/main/java/.../modding/RpdApi.java`(扩 5 个 primitive:`setMobAi`/`enemyOf`/`cellDistance`/`emptyCellNextTo`/`blink`;`BuffWhitelist` 加 `Ooze` 1 项)
- `core/src/main/java/.../modding/LuaMob.java`(加 `setAiTag(String)`/`chooseAndRememberEnemy()`/`findEmptyNextTo(int)` 三个 public 包装,供 RpdApi 经继承访问 `state`/`chooseEnemy` 等 protected 成员)
- `core/src/main/assets/mods/test_mod/scripts/mobs/`(6 个 mob:`shaman_elder`/`spider_elite`/`deep_snail`/`hydra`/`maze_shadow`/`buffer`,snake_case id)
- `core/src/test/java/.../modding/RpdApiBlobTest.java`(扩 5 个 primitive 的单测;既有用例不动)
- `docs/PLAN-modding-m6a-bridge-subset.md`(参考其「M6b 预测」段,已完成)
- `core/src/main/assets/mods/test_mod/mod.json`(`default_enabled=false` 既有,不动 — C3)

## Steps

> Phase 1 细化:挑选覆盖 5 类行为的 6 个 mob + 倒逼的 5 个 primitive。**放毒气**已由既有 `test_blob_rat`(M6a)覆盖,本批 6 个新 mob 覆盖其余行为(加 buff / 特殊 AI / 召唤 / 瞬移)并扩 buff 广度。

### Mob 选择(6 个,行为覆盖矩阵)

| mob id | 源(Remished) | 行为类别 | 倒逼的 primitive / 白名单 |
|---|---|---|---|
| `shaman_elder` | ShamanElder.lua | 特殊 AI(距离驱动 Fleeing/Hunting 切换) | **`setMobAi`** + **`enemyOf`** + **`cellDistance`** |
| `spider_elite` | SpiderElite.lua | 加 buff(attackProc 上 Vertigo) | 既有 `affectBuff`(baseline,验证 Vertigo 链路) |
| `deep_snail` | DeepSnail.lua | 加 buff(defenseProc 上 Ooze,相邻时) | **`BuffWhitelist.Ooze`** + **`cellDistance`** |
| `hydra` | Hydra.lua | 召唤(die 时分裂 2 个自身副本) | **`emptyCellNextTo`** + 既有 `spawnMob` |
| `maze_shadow` | MazeShadow.lua | 瞬移(act 时概率 blink 到目标邻格) | **`blink`** + 复用 `enemyOf`/`emptyCellNextTo` |
| `buffer` | Buffer.lua | 加 buff(attackProc 上随机白名单 buff) | 既有 `affectBuff` + 扩 buff 广度(暴露白名单覆盖摩擦) |

> 说明:候选 `ScriptedThief`(遍历背包偷物品)是典型 `chr:method()` 深度实例直调,**故意不选** — M6a 预测已标记其为 D5' 升级触发,本 PoC 不引入背包 API,在「M6c/d 预测」里记录为已确认的 luajava 禁摩擦点。

### 实施步骤

1. **`RpdApi` 扩 5 个 primitive**(均静态包装,字符串 id/位置 int,不暴露 Java Class 句柄):
   - `setMobAi(mobId, aiTag)`(`SetMobAi extends TwoArgFunction`):resolveChar → 非 LuaMob → log+NIL;tag 小写映射 `sleeping/hunting/wandering/fleeing/passive` → `LuaMob` 的 `SLEEPING/HUNTING/WANDERING/FLEEING/PASSIVE` 公开 AiState 字段;未知 tag → log+NIL。调 `((LuaMob)target).setAiTag(tag)`(LuaMob 经继承访问 `this.state` 公开字段)
   - `enemyOf(mobId)`(`EnemyOf extends OneArgFunction`):resolveChar → 非 LuaMob → log+NIL;**不直接读缓存 `Mob.enemy`**(Lua `act` 在 `super.act()` 之前,缓存可能为空/上一轮);调用 `((LuaMob)target).chooseAndRememberEnemy()` 主动走 `chooseEnemy()` 并同步 `this.enemy`,返回 enemy id 或 NIL。`chooseAndRememberEnemy()` 先 guard `Dungeon.level != null`,再按 `Char.act()` 同款逻辑确保 `fieldOfView != null && fieldOfView.length == Dungeon.level.length()`(否则 new boolean array),再执行 `Dungeon.level.updateFieldOfView(this, fieldOfView)`,以确保 `chooseEnemy()` 使用最新视野且首次 act 前不 NPE
   - `cellDistance(posA, posB)`(`CellDistance extends TwoArgFunction`):两参必须 int;`Dungeon.level==null` / 任一 pos 越界 → NIL;返回 `Dungeon.level.distance(posA,posB)`(SPD Chebyshev 距离,斜向相邻=1)。用于 `shaman_elder` 距离阈值和 `deep_snail` 相邻判断,避免 Lua 端无法从线性 cell id 推 width/height
   - `emptyCellNextTo(pos)`(`EmptyCellNextTo extends OneArgFunction`):pos 非 int → NIL;`Dungeon.level==null` → NIL;遍历打乱后的 `PathFinder.NEIGHBOURS8`(不含中心),对每个 `c=pos+offset` **先** `insideMap(c)` 再访问数组,返回首个 `Level.passable[c] && Actor.findChar(c)==null` 的 cell,全占满 → NIL
   - `blink(mobId, pos)`(`Blink extends TwoArgFunction`):resolveChar → 非 LuaMob → log+NIL;pos 非 int / `Dungeon.level==null` / 越界 / `!passable[pos]` / `Actor.findChar(pos)!=null` → log+NIL(守 teleportToLocation 同款前置);否则 `ScrollOfTeleportation.appear((LuaMob)target, pos)`(canonical 瞬移 VFX + `ch.move` + occupyCell)
2. **`LuaMob` 加 3 个 public 包装**(供 RpdApi 经继承访问 protected 成员,C2 守 fork 边界):
   - `public void setAiTag(String tag)`:map→`this.state = this.<FIELD>`
   - `public Char chooseAndRememberEnemy()`:若 `Dungeon.level == null` 返回 null;若 `fieldOfView == null || fieldOfView.length != Dungeon.level.length()` 先 new boolean array;然后 `Dungeon.level.updateFieldOfView(this, fieldOfView)`,再 `this.enemy = chooseEnemy(); return this.enemy;`
   - `public static int findEmptyNextTo(int pos)`:`emptyCellNextTo` 用的查找逻辑(NEIGHBOURS8,insideMap 先于数组访问),无空格返回 -1
3. **`BuffWhitelist` 加 `Ooze`**:`level-based` 组(set(float)),`ENTRIES.put("Ooze", (t,amt)->Buff.affect(t,Ooze.class).set(amt)); BUFF_CLASSES.put("Ooze", Ooze.class);`(同 Bleeding/Poison 模式)
4. **6 个 mob 脚本**(纯 `register_mob` + `RPD.*`,不引入 `scripts/lib/`):
   - `shaman_elder.lua`:`act` 调 `enemyOf`+`cellDistance(charPos(self), charPos(enemy))` 算距离,<2→`setMobAi "fleeing"`,>4→`setMobAi "hunting"`,return false(让上游 AI 用新 state 跑)。源 `zapProc`/`zapMiss` 依赖 Remished `ManaShield`/`skillLevel`,**舍**(记摩擦)
   - `spider_elite.lua`:`attackProc` 20% `affectBuff(enemy, RPD.Buffs.Vertigo, <amt>)`,return baseDamage
   - `deep_snail.lua`:`defenseProc` 20% 且 `cellDistance(selfPos, enemyPos)==1` 时 `affectBuff(enemy, RPD.Buffs.Ooze, <amt>)`,return baseDamage
   - `hydra.lua`:`die` 里循环 2 次:`emptyCellNextTo(selfPos)` 拿空格 → `spawnMob("hydra", cell)`(自体分裂)。源用 `MobFactory:mobByName(self:getMobClassName())`,我们用固定 id `"hydra"`(递归分裂,等同原义)
   - `maze_shadow.lua`:`act` 30% 概率:`enemyOf` 拿目标 → `emptyCellNextTo(enemyPos)` → `blink(selfId, cell)`;return false
   - `buffer.lua`:`attackProc` 从白名单子集表 `{RPD.Buffs.Vertigo, .Roots, .Paralysis, .Cripple, .Slow, .Poison, .Ooze}` 随机 `affectBuff(enemy, pick, <amt>)`,return baseDamage。源含 `Invisibility/Levitation/Charm/Frost/Light` **未在白名单**(记 buff 覆盖摩擦 → 指导 M6c 扩白名单)
5. **单测**(扩 `RpdApiBlobTest`,既有用例不回归):
   - `setMobAi`:LuaMob 经 `setAiTag("fleeing")` 后 `mob.state == mob.FLEEING`;坏 tag 抛错吞 → 不改 state;非 LuaMob → NIL
   - `enemyOf`:在 hero/target 可见场景调用后主动 `chooseEnemy()` 并同步 `mob.enemy`,返回其 id;无 enemy → NIL(覆盖 Lua act 发生在 `super.act()` 前的时序问题)
   - `cellDistance`:headless/越界→NIL;合法返回 `Dungeon.level.distance`(斜向相邻=1)
   - `emptyCellNextTo`:headless(Dungeon.level=null)→NIL;有 level 时返回 `NEIGHBOURS8` 中 passable+无 char 的邻格;边缘 cell 不越界;全占满→NIL;不返回原 cell
   - `blink`:headless→NIL;越界/占位→NIL;合法时 `mob.pos==newPos`(经 `appear`)
   - `Ooze`:`affectBuff(id,"Ooze",5)` 后目标有 Ooze buff;`BuffWhitelist.lookupClass("Ooze")!=null`
   - 既有 229 tests 不回归
6. **C3 回归**:`test_mod default_enabled=false`(既有),6 个 mob 只在 test_mod 开启时由 LuaEngine 扫描加载;既有测试零影响
7. **设备验证**(可选):开 test_mod → `RPD.spawnMob("hydra", pos)` 杀之观察分裂;`shaman_elder` 观察远近切换 AI;`maze_shadow` 观察瞬移
8. **回填本 PLAN 末尾「M6c/d 预测」段**(单 mob 工时 + 总 primitive 数 + luajava 禁摩擦点 + M6c∥M6d 并行可行性)

## Phase 1 细化笔记(worker 补,不改 Goal/Context 方向)

实施前代码核对结论:

- `RpdApi.build()` 现有 19 个 Lua-facing surface(含 M6a 的 `Blobs`/`Buffs`/`placeBlob`/`addImmunity`),PLAN 计数正确 ✓
- `BuffWhitelist` / `BlobRegistry` 是 `RpdApi` 的**内部类**,非独立文件(`find` 0 命中)→ 扩 `Ooze` 直接在 `RpdApi.java` 内改,不新建文件
- `LuaMob`(`modding` 子包)经继承可访问 `Mob` 的 protected `enemy`/`chooseEnemy`/`state`(public AiState 字段)/`immunities` —— 加 3 个 public 包装方法即可,不动上游 `Mob.java`
- SPD AI 状态机:`public AiState SLEEPING/HUNTING/WANDERING/FLEEING/PASSIVE`,`public AiState state` —— `setMobAi` 直接赋值公开字段即可切换(C3:不破 AI 持久化,Bundle 存 STATE 字符串读回仍生效)
- 瞬移 canonical 路径:`ScrollOfTeleportation.appear(Char, int)` —— `ch.move(pos,false)` + `sprite.place(pos)` + VFX,public static,`blink` primitive 复用
- 空格查找:无现成 `emptyCellNextTo`,自行遍历打乱后的 `PathFinder.NEIGHBOURS8`(不含中心),并且先 `insideMap(c)` 再访问 `Level.passable[c]`,防越界且不返回原 cell
- `Ooze extends Buff` 有 `set(float)`,与 `Poison`/`Bleeding` 同型(level-based),白名单加 entry 零结构改动
- `test_mod/mod.json` `default_enabled=false` 既有 ✓(C3)
- `LuaItemCallbacks.callOpt`/`callOptInt` 自带容错(无函数/Lua 错 → 跳过/回退),新 mob 回调复用此机制

### 关键设计决策(可执行粒度,不改 D5' 方向)

- **距离/相邻走窄 primitive `cellDistance`**:`charPos` 只暴露线性 cell id,Lua 端无 level width/height,不能正确计算 SPD 的 Chebyshev 距离;故新增 `cellDistance(posA,posB)` 直接包装 `Dungeon.level.distance`(斜向相邻=1)
- **`maze_shadow` 用 `act` 触发 blink** 而非原 `interact`(LuaMob 是敌对 mob,非 NPC;且组合 `enemyOf`+`emptyCellNextTo`+`blink` 三个 primitive,最大化覆盖)
- **`hydra` 自体分裂用固定 id `"hydra"`**:源用 `MobFactory:mobByName(self:getMobClassName())`,我们无 MobFactory,直接 `spawnMob("hydra", cell)`(同一 register_mob id,语义等同)
- **`buffer` 暴露 buff 白名单覆盖摩擦**:源 10 个 buff 中 5 个(`Invisibility/Levitation/Charm/Frost/Light`)未在白名单 → `buffer` 只从已支持的 7 个里随机,记摩擦 → M6c 扩白名单依据
- **`shaman_elder` 舍 `zapProc`/`zapMiss`**:依赖 Remished `ManaShield`(SPD 无)+ `skillLevel`(实例方法),记为 luajava 禁摩擦点
- **故意不选 `ScriptedThief`**:遍历 `enemy:getBelongings().backpack.items` 是深度 `chr:method()` 实例直调,确认 M6a 预测的 D5' 升级触发条件,但本 PoC 不引入背包 API(留 M6d item+spell 决策)

## Acceptance

- [x] 5-6 个 Remished mob 在 SPD 内 register + 生成 + 行为正确(放毒气由 M6a `test_blob_rat` 覆盖;本批 6 个覆盖加 buff/特殊 AI/召唤/瞬移)
- [x] 改写**未引入** Remished `scripts/lib/`,纯 `register_mob` + `RPD.*`
- [x] 扩的 id primitive(5 个:`setMobAi`/`enemyOf`/`cellDistance`/`emptyCellNextTo`/`blink`)+ BuffWhitelist 新增项(`Ooze`)记录在案
- [x] **D5' 禁 luajava**(不开 `bindClass`,字符串 id 替代)
- [x] mod 关闭零影响(C3);`./gradlew :core:test --no-daemon` 通过(240 tests)
- [x] **回填「M6c/d 预测」段**:单 mob 平均工时 + 总扩 primitive 数 + 推算 M6c(buff)/M6d(item+spell)工作量 + luajava 禁下摩擦点 → 指导 M6c∥M6d 并行

## M6c/d 预测(完成后回填)

- M6b 改写 mob 数:**6** 个新 mob(`shaman_elder`/`spider_elite`/`deep_snail`/`hydra`/`maze_shadow`/`buffer`) + 既有 M6a `test_blob_rat` 继续覆盖放毒气/免疫基线。
- M6b 扩的 primitive 数:**5** 个 Lua-facing surface:`setMobAi` / `enemyOf` / `cellDistance` / `emptyCellNextTo` / `blink`。配套 infra(非 Lua surface):`LuaMob.setAiTag` / `LuaMob.chooseAndRememberEnemy` / `LuaMob.findEmptyNextTo`;`BuffWhitelist` 新增 `Ooze` 1 项。
- 单 mob 平均工时(含扩桥):**≈ 0.45–0.6h / mob**。本批总 feature 时间约 3–4h(PLAN 细化+codex 评审修正约 1h,扩桥+脚本约 1h,测试/回归修正约 1h+,预测/整理约 0.5h)。后续若 primitive 已覆盖,纯脚本 mob 可降到 10–20min/个;若倒逼新 Java surface,每个 surface 约 20–45min(含测试)。
- 推算 M6c(buff wrapper + 16 脚本)工作量:**≈ 1.5–2.5 天**。主要工作不是 Lua 改写本身,而是把 Remished buff 的生命周期/叠加语义映射到 SPD `Buff` 子类:新增 `BuffWhitelist` entries 低成本(每项几行),但 `ManaShield`/`Charm`/`Frost`/`Light`/`Invisibility` 等需要确认 SPD 等价 buff、duration/level 语义和是否允许脚本 apply/remove/permanent。建议 M6c 先扩 `affectBuff` + `removeBuff` + `permanentBuff` 三类窄 API,并对 3–4 个代表 buff 做单测后批量迁移。
- 推算 M6d(item/spell API 扩容)工作量:**≈ 2–4 天**。`ScriptedThief` 已确认是关键摩擦点:背包遍历、随机选择 item、移除/转移 ownership、loot 持久化都属于深度 `chr:method()` 实例直调,不能靠现有 id primitive 表达。建议 M6d 拆成 item inventory API(如 `randomBackpackItem(heroId)`/`stealItem(mobId, targetId)`/`lootName(mobId)`)与 spell/zap API 两条;先做 2–3 个窄 primitive 验证 thief/launcher 类脚本,不要开通通用 item object 句柄。
- luajava 禁下 buff/item/spell 移植的摩擦点:**(1)** Lua 端拿到的是 char id + cell id,凡是需要 Java instance 方法(`skillLevel`/`getBelongings`/`getLoot`/`getSprite`)都必须显式 id API 化;**(2)** 线性 cell id 不能在 Lua 端正确算距离/相邻,本次用 `cellDistance` 解决;**(3)** `Mob.enemy` 缓存在 Lua `act` 前可能旧/空,本次用主动 `chooseEnemy` wrapper 解决;**(4)** buff 白名单覆盖不足会导致脚本行为降级,必须在 M6c 逐项记录“有 SPD 等价/无等价/需新 wrapper”。
- **M6c∥M6d 并行可行性评估**:**可并行,但需约定 RpdApi 分段命名以降低冲突**。M6c 主要改 `BuffWhitelist` 与 buff primitive;M6d 主要改 item/spell/inventory primitive 和测试脚本。共享冲突点是 `RpdApi.build()` 注册表与 `RpdApiBlobTest`/新测试文件;建议并行时各自新增独立测试类(`RpdApiBuffTest` / `RpdApiItemSpellTest`),`build()` 注册区按 M6a/M6b/M6c/M6d 注释分块,合并冲突可控。

## Risks

- **自定义 wander AI**(luajava 禁下摩擦):Remished `MobAi:getStateByTag` 直调 Java,我们用 `setMobAi(mobId, aiTag)` id API。若某 mob 深度自定义 AI(FSM 状态机),id API 表达不够 → 记摩擦点,可能触发 D5' 重评
- **5-6 mob 累计扩 RpdApi**:每个倒逼 primitive,集中加 RpdApi(本 feature 单 worker 累计扩容,不撞)
- **buff 覆盖**:Remished mob 用到多种 buff,持续扩 BuffWhitelist
- 守 fork 约束:新代码进 `modding/` 子包(C2),不散回上游根包
- C5 proguard:新 primitive 静态包装,无反射,预期不需 keep
- 平衡:PoC mob 默认不进生成池(走调试 / `RPD.spawnMob`),不影响原版难度(C3)
