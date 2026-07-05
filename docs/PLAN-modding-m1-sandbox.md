# PLAN — modding-m1-sandbox

> 里程碑:**M1 沙箱 + 内容注册管线(物品)**
> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M1
> M0 前置:已合入 master(`feat(modding-m0-lua-poc)`)
> 本 PLAN 是 feature_worker 的实施依据;codex_reviewer 评审本 PLAN + 实施。

---

## Goal

在 M0 Lua 闭环上加两层:
1. **Lua 沙箱**——`@LuaInterface` 注解 + 编译期 processor 生成白名单 map + 运行期**白名单暴露**,把 Lua 代码能访问的 Java API 限制在白名单
2. **Generator 双源**——把 Lua 物品接入 SPD `Generator`,让 Lua 物品能进掉落池

验证完整链路:**Lua 定义 → 沙箱边界 → 工厂注册 → 游戏内掉落**。

## Context

- **M0 已交付**(`core/.../modding/`):`LuaEngine`(luaj + `register_item` global)/ `LuaItem`(extends MeleeWeapon + Bundle 序列化)/ `LuaItemRegistry`(Map<id,LuaTable>)/ `LuaDebugService`(debug 按钮给物品)。**M0 无沙箱,Lua 物品不进 Generator**。
- **M1 调研关键发现**(决定本 PLAN 设计,worker 必读):
  1. **Remixed 沙箱运行期是空壳**:Remixed 的 `LuaSandbox.warnIfNotAllowed` 在整个源码**零调用点**——它只生成 `lua-interface-map.json` 但没在 luaj 调用路径实装拦截。**M1 必须自研 luaj 白名单暴露**(见 D1)。
  2. **SPD Generator 是 Class 数组 + Reflection**(`Generator.java`):`Category.classes: Class[]` + `Reflection.newInstance(itemCls)`,**无 id-keyed map**。Lua 物品都是 `LuaItem`(同质包装),无独立 Class。双源要重设计(见 D2)。
  3. **SPD 无 mob 工厂**:`Level.createMob()` 每关硬编码返回 `new XxxMob()`。做 mob 双源要新建 MobFactory 间接层(改动面大)。
  4. **Bundle 序列化**:M0 已解决 LuaItem 持久化(固定 className `LuaItem` + `lua_item_id` 字段)。多类 Lua 物品需扩展(见 D3)。
- **范围决策**(基于调研,已定):
  - **M1 砍 mob**:mob 工厂间接层改动面大、风险高;mob 推到 M2 或独立里程碑 M1.5。本 PLAN 只做**物品双源**。
  - **M1 砍外部 mod 目录扫描**:SPD 无外部存储 mod 概念,留给 M5(mod 治理)。M1 只支持内置 `assets/scripts/`。

## 关键决策(worker 实施前确认;有疑问 `[BLOCKED]` 上报,别瞎猜)

### D1 luaj 沙箱实现路径(推荐方案 A:白名单暴露)

- **方案 A(推荐)**:**白名单暴露**——LuaEngine 构造 globals 时,只把 `@LuaInterface` 标注的 Java 类/方法注入 Lua 全局;移除 `JsePlatform.standardGlobals()` 的危险部分(`io`/`os`/`loadfile`/`load`/`dofile`/`debug`/`getfenv`/`setfenv`)。Lua 根本访问不到未授权对象,**无需运行期拦截**。
- 方案 B:运行期 hook `globals.load` 或 CoerceJava,校验每次 Java 访问(复杂,luaj 无现成 hook 点——Remixed 想做但没做成)。
- **推荐 A 的理由**:更简单更安全(看不到就访问不到);Remixed 设计了运行期校验但没实装,正好说明 B 难做。
- **注意**:M0 的 `test_sword.lua` 用 `register_item`(自定义 global),不依赖 `io`/`os`。改造 globals 后 M0 测试应仍通过(worker 验证,若破则 [BLOCKED])。

### D2 Generator 双源方案(推荐方案 A:独立 Category)

- **方案 A(推荐)**:新增 `Generator.Category.LUA_ITEM`,在 `random()` 里按概率决定是否掉 Lua 物品(从 `LuaItemRegistry` 随机选 id → `create(id)`)。
- 方案 B:在 `Generator.random()` 的 `Reflection.newInstance` 处加 if 分支。
- 方案 C:动态生成 LuaItem 子类(复杂,不建议)。
- **C3 保证**:LUA_ITEM Category 概率设默认 0 或极低(可配置开关),原版掉落平衡不动。worker 在 PLAN 实施记录里说明默认概率值。

### D3 多类 Lua 物品(M1 仍只做武器)

- M0 只做 `LuaItem extends MeleeWeapon`。M1 是否扩展 `LuaArmor`/`LuaPotion`?
- **推荐**:M1 仍只做武器类。多类型留给 M2(API 暴露时扩展)。降低 M1 范围。

## Files

`✚` 新增 / `✎` 修改 / `?` worker 调研后定

- `✚ annotation/`(新独立 Gradle 模块)— `@LuaInterface` 注解(照搬 `../remixed-dungeon/annotation/src/main/java/com/nyrds/LuaInterface.java`:marker,`@Target={METHOD,TYPE,FIELD,CONSTRUCTOR}`)。`build.gradle`:`java-library`。
- `✚ processor/`(新独立 Gradle 模块)— `LuaInterfaceProcessor`(`@AutoService(Processor)`,照搬 `../remixed-dungeon/processor/src/main/java/com/nyrds/LuaInterfaceProcessor.java`),编译期生成 `lua-interface-map.json`(可达闭包 BFS,从类级 `@LuaInterface` 根出发)。依赖 `:annotation` + `com.google.auto.service:auto-service`。
- `✎ settings.gradle` — `include ':annotation', ':processor'`
- `✎ core/build.gradle` — `annotationProcessor project(':processor')` + `compileOnly project(':annotation')`(运行期不依赖注解,只在编译期用)
- `✚ core/.../modding/LuaSandbox.java` — 读 `lua-interface-map.json`(`getResourceAsStream`),提供 `canAccessClass/Method/Field` + `exposedGlobals()`(返回给 LuaEngine 的、只含白名单对象的 `Globals`)
- `✎ core/.../modding/LuaEngine.java` — 改造 globals 构造(D1 方案 A):用 `LuaSandbox.exposedGlobals()` 或在 `JsePlatform.standardGlobals()` 基础上移除危险 lib;eval 路径保持
- `✎ core/.../items/Generator.java` — 新增 `Category.LUA_ITEM`(D2 方案 A)+ `random()` 加 Lua 物品分支。**改动收敛到最小**(新增 enum 项 + 一个分支,不动原版物品逻辑)
- `✚ core/.../modding/LuaItemPool.java`(若 D2 方案 A)— 从 `LuaItemRegistry` 随机选 id 的概率池
- `✎ core/.../modding/LuaItem.java` — 在希望暴露给 Lua 的方法上标 `@LuaInterface`(M1 可能只标几个 accessor,如 `name()`/`tier`)
- `✚ core/src/test/java/.../modding/LuaSandboxTest.java` — 验证:Lua 试图访问 `io.read`/`os.execute`/反射等未授权 API → 不可达/报错
- `✚ core/src/test/java/.../modding/GeneratorLuaItemTest.java` — 验证:Generator 能生成 Lua 物品
- `✚ core/src/main/assets/scripts/items/` — 多个测试 Lua 物品(如 `test_axe.lua`/`test_dagger.lua`,验证池,不只 test_sword)
- `✎ android/proguard-rules.pro` — keep 注解处理产物 + luaj + modding 子包(若验证 release,C5)

## Steps

每步独立可验证。

1. **建 annotation 模块** — `@LuaInterface` 注解 + `build.gradle`(java-library)+ `settings.gradle` include。编译验证模块独立。
2. **建 processor 模块** — `LuaInterfaceProcessor` + `auto-service` 依赖 + 照搬 Remixed 的可达闭包 BFS 逻辑;`core` 接 `annotationProcessor`。编译 `:core`,确认 `lua-interface-map.json` 产出(在 build/classes 或 resources 里)。
3. **写 LuaSandbox** — 读 map.json + `canAccess*` API + `exposedGlobals()`。先在 `LuaItem` 上标 `@LuaInterface` 几个方法,验证 processor 收录。
4. **改造 LuaEngine(D1 方案 A)** — globals 用白名单暴露 + 移除危险 lib;更新 `LuaEngineTest` 验证 M0 的 `register_item` 链路仍通 + 新增"Lua 访问 `io.read` 失败"断言。
5. **Generator 双源(D2 方案 A)** — 新增 `Category.LUA_ITEM` + `LuaItemPool` + `random()` 分支。**默认概率 0 或极低**(C3)。
6. **沙箱测试** — `LuaSandboxTest`:Lua 静态访问 `io`/`os`/`loadfile` 等不可达(返回 nil 或报错)。
7. **Generator 测试** — `GeneratorLuaItemTest`:配置 LUA_ITEM 概率 > 0 后,`Generator.random(Category.LUA_ITEM)` 能返回 `LuaItem` 实例。
8. **desktop debug 验证** — 临时调高 LUA_ITEM 概率,启动游戏,打怪确认 Lua 物品能掉落;恢复默认低概率。
9. **回归(C3)** — 原版掉落平衡:默认 LUA_ITEM 概率为 0 或极低,正常开局掉落池不变。Grep 确认未误改原版 Category 概率。
10. **(加分)release proguard 验证**(C5)— 若时间允许。

## Acceptance

- [ ] `:annotation` + `:processor` 模块编译,`lua-interface-map.json` 产出
- [ ] `LuaSandbox` 读 map,`canAccess*` + `exposedGlobals()` 工作
- [ ] **LuaEngine 白名单暴露**:Lua 访问 `io`/`os`/`loadfile`/`load`/`dofile` 等危险 API 不可达(`LuaSandboxTest` 验证)
- [ ] M0 的 `register_item` 链路不破(`LuaEngineTest` 仍通过)
- [ ] `Generator` 能掉落 Lua 物品(`GeneratorLuaItemTest` 验证;desktop debug 实测)
- [ ] 原版玩法平衡未变(C3:LUA_ITEM 默认概率 0 或极低,原版 Category 概率不动)
- [ ] fork 代码在 `modding/` 子包(C2);上游改动收敛到 `Generator.java` + `settings.gradle` + `core/build.gradle`(C4)
- [ ] `:core:compileJava` / `:desktop:debug` / `:android:assembleDebug` 通过
- [ ] codex 评审通过(PLAN + 实施)

## 风险

- **D1 白名单暴露 vs M0 兼容**:`JsePlatform.standardGlobals()` 包含的 lib 多,精确移除危险部分要仔细。M0 的 `test_sword.lua` 只用 `register_item`,理论上不破,但 worker 必须验证。若 M0 测试破 → `[BLOCKED]`。
- **Generator 改动**(C4):`Generator.java` 是上游核心文件(37K),改动 merge 冲突风险。改动收敛到"新增 Category 项 + 一个分支",不动原版逻辑。
- **APT 与 Android 构建兼容**:确认用 `annotationProcessor`(javac)而非 `kapt`;AGP 下 APT 配置正确。
- **proguard**(C5):release 可能裁注解/反射,需 keep。M1 以 desktop debug 为主验收,release 加分。

## 参考

- `docs/MODDING-ROADMAP.md` §4 M1
- `docs/PLAN-modding-m0-lua-poc.md`(M0 上下文,已合入 master)
- `../remixed-dungeon/annotation/src/main/java/com/nyrds/LuaInterface.java`(注解定义,照搬)
- `../remixed-dungeon/processor/src/main/java/com/nyrds/LuaInterfaceProcessor.java`(处理器,照搬)
- `../remixed-dungeon/RemixedDungeon/src/main/java/com/nyrds/lua/LuaSandbox.java`(沙箱,但**运行期空壳**,只参考 canAccess API 设计,不要照搬它的运行期逻辑)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java`(物品工厂,M1 改造点)
- M0 已有:`core/.../modding/{LuaEngine,LuaItem,LuaItemRegistry,LuaDebugService}.java`

## 范围决策记录(写入 ROADMAP 后续更新)

- **砍 mob**:SPD 无 mob 工厂(`Level.createMob()` 每关硬编码),做 mob 双源要新建 `MobFactory` 间接层(改动面大、风险高)。M1 只做物品。**mob 推到 M2**(核心 API 暴露时一并处理)或独立里程碑 M1.5。
- **砍外部 mod 目录扫描**:留给 M5(mod 治理)。M1 只支持内置 `assets/scripts/`。
- **D3 多类 Lua 物品**:M1 仍只做武器(MeleeWeapon),多类型留给 M2。

---

## 实施细化(worker 调研后补充,2026-07-05)

> 以下是 worker 通读 M0 代码 / Remixed 参考 / SPD Generator 后确定的具体落地细节。
> dispatcher 的 Goal/Context/关键决策 不变,这里把它们落到可执行粒度。

### N1 包名与模块结构(偏离 Remixed 的 `com.nyrds`)

- 注解和处理器**逻辑照搬 Remixed**,但包名改用 fork 命名空间 `com.shatteredpixel.shatteredpixeldungeon.modding.*`,避免在 SPD 源码里出现 `import com.nyrds.*;`。
  - `annotation/` 模块:`com.shatteredpixel.shatteredpixeldungeon.modding.annotations.LuaInterface`(marker,`@Target={METHOD,TYPE,FIELD,CONSTRUCTOR}`,与 Remixed 一致)。
  - `processor/` 模块:`com.shatteredpixel.shatteredpixeldungeon.modding.annotations.processor.LuaInterfaceProcessor`(`@AutoService(Processor)`,可达闭包 BFS 逻辑照搬,JSON 写出逻辑照搬)。
- 处理器依赖精简:保留 `auto-service:1.1.1` + `guava:32.0.1-jre`(用于 `ImmutableSet.of` in `getSupportedAnnotationTypes`);**砍 javapolet / lombok**(那是 Remixed 的另一个处理器用的,LuaInterfaceProcessor 不需要)。
- Java 版本:`appJavaCompatibility`(Java 11),与项目一致(Remixed 用 1.8,这里升到 11)。

### N2 D1 落地(`LuaSandbox.exposedGlobals()` + 危险 lib 移除)

- M1 的"白名单暴露"分两层,二者都做:
  1. **globals 收敛(运行期真正的边界)**:`LuaSandbox.exposedGlobals()` 在 `JsePlatform.standardGlobals()` 基础上**移除危险 lib**,只保留安全子集。具体要 `globals.load(...)` 不掉的项:
     - lib 级:`io`、`os`、`package`(luaj 的 `PackageLib` 暴露 `require/loaded/loadlib`)
     - 全局函数:`load`、`loadfile`、`dofile`、`loadstring`、`require`(直接从 globals 置 NIL;`package=nil` 不等于移除 require,需显式置 require=NIL)
     - 反射/调试:`debug`、`getfenv`、`setfenv`
     - **Java bridge(关键,否则沙箱形同虚设)**:`luajava` —— `JsePlatform.standardGlobals()` 装了 `LuajavaLib`,不清掉 Lua 可 `luajava.bindClass(...)` / `luajava.newInstance(...)` 直接拿任意 Java 类,绕过白名单。**必须置 `luajava=NIL`**。
     - 保留:`math`、`string`、`table`、`coroutine`、basic 里的 `print/tostring/tonumber/pairs/ipairs/type/select/error/assert/setmetatable/getmetatable/rawget/rawset/rawequal/rawlen/unpack/next/xpcall/pcall`。
  2. **`@LuaInterface` map(processor 产出,供 future 精细暴露)**:M1 用它做 `canAccess*` 查询 API + 测试断言 processor 收录了 `LuaItem` 上标注的方法。M1 不在 luaj 调用路径实装 per-call 拦截(那需要 hook CoerceJava,Remixed 没做成,M1 也不做)。
- 移除 lib 的实现:`LuaSandbox.exposedGlobals()` 用 `globals.set("io", NIL)` 等(显式置 NIL 比 `rawset` 移除更明确,且能让 `globals.load` 拿到 `io.read` 时报 "attempt to index a nil value")。验证:测试里 `globals.load("return io.read")` 执行后 `io` 为 nil → 不可达。
- **M0 兼容**:`register_item` 是自定义 global(不依赖 io/os),改造后 `LuaEngineTest` 必须仍通过。`init.lua` 用 `dofile` —— ⚠️ **这是改造的关键风险点**:dofile 在移除名单里!解决方案:`init.lua` 改用 LuaEngine 在 Java 侧显式驱动加载各 item 脚本(Java 侧遍历 `assets/scripts/items/*.lua` 并 `globals.load`),而不是 Lua 侧 `dofile`。这样 Lua 代码永远拿不到 `dofile`,而 Java 侧的脚本加载不受沙箱影响。
- `LuaEngine.init()` 改造:加载 `init.lua`(纯配置/无 dofile)后,Java 侧扫描 `assets/scripts/items/*.lua` 逐个 `load+call`(用现有 `findResource`)。

### N3 D2 落地(Generator 双源,改动收敛)

- **改动面(上游 Generator.java)**:精确 4 处,全部 additive:
  1. import `...modding.LuaItem` + `...modding.LuaItemPool`(2 行)。
  2. Category enum 末尾(GOLD 之后)新增 `LUA_ITEM( 0, 0, LuaItem.class );`(GOLD 末尾 `;` 改 `,`)。
  3. `random(Category cat)` switch 新增 `case LUA_ITEM: return randomLuaItem();`。
  4. 新增 private 方法 `randomLuaItem()`(见下)。
- **不动的**:所有原版 Category 的 firstProb/secondProb/classes/probs/defaultProbs、deck 系统、`generalReset`/`fullReset`/`storeInBundle`/`restoreFromBundle` 一字不改(C3/C4)。
- `randomLuaItem()`:
  ```java
  private static Item randomLuaItem() {
      LuaItem item = LuaItemPool.random();
      if (item == null) return random(Category.WEAPON);   // registry 空 → 回退原版武器
      return item.random();                                 // 走 Weapon.random():+0/+1/+2
  }
  ```
- **默认概率**:enum 写死 `0, 0` → `generalReset()` 把 LUA_ITEM 的 categoryProbs 置 0 → `Random.chances` 永不选中 → **原版 `Generator.random()` 行为 0 变化**(C3)。
- **测试与 desktop 验收的概率注入方式**:
  - 单元测试(Step 7):直接 `Generator.random(Category.LUA_ITEM)`(switch case 不看 categoryProbs,直接进 `randomLuaItem`)。无需 bump 概率。
  - desktop debug 验收(Step 8):临时把 enum `0, 0` 改成 `5, 5`(高于 WEAPON 的 2/2),`./gradlew :desktop:debug`,打怪确认掉 Lua 物品,验收后**改回 `0, 0`** 再 commit。
- **`order(Item)` 影响(可接受)**:LuaItem is-a MeleeWeapon,会同时匹配 WEAPON/WEP_T1-5/LUA_ITEM,`order()` 取最后匹配 → LuaItem 排到 GOLD 之后(库存最末)。**理由不是"概率 0 仅 debug 可见"**(LUA_ITEM 可被 `Generator.random(LUA_ITEM)`、debug 按钮、未来开关放进背包),而是:**库存排序变化只影响 LuaItem 自身的绝对位置,不改变任何原版物品之间的相对顺序**,因此对原版玩法平衡 0 影响(C3)。

### N4 LuaItemPool(modding/ 新增)

- `core/.../modding/LuaItemPool.java`:`public static LuaItem random()` 从 `LuaItemRegistry` 已注册 id 均匀随机选一个,`LuaItemRegistry.create(id)`;registry 空返回 null。无概率配置字段(概率由 Generator enum 控制,单一事实源)。
- 需要给 `LuaItemRegistry` 加一个 `public static Set<String> ids()` / 或 `public static List<String> idList()`(M0 没有,只暴露了 `contains`/`getTable`/`create`)。

### N5 `@LuaInterface` 标注目标(M1 最小集)

- 在 `LuaItem` 上标注:`name()`、`desc()`、`image` 字段已是 public?——`image` 是 `Item.image`(public int),`tier` 是 MeleeWeapon 的 protected/package?调研后定。M1 只标**明确 public 且无副作用**的 accessor:`name()`、`desc()` 两个方法。这足以验证 processor 收录 + 测试 `canAccessMethod("...LuaItem", "name")==true`、`canAccessMethod("...LuaItem", "storeInBundle")==false`。
- 不标 `storeInBundle`/`restoreFromBundle`/`random` 等(Lua 不该调这些)。

### N6 测试新增/更新清单

- 更新 `LuaEngineTest`(M0 已有):保留 `initRegistersTestSwordFromAssets`;新增 `dofileRemoved` 断言:`globals.load("return io")` 执行后 `io` 为 nil(N2 验证)。或拆到 `LuaSandboxTest`。
- 新增 `LuaSandboxTest`:
  - `exposedGlobalsHasNoIo`:assert `LuaSandbox.exposedGlobals().get("io")` is nil。
  - 同上断言 `os`/`load`/`loadfile`/`loadstring`/`dofile`/`require`/`debug`/`getfenv`/`setfenv`/`package`/`luajava` 全 nil。
  - **执行型断言(贴近攻击面,must-fix 补)**:不只断言 key 为 nil,还要 `globals.load("return luajava")` 执行后为 nil;`globals.load("return require")` 为 nil;`globals.load("return loadstring")` 为 nil;且 `globals.load("return luajava.bindClass('java.lang.Runtime')")` 执行**抛错**(pcall 返回 false),证明 Java bridge 不可达。
  - `registerItemStillWorks`:`exposedGlobals().set("register_item", ...)` 后注册链路通(等价 M0 行为)。
  - `mapLoadedFromProcessor`:`LuaSandbox.canAccessMethod("com.shatteredpixel.shatteredpixeldungeon.modding.LuaItem", "name")` == true(证明 processor 产出了 map 且 LuaSandbox 读到了)。前置:`@LuaInterface` 已标在 `LuaItem.name()`。
- 新增 `GeneratorLuaItemTest`:
  - `randomLuaItemCategoryReturnsLuaItem`:先 `LuaEngine.init()`(headless)让 registry 非空,再 `Generator.random(Category.LUA_ITEM)` → `assertInstanceOf(LuaItem.class, ...)` + 校验 name/tier 来自 Lua。
  - `emptyRegistryFallsBackToWeapon`:registry 空(不 init engine)→ `random(Category.LUA_ITEM)` 返回 `MeleeWeapon` 实例(回退)。
- 新增 assets:`core/src/main/assets/scripts/items/test_axe.lua` + `test_dagger.lua`(验证池有 ≥2 个 id 可随机,不只 test_sword)。

### N7 构建验证命令(按顺序)

```bash
# 1. 新模块独立编译
./gradlew :annotation:compileJava :processor:compileJava
# 2. core APT 跑通 + 产出 map(检查产出位置)
./gradlew :core:compileJava
ls core/build/classes/java/main/lua-interface-map.json   # 应存在
# 3. 单元测试
./gradlew :core:test --tests '*LuaSandboxTest' --tests '*LuaEngineTest' --tests '*GeneratorLuaItemTest'
# 4. desktop debug(Step 8 验收,临时 bump 概率)
./gradlew :desktop:debug
# 5. android 编译通过(不要求运行时验证)
./gradlew :android:assembleDebug
# 6. release proguard(C5 加分)
./gradlew :android:assembleRelease
```

### N8 C5 proguard(基本自动满足)

- 现有 `android/proguard-rules.pro` 已有 `-keepnames class com.shatteredpixel.** { *; }`,覆盖 `modding/` 子包 + LuaItem。
- 补 1 条 luaj keep(反射密集,保险):`-keep class org.luaj.vm2.** { *; }`。
- 注解处理产物 `lua-interface-map.json` 是 resource(CLASS_OUTPUT 根目录),R8 不裁 resources,无需 keep。

### N9 待 reviewer 确认的判断点

- 包名改 `com.shatteredpixel.shatteredpixeldungeon.modding.*`(vs Remixed 的 `com.nyrds`)—— 已定,如 reviewer 强烈反对可改回。
- `init.lua` 改 Java 侧驱动加载 item 脚本(移除 Lua 侧 `dofile`)—— 这是 N2 的关键设计,保证 dofile 可安全移除。
