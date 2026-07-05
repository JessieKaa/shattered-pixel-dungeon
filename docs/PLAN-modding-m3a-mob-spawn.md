# PLAN — modding-m3a-mob-spawn

> 里程碑:**M3a mob 双源 + spawnMob API(M3 第一弹)**
> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M3
> 前置:M0+M1+M2-Item 已合入 master(Lua 物品回调 + RPD API + 沙箱已就位)
> M3 决策(已定):D1 消耗性 spell / D2 复用天赋 / D3 做宠物 / D4 保留硬编码

---

## Goal

让 Lua 能定义**敌对 mob** + 通过 `RPD.spawnMob(mobId, pos)` 注入式 spawn + Lua mob 有 AI 回调(`act`/`attackProc`/`die`)。**完全不动 `Level.createMob`/`MobSpawner`/`mobsToSpawn`**(C3/C4)。

为 M3b 宠物(友好 mob,基于 `DirectableAlly`)铺路——M3a 只做敌对,友好留 M3b。

## Context

- **M0/M1/M2 已交付**:Lua 物品(回调 + RPD API + 沙箱 + Generator 双源)。M2 建立的范式:**charId + Actor.findById**、**LuaItemCallbacks.callOpt/callOptInt**、**Registry + hydrate**、**窄 RPD 函数注入**。M3a 把这套范式映射到 mob 端。
- **M3a 调研关键发现**(worker 必读):
  1. **`Mob` 是 abstract Char**,AI 是状态机(`SLEEPING/WANDERING/HUNTING/...`,实例字段非枚举),`Mob.act()`(`Mob.java:225`)是最顶层 hook——override 它即接管 AI tick
  2. **Alignment(`ENEMY/ALLY/NEUTRAL`)控制敌对**:`alignment=ENEMY` 默认敌对;`Mob.chooseEnemy()` 自带 ALLY 分支
  3. **`DirectableAlly`**(`actors/mobs/npcs/DirectableAlly.java`,MirrorImage/PrismaticImage 父类)是友好 mob 基类(supports `followHero/defendPos/targetChar`)。**M3a 不用,M3b 宠物用它**
  4. **`Level.createMob`/`MobSpawner.getMobRotation` 每关硬编码**;`Level.spawnMob` 用 `GameScene.add(mob)` 注册。**`RPD.spawnMob` 在 `GameScene.add` 级注入,绕过 createMob → C3/C4 完整**(spawn rotation/nMobs 平衡不动)
  5. **Remixed `MobFactory` + `CustomMob`**(单占位类)+ `scripts/lib/mob.lua`(回调 `act/attackProc/die/...`)。Remixed 直接传 `self`(Char)给 Lua——**M3a 不复制**(违反 M1 沙箱),用 `charId`
- **范围决策(基于调研,已定)**:
  - M3a 只做**敌对 LuaMob(`extends Mob`)**,友好 mob(宠物,`extends DirectableAlly`)留 M3b
  - **不动 `Level.createMob`/`MobSpawner`**(C4);mob 不进原版 spawn 池(C3)
  - Lua mob 通过 `RPD.spawnMob` 注入(召唤/事件场景),非原版 spawn

## 关键决策(worker 实施前确认;有疑问 `[BLOCKED]`)

### D1 LuaMob 基类(敌对 extends Mob)

`LuaMob extends Mob`(不是 DirectableAlly)。默认 `alignment=ENEMY`。从 Lua table 读 `hp`/`ht`/`attack`/`defense`/`name`/`sprite`(复用现有 mob sprite,M3a 不画新图)+ AI 回调字段。friendly(宠物)留 M3b(那时建 `LuaAlly extends DirectableAlly`)。

### D2 AI 回调机制(沿用 M2 范式)

`LuaMob` override `act()`/`attackProc(Char,Char,int)`/`defenseProc`/`die(Object)`:
- **每个 override 先 `super`**(跑上游 Mob AI/proc 链),再 `LuaItemCallbacks.callOpt/callOptInt`(直接复用 M2 工具类)
- 参数用 `charId`(`this.id()` + `enemy.id()`),不 coerce Char(D3 一致:M1 沙箱无 per-call 拦截,coerce 暴露 Char→belongings→Dungeon)
- **`act()` 特殊**:若 Lua `act(selfId)` 返回 true,**短路** `super.act()` 的 AI 调度(Lua 完全接管 tick);返回 false/无函数 → 走上游 Mob AI
- 无回调字段 fallback 上游 Mob 行为(M0 degraded 范式)

### D3 spawnMob 不动 Level.createMob(C3/C4 核心)

`RPD.spawnMob(mobId, pos)` → `LuaMobRegistry.create(mobId)` → `mob.pos = pos` → `GameScene.add(mob)`(与 `Level.spawnMob:762` 同注册点)。**不触及 `Level.createMob`/`MobSpawner`/`mobsToSpawn`**。Lua mob 不在原版 spawn rotation 里,只在 `RPD.spawnMob` 调用时出现(召唤/事件)。

### D4 持久化(沿用 LuaItem 范式)

`LuaMob` 固定 Bundle className `LuaMob` + `lua_mob_id` 字段。`storeInBundle`/`restoreFromBundle` 镜像 `LuaItem`(`Mob` 的 storeInBundle 已存 STATE/ENEMY_ID,加 `lua_mob_id`)。restore 时 `LuaMobRegistry.getTable(id)` re-hydrate。Bundle 反序列化 `Reflection.newInstance(LuaMob.class)` 要无参构造。

## Files

`✚` 新增 / `✎` 修改(均在 `core/.../modding/`,C2)

- `✚ core/.../modding/LuaMob.java` — `extends Mob`,override `act/attackProc/defenseProc/die`(D2),hydrate 数值 + 回调字段(D1),持久化(D4)
- `✚ core/.../modding/LuaMobRegistry.java` — `Map<String, LuaTable>` + `register/getTable/create/ids`(1:1 类比 `LuaItemRegistry`)
- `✎ core/.../modding/LuaEngine.java` — `register_mob(table)` global(校验 `id`/`name`/`hp`/`ht`/`attack`/`defense` 必填,函数字段可选),类比 `register_item`
- `✎ core/.../modding/RpdApi.java` — 加 `spawnMob(mobId, pos)`(`TwoArgFunction`,`LuaMobRegistry.create` + `GameScene.add`,**不触及 Level.createMob**)
- `✚ core/src/main/assets/scripts/mobs/test_mob.lua` — 测试敌对 mob:定义 hp/ht/attack + `act(selfId)` 简单 AI(或 fallback 上游)+ `attackProc` 回调
- `✚ core/src/test/java/.../modding/LuaMobTest.java` — 注册 + spawn + AI 回调测试(headless,无 GameScene 则 mock 或只测 Registry/hydrate/回调调度)
- `✎ android/proguard-rules.pro` — keep `LuaMob` + modding 子包(若验证 release)

## Steps

每步独立可验证。

1. **写 LuaMobRegistry** — 1:1 类比 LuaItemRegistry(`Map<String,LuaTable>` + register/getTable/create/ids)。单测:register + create 返回 LuaMob。
2. **写 LuaMob(extends Mob)** — 无参构造(Bundle 反序列化)+ hydrate(table 读 hp/ht/attack/defense/name/sprite + 回调字段缓存)+ storeInBundle/restoreFromBundle(`lua_mob_id` re-hydrate)。**单测**:hydrate 后字段正确;restore 后 re-hydrate 一致。
3. **AI 回调 override(D2)** — `act()`(super + callOptInt,selfId;返回 true 短路)/ `attackProc`/`defenseProc`/`die`(super + callOpt,selfId+enemyId)。**复用 `LuaItemCallbacks`**。单测:mock table + 回调字段,验证 call 工作;无字段 fallback 上游。
4. **register_mob global** — `LuaEngine` 加 `globals.set("register_mob", ...)`,校验必填字段后 `LuaMobRegistry.register`。
5. **RPD.spawnMob(D3)** — `RpdApi` 加 `spawnMob(mobId, pos)`,`LuaMobRegistry.create` + `mob.pos=pos` + `GameScene.add(mob)`。**不调 Level.createMob**。单测:验证 spawnMob 不触发 Level.createMob(可用 spy 或检查 mobsToSpawn 不变)。
6. **测试 Lua mob** — `test_mob.lua`(敌对,hp/ht/attack + act/attackProc 回调)+ LuaMobTest 全链路(register → spawn → AI 回调)。
7. **沙箱回归** — Lua 不能 `luajava.bindClass`(M1 沙箱不破);charId 范式一致(Lua 拿不到 Char 对象)。
8. **回归(C3)** — M0/M1/M2 测试全过(LuaEngineTest/LuaSandboxTest/GeneratorLuaItemTest/LuaItemCallbackTest);原版 spawn 不变(Level.createMob 未改,Grep 确认)。
9. **(加分)release proguard**(C5)。

## Acceptance

- [ ] LuaMobRegistry 工作(register/getTable/create/ids)
- [ ] LuaMob hydrate 数值 + 持久化(`lua_mob_id` re-hydrate,Bundle round-trip)
- [ ] AI 回调:`act`(返回 true 短路 super)/`attackProc`/`die` 工作(无字段 fallback 上游 Mob AI)
- [ ] `register_mob` global 工作
- [ ] `RPD.spawnMob(mobId, pos)` 注入 mob(`GameScene.add` 级),**不触及 `Level.createMob`/`MobSpawner`/`mobsToSpawn`**(测试 + Grep 确认)
- [ ] **沙箱不破**:Lua 不能 luajava.bindClass(M1 回归测试)
- [ ] M0/M1/M2 测试无回归
- [ ] 原版 spawn 平衡不变(C3:`Level.createMob` 零改动)
- [ ] fork 代码在 `modding/` 子包(C2);**上游零改动**(C4:不碰 `Level`/`Mob`/`MobSpawner`,只新增 modding/ 文件 + 标注)
- [ ] `:core:compileJava` / `:desktop:debug` / `:android:assembleDebug` 通过
- [ ] codex 评审通过(PLAN + 实施)

## 风险

- **`act()` 短路语义**:Lua `act` 返回 true 短路 super.act()——需保证 mob 仍消耗 tick(`Actor.spend`)否则卡死。worker 验证:即使 Lua 接管,也要 `spend(time)` 推进时间。**这是关键正确性点**(codex 重点审)。
- **GameScene.add 在 headless 测试不可用**:LuaMobTest 可能无法测真实 spawn。降级:测 Registry + hydrate + 回调调度(不调 GameScene.add),spawnMob 的 GameScene 路径靠 desktop 实测或代码审查。
- **mob sprite**:LuaMob 复用现有 mob sprite(M3a 不画新图)。worker 选一个现有 sprite(如 `SpriteLoader` 找一个)。
- **Bundle className 冲突**:LuaMob 是新类,不与现有 mob class 冲突。但 `Level.NMobs`/`mobsToSpawn` 用 Class 存储——LuaMob 不进那个列表(只 GameScene.add),无冲突。
- **proguard**(C5):release keep LuaMob + 反射。

## 参考

- `docs/MODDING-ROADMAP.md` §4 M3
- `docs/PLAN-modding-m2-item-api.md`(M2-Item 上下文,范式直接复用)
- `core/.../actors/mobs/Mob.java`(`act`/`attackProc`/`die` hook + Alignment)
- `core/.../actors/mobs/npcs/DirectableAlly.java`(M3b 宠生基类,M3a 暂不用)
- `core/.../levels/Level.java`(`createMob`/`spawnMob`,**M3a 不动**)
- M2 已有:`core/.../modding/{LuaItem,LuaItemCallbacks,LuaItemRegistry,RpdApi,LuaEngine}.java`(范式 1:1 映射)
- `../remixed-dungeon/scripts/lib/mob.lua`(回调签名参考,不照搬 luajava/self)

## 范围决策记录

- **M3a 只做敌对 LuaMob(`extends Mob`)**。友好 mob(宠物,`extends DirectableAlly`)留 M3b。
- **spawnMob 注入式**(不动 `Level.createMob`),mob 不进原版 spawn 池。
- **AI 回调沿用 M2 范式**(charId + LuaItemCallbacks + super-then-Lua)。
- **`act()` 短路语义**:Lua 返回 true 接管 tick,但必须 `spend` 推进时间(关键正确性)。

---

## 实施细节(worker 阶段 1 细化,核对了源码)

### 核对结论(5 条调研发现 vs 源码)

1. **`Mob.act()`(Mob.java:225)**:先 `super.act()`(=`Char.act()` @ Char.java:197,只更新 fieldOfView + IMMOVABLE throwItems,**不 spend**),再处理 paralysed/Terror/Feint,最后 `state.act(enemyInFOV, justAlerted)`(AI 状态机,各 state 内部 `spend(TICK)` 或 `spend(1/speed())`)。**确认**:override `Mob.act()` 即接管 AI tick 顶层 hook。
2. **`alignment`**:Char.java:183 `enum Alignment`,字段 `public Alignment alignment`(Char.java:188)。Mob 实例 init 块(Mob.java:106-110)默认 `alignment = Alignment.ENEMY`。LuaMob `extends Mob` 自动敌对,无需额外设。
3. **`GameScene.add(Mob)`(GameScene.java:1145→1153)**:`Dungeon.level.mobs.add(mob)` + `scene.addMobSprite(mob)` + `Actor.addDelayed` + `mob.spendToWhole()`。**与 `Level.spawnMob`(Level.java:742-764)第 756 行 `GameScene.add(mob)` 同注册点**。`RPD.spawnMob` 复用此入口即 C3/C4 完整。
4. **`Level.createMob`(Level.java:508)**:`mobsToSpawn` + `MobSpawner.getMobRotation` —— **M3a 零改动**(C3/C4)。
5. **`Actor.TICK = 1f`(Actor.java:39,public static final)**,`Char.id()`→`Actor.id()`(Actor.java:135)可用。`Char.spend(float)`(Char.java:1122,protected)LuaMob 可直接调。

### D1 LuaMob 字段映射(细化)

Lua table → Java 字段(复用 M2 `hydrate(table)` 范式):

| Lua 字段 | Java | 说明 |
|---|---|---|
| `id`(string,必填) | `luaMobId` | 持久化键 `lua_mob_id`,restore 时 `LuaMobRegistry.getTable(id)` re-hydrate |
| `name`(string,必填) | `nameStr` | override `name()` 返回(沿用 LuaItem 思路) |
| `hp`(int,必填) | `HP = HT = hp` | 当前/最大血量 |
| `ht`(int,可选,默认=hp) | `HT = ht` | 最大血量(覆盖 hp 设的 HT) |
| `attack`(int,必填) | `attackStat` | `damageRoll()` 返回 `Random.NormalIntRange(max(1,attack-2), attack+2)`;`attackSkill(target)` 返回 `attack` |
| `defense`(int,必填) | `defenseSkill = defense`(Mob 字段) | Mob.defenseSkill(enemy) 默认读此字段(非 surprised 时) |
| `sprite`(string,可选) | `spriteClass` | 见下「Sprite 白名单」,缺省 `CrabSprite` |
| `act`/`attackProc`/`defenseProc`/`die`(function,可选) | 不缓存,经 `luaTable()` 实时取 | 无字段 → fallback 上游 Mob AI |

构造:`public LuaMob()`(Bundle Reflection.newInstance 无参) + `LuaMob(LuaTable)`(hydrate)。实例 init 块已设 `alignment=ENEMY`(继承 Mob)。

### Sprite 白名单(M3a 不画新图,worker 决策)

LuaMob 内置一个小 `Map<String, Class<? extends CharSprite>>` 名字→现成 sprite 类,避免引 sprite 注册系统:

```
"crab"→CrabSprite, "rat"→RatSprite, "slime"→SlimeSprite,
"gnoll"→GnollSprite, "brute"→BruteSprite, "skeleton"→SkeletonSprite,
"bat"→BatSprite
```

`hydrate` 读 `sprite` 字符串,optint 缺省 → `"crab"`。未知名也 fallback `CrabSprite`(不抛错,M0 degraded 范式)。M3b 可扩成完整 sprite registry。

### D2 `act()` 短路 + spend(关键正确性,细化)

```java
@Override
protected boolean act() {
    LuaTable tbl = luaTable();
    LuaValue fn = (tbl != null) ? tbl.get("act") : LuaValue.NIL;
    if (fn.isfunction()) {
        try {
            LuaValue res = fn.invoke(LuaValue.varargsOf(LuaValue.valueOf(id()))).arg1();
            if (res.isboolean() && res.toboolean()) {
                // Lua 完全接管 tick:跳过 Mob.act() 的 AI 状态机(state.act)。
                // 关键正确性:Lua 无法调 Actor.spend(protected),RPD 也无 spend 原语,
                // 必须由 Java 兜底 spend(TICK),否则该 mob 占住 Actor 时间队列首部,
                // 整局游戏循环卡死。Char.act() 的 FOV housekeeping 也跳过(Lua 走 RPD API
                // 用 charId 直查 Actor.findById,不依赖 fieldOfView)。
                spend(TICK);
                return true;
            }
        } catch (Exception e) {
            Gdx.app.error(TAG, "act callback threw", e);
            // Lua 出错 → 降级走上游 AI,绝不卡死
        }
    }
    return super.act();  // Mob.act() 上游 AI
}
```

- `super.act()` = `Mob.act()`(含 AI 状态机)。Lua 返回 true 时**不**调它(短路)。
- `attackProc`/`defenseProc`/`die` 沿用 M2 范式:`super` 在前 + `LuaItemCallbacks.callOptInt/callOpt`,无字段/出错 fallback 上游。

### D3 `RPD.spawnMob(mobId, pos)`(细化)

`RpdApi` 加 `TwoArgFunction SpawnMob`:
1. `mobId` 必须 jstring 且 `LuaMobRegistry.contains(id)`(否 NIL + log)
2. `pos` 必须 int(Lua 传非法值时由 GameScene.add/level 自然拒绝,M3a 不做深度校验)
3. `LuaMob m = LuaMobRegistry.create(id); m.pos = pos; GameScene.add(m)`
4. **Grep/审查确认方法体不出现 `Level.createMob`/`MobSpawner`/`mobsToSpawn`**

### 测试策略(细化,呼应风险 #2)

`LuaMobTest`(headless, `HeadlessApplication` 套路同 `LuaItemCallbackTest`):
- **可测**:`LuaMobRegistry` register/getTable/create/ids/clear;`LuaMob` hydrate 字段(HP/HT/attack/defense/name/spriteClass);Bundle round-trip(`storeInBundle`→`restoreFromBundle` 后 `lua_mob_id` 一致 + re-hydrate);`register_mob` 校验(缺必填字段被拒);M1 沙箱回归(`luajava.bindClass` 仍失败,RPD.spawnMob 注入后不破);`act()` Lua 返回 true 路径 `spend` 被调用(用 `Actor.now()` 前后差或 `nextMove`/直接反射 — 若不可靠则改 desktop 实测 + 代码审查)。
- **不可 headless 测(降级)**:真实 `GameScene.add` 需 `Dungeon.level`+scene,headless 会 NPE。`spawnMob` 的 GameScene 路径靠 (a) 代码审查确认走 `GameScene.add` 非 `Level.createMob`,(b) desktop 实测。`LuaMobTest` 只断言 `LuaMobRegistry.create` + `mob.pos` 赋值正确。

### proguard(C5,细化)

现有规则已有:
- `-keep class * implements com.watabou.utils.Bundlable { *; }`(Mob→Char→Bundlable,LuaMob 自动覆盖)
- `-keep class com.shatteredpixel.shatteredpixeldungeon.modding.** { *; }`(M2 已加,覆盖 LuaMob/LuaMobRegistry)
- `-keep class * extends com.watabou.noosa.Gizmo { *; }`(CharSprite)

→ **LuaMob 反射链路已全覆盖,无需新规则**。补一行注释 `# M3a LuaMob: covered by modding.** + Bundlable rules above` 即可。

### 上游零改动(C4 核对)

Files 清单全部在 `core/.../modding/` 子包(新增 LuaMob/LuaMobRegistry/test_mob.lua)或已存在的 modding 文件(LuaEngine/RpdApi 局部增改)。**`Level.java`/`Mob.java`/`MobSpawner.java`/`GameScene.java`/任何上游文件零改动**。`GameScene.add(Mob)` 是 public static 现成 API,只是被调用,不改其源码。
