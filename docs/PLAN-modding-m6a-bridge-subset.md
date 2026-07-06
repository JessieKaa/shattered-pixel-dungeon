# PLAN: M6a — Remished 桥子集(最小 Lua API 扩容)

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M6 / §9 评估附录
> 前置:M5c(`13b0d56a8`)+ M4e(`23e6c6f56`)已合 master
> 本 feature 是 **D5 决策门**(M6b PoC 后定)的前置 —— 只做最小桥,产出成本数据

## Goal

为 M6b mob 改写 PoC(`RemishedFetidRat` 等 5-6 个)补齐**最小** Lua↔Java 桥子集:让一个 Remished 风格 mob(每 tick 放毒气 + 初始化加免疫)能在 SPD 跑起来,且**不开 luajava**(守 M1 沙箱)。

## Context — 对 §9 评估附录的关键修正

§9 用"`@LuaInterface` 713 vs 6"量化缺口,这是**误导性指标**。真实鸿沟是 **luajava 可用性**,不是标注数:

### 架构鸿沟(§9.2 修正)

- **Remished**(`../remixed-dungeon/scripts/lib/commonClasses.lua`,571 行):Lua 用 `luajava.bindClass("com.watabou.pixeldungeon...")` **直接绑定 Java 类**,直调任意静态/实例方法 + `luajava.newInstance`。`RPD` 表 = 一堆 `bindClass` 的 Class 对象 + ~40 个 Lua 包装函数 + 常量子表(`Buffs` 24 / `Blobs` 15 / `Sfx` 25)。`@LuaInterface` 只是软白名单
- **我们**(M1 `LuaSandbox`):**luajava 被禁**(连同 io/os/load/dofile)。Lua 只能调手工构建的 `RpdApi`(当前 16 函数:`affectBuff`/`damageChar`/`healChar`/`GLog`/`charHP`/`charPos`/`spawnMob`/`spawnAlly`/...)+ Lua wrapper 回调槽

### 已确认非缺口(§9 高估部分,实际已有)

| §9 说的缺口 | 实际情况 |
|---|---|
| `act` 回调"关键缺口" | ✅ **已有**(`LuaMob.java` L150-174,takeover 模式:Lua 返回 true 接管 tick,Java 强制 `spend(TICK)` 防冻结) |
| `attackProc`/`defenseProc`/`die` | ✅ 已有 |
| `RPD.glog` / `self:getPos()` | ✅ 已有等价(`GLog`/`charPos`) |
| `@LuaInterface` 713 vs 6 = 10x 缺口 | ❌ 误导。真实 Lua 可调面 = `RpdApi`(16)+ wrapper 回调;Remished = luajava 全开 |

### 真实缺口(RemishedFetidRat 46 行解剖 + RpdApi 现状核对)

1. **`RPD.placeBlob(blobId, pos, amount)`** — 放毒气等区域效果(Remished:`Blobs.Blob:seed` + `GameScene:add`)
2. **`RPD.Blobs` 常量表**(ToxicGas/ParalyticGas/ConfusionGas 等)— Remished 用 Java Class 对象,我们用**字符串 id** 映射(守 luajava 禁)
3. **`RPD.Buffs` 常量表**(Paralysis/Vertigo 等)— 同上,字符串 id,供 `affectBuff`(已有)用
4. **`LuaMob` 加 `spawn` 回调槽** — 初始化时机(register 后首次 act,供 Lua 选 kind/加免疫)
5. (可选)`RPD.setMobAi` / `RPD.blink` — 若 PoC 选中含 AI 切换/瞬移的 mob;M6a 先不做,M6b 按需补

## D5' luajava 策略(本 feature 默认 (a),标为风险)

| 选项 | 做法 | 工作量 | 安全 |
|---|---|---|---|
| (a) **保持禁**(本 PLAN 默认) | 手工 `RpdApi` 扩容,字符串 id 代替 Class 句柄 | 中(每 API 包装) | 高(M1 沙箱不变) |
| (b) 白名单开 luajava | 放开 `bindClass` 限白名单包,复用 Remished 脚本(改包名) | 低(改包名为主) | 低(白名单包内任意 Java 可调) |
| (c) 混合 | trusted 内置 mod 开,第三方禁 | 中-高(两套规则) | 中 |

**默认 (a)**:守 M1 沙箱 + fork C 约束(平台定位是"可选内容包",非"任意代码执行")。若 M6b PoC 证明手工翻译成本不可接受(某 mob 深度依赖 `chr:method()` 实例直调),升级到用户决策 D5'。

## Files

- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/RpdApi.java`(主改):加 `placeBlob`、`addImmunity`、`Blobs`/`Buffs` 常量表 builder,以及 `BlobRegistry`(id→Class 解析)
- `core/.../modding/LuaMob.java`:加 `spawn` 回调时机(首次 act,`private boolean spawned` 标志,持久化,调用一次)+ `public void addLuaImmunity(Class)`(经由继承访问 `Char.immunities` protected 字段)
- `core/src/main/assets/mods/test_mod/scripts/mobs/test_blob_rat.lua`(新增):PoC mob(FetidRat 风格)
- `core/src/test/java/.../modding/RpdApiBlobTest.java`(新增):placeBlob + 常量映射 + addImmunity + spawn-once 单测
- `android/proguard-rules.pro`:placeBlob/addImmunity 是静态/直接字段包装,无反射入口(`Blob.seed` 内部的 `Reflection.newInstance` 已被上游既有 keep 覆盖),预期**不需要**新 keep

## Phase 1 细化笔记(worker 补,不改 Goal/Context 方向)

实施前代码核对结论:

- `RpdApi.build()` 现有 16 函数,PLAN 计数正确 ✓
- `LuaMob.act()` takeover 路径在 L150-174 ✓(`fn.call(id())` 返回 true → skip super + `spend(TICK)`)
- `LuaSandbox.exposedGlobals()` L102 `strip(g, "luajava")` ✓(D5'-(a) 守住)
- `affectBuff(charId, buffName, amount)` **已接受字符串 id**(经 `BuffWhitelist.lookup`),故 `Buffs` 常量表只暴露字符串 key,**不改 affectBuff 签名**
- 规范 blob 语义:`GameScene.add(Blob.seed(cell, amount, TypeClass))`(DM200/FetidRat/Elemental 一致);`Blob.seed` 3-arg 走 `Dungeon.level`
- `ToxicGas.evolve()` L49 `if (!ch.isImmune(this.getClass()))` → 免疫经 `immunities.add(ToxicGas.class)` 实现

### 关键修正:PoC 的"毒免疫"机制(PLAN 原文 affectBuff 是错的)

PLAN 步骤 5 原文 `spawn=function(selfId) RPD.affectBuff(selfId, RPD.Buffs.Poison, 9999) end`(标注"毒免疫")**语义反了**:这会**给 mob 上 Poison**(每 tick 掉血),不是免疫。真正的 FetidRat 经由 `immunities.add(StenchGas.class)`(基于 `Class` 的免疫,`Buff.affect` L69 / `Char.isImmune` L1326 检查此集合)。

Goal 明确要求"**初始化加免疫**",故桥必须能表达免疫。在 luajava 禁的前提下,最小正确做法 = 新增第 5 个 API `RPD.addImmunity(charId, id)`,把 id 解析成 `Class` 加进目标 `Char.immunities`。

- 这是**可执行粒度细化**(workflow Step 5),**不改 D5'-(a) 方向**(仍是手建 RpdApi、仍禁 luajava)
- `immunities` 是 `Char` 的 `protected final HashSet<Class>`,`LuaMob`(跨包子类)经 `this.immunities` 继承访问合法;故在 `LuaMob` 加 `public void addLuaImmunity(Class)` 即可,不动上游 `Char`
- 范围:`addImmunity` 仅对 `LuaMob` 生效(非 LuaMob → log + NIL,不动上游硬编码免疫)。PoC 是 LuaMob,够用
- 这是 M6a 成本数据的关键一条:免疫是 Goal 硬需求,计入 API 数比"假装用 affectBuff"更诚实

### spawn-once 的持久化

`spawned` 标志持久化进 Bundle(存/读),保证"一次且仅一次"跨存档成立(否则读档后首 act 会二次触发 spawn,重新 addImmunity 虽幂等但违反 Acceptance)。

## Steps

1. **RpdApi 加 `BlobRegistry`**:新内部静态 `Map<String, Class<? extends Blob>>`,注册 14 个常用 blob(ToxicGas/ParalyticGas/ConfusionGas/StenchGas/Fire/Web/Freezing/Blizzard/Inferno/Regrowth/SmokeScreen/StormCloud/Electricity/CorrosiveGas,各 5+ 条满足 Acceptance)。`lookupBlob(id)` → Class 或 null
2. **RpdApi 加 `Buffs` 常量表 + Class 查询**:`BuffWhitelist` 增补一个平行 `Map<String,Class<?>> BUFF_CLASSES`(各 put 处同时记录 Class,9 条:Roots/Slow/Cripple/Paralysis/Vertigo/Haste/Bleeding/Poison/Barkskin)。`rpd.set("Buffs", ...)` 把这 9 个 simple-name → simple-name 字符串放进 LuaTable(值=内部 key,内部改名不破 Lua)。`lookupBuffClass(name)` 供 addImmunity 用
3. **RpdApi 加 `Blobs` 常量表**:`rpd.set("Blobs", blobConstants())` 同 Buffs 模式,14 个 name→name 字符串
4. **RpdApi 加 `placeBlob(blobId, pos, amount)`**(`PlaceBlob extends ThreeArgFunction`):blobId 查 BlobRegistry,失败 log+NIL;pos 非 int 或 amount 非 validAmount → log+NIL;`Dungeon.level == null`(headless)→ log+NIL 不抛;**`!Dungeon.level.insideMap(cell)` → log+NIL**(codex round-1 must-fix:越界 cell 会让 Blob.seed→gas.seed 的 `cur[cell]+=amount` 越界,留半初始化 blob);否则 `GameScene.add(Blob.seed(cell, (int)amt, clazz))`(规范 3-arg,内部走 Dungeon.level)
5. **RpdApi 加 `addImmunity(charId, id)`**(`AddImmunity extends TwoArgFunction`,第 5 个 API):resolveChar → 非 LuaMob → log+NIL;id 先查 BlobRegistry 再查 BUFF_CLASSES,都失败 → log+NIL;`((LuaMob)target).addLuaImmunity(id, clazz)`(同时传 id 与 Class,LuaMob 记录 id 列表供持久化)
6. **LuaMob 加 `spawn` 回调 + 持久化**:`private boolean spawned=false;` + `LUA_SPAWNED` Bundle key;`act()` 最开头若 `!spawned` → `spawned=true; callOpt(tbl,"spawn",valueOf(id()))`(callOpt 自带容错:无 spawn 函数则跳过,Lua 抛错只 log);storeInBundle/restoreFromBundle 持久化 spawned。**注意**:spawned 持久化后读档不重跑 spawn → spawn 里 addImmunity 加的免疫会丢(Char.storeInBundle 不持久化 immunities)→ 见 step 7 的免疫持久化修复(codex round-1 must-fix)
7. **LuaMob 加 `addLuaImmunity` + 免疫持久化**(codex round-1 must-fix):`private final List<String> luaImmunityClassNames = new ArrayList<>();` `public void addLuaImmunity(String id, Class type){ immunities.add(type); luaImmunityClassNames.add(type.getName()); }`(继承访问 protected immunities;**存 FQCN `type.getName()` 而非 simple id**(codex round-2 must-fix:`Reflection.forName("ToxicGas")` 会失败,必须 FQCN 如 `com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas`));storeInBundle 存 `LUA_IMMUNITY_CLASSES`(FQCN String 数组);restoreFromBundle 经 `Reflection.forName(fqcn)` 逐个重建 immunities + 回填 luaImmunityClassNames(白名单在 addLuaImmunity 入口已守,持久化的 FQCN 来自此前白名单内的 Class,Reflection.forName 仅为重建不做新输入解析,与 SPD 既有 bundle-restore 用 Reflection 的模式一致)
8. **PoC mob** `test_blob_rat.lua`:`register_mob{id="test_blob_rat",name="毒气鼠",hp=20,ht=20,attack=8,defense=3,sprite="rat"}`;`spawn=function(selfId) RPD.addImmunity(selfId, RPD.Blobs.ToxicGas) end`(毒气免疫);`act=function(selfId) RPD.placeBlob(RPD.Blobs.ToxicGas, RPD.charPos(selfId), 50); return false end`(return false → 走上游 AI,tick 末放毒气)
9. **单测** `RpdApiBlobTest`:(a) Blobs/Buffs 表覆盖所有注册 id;(b) placeBlob 在 headless(Dungeon.level=null)返回 NIL 不抛 + 坏 id/坏 pos 返回 NIL + **越界 pos 返回 NIL 不抛**(codex round-1 must-fix 覆盖);(c) addImmunity 对 LuaMob 生效(`isImmune(ToxicGas.class)==true`),非 LuaMob 返回 NIL;(d) spawn 回调 drive `act()` 两次仅触发一次(spawn 内计数,act 返回 true 走 takeover 路径避开 super.act 的 NPE);(e) **LuaMob Bundle round-trip 后 spawned=true 且 addLuaImmunity 加的免疫仍生效(`isImmune` 仍 true)**(codex round-1 must-fix 覆盖);(f) luajava 仍 stripped(沙箱回归)
10. **C3 回归**:test_mod `default_enabled=false`(既有),test_blob_rat.lua 只在 test_mod 开启时由 LuaEngine 扫描加载;既有 218 tests 不回归
11. **设备验证**(可选,worker 自行决定是否跑):开 test_mod → `RPD.spawnMob("test_blob_rat", pos)` → 观察毒气扩散 + mob 不被自己毒伤 + 不闪退

## Acceptance

- [ ] `RPD.placeBlob(RPD.Blobs.ToxicGas, pos, 50)` 可调,毒气区域效果出现(设备或 headless scene 测试)
- [ ] `RPD.Blobs` / `RPD.Buffs` 常量表覆盖 PoC 用到的 Blob/Buff(各 5+ 个常用项)
- [ ] `LuaMob.spawn` 回调在 mob 生命周期调用**一次且仅一次**(单测覆盖)
- [ ] PoC mob(`test_blob_rat`)在 SPD 内生成 + 行为正确(放毒气 + 毒免疫);mod 关闭时零影响(C3)
- [ ] 新增单测通过;既有 218 tests 不回归
- [ ] **回填本 PLAN 末尾"M6b 预测"段**(M6a 实际补的 API 数 + 工时 → 单 mob 倒逼 API 数 → D5 建议)

## M6b 预测(本 feature 完成后由 worker 回填)

- M6a 补的 API 数:__
- M6a 实际工时:__
- 单 mob 倒逼 API 数(FetidRat 实测):__
- 推算 5-6 mob PoC 总缺口(M6b 要补的 API):__
- **D5 建议**:(a) B 全量 / (b) B-mini 止 / (c) 转 C 路径
- **D5' 是否需升级**(luajava 是否必须开):是 / 否 + 理由

## Risks

- **luajava 禁的根本限制**:Remished 脚本里 `chr:method()` 直调实例方法(如 `belongings.backpack.items:get(i)`)无法翻译,只能走 id-based API。若 PoC mob 行为深度依赖实例直调 → M6b 成本飙升 → 触发 D5' 升级(这恰恰是 M6a 要产出的信号)
- **spawn 回调时机**:首次 act 可能太晚(若 mob 需在生成瞬间初始化)。备选:`Actor.add` / `Dungeon.level.spawnMob` 路径触发。M6a 用首次 act,M6b 验证不够再调
- **常量表维护负担**:字符串 id→Class 映射要集中维护,新增 Blob/Buff 要更新表 + 测试。可接受(枚举有限)
- C5 proguard:placeBlob 静态包装,无反射入口,预期不需要新 keep
- 平衡:PoC mob 默认不进生成池(只走 `RPD.spawnMob` / 调试入口),不影响原版难度(C3)
