# PLAN — modding-m0-lua-poc

> 里程碑:**M0 最小 Lua 闭环(可行性 PoC)**
> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M0
> 本 PLAN 是 feature_worker 的实施依据;codex_reviewer 评审本 PLAN + 实施。

---

## Goal

在 SPD 里嵌入 **luaj**(Lua for Java),跑通"用 Lua 脚本定义一把武器,并在游戏里拿到它"的最小闭环,证明 Lua modding 链路可行——为后续 M1(沙箱)/M2(API 暴露)/M3(机制骨架)铺基础。

## Context

- 这是 `MODDING-ROADMAP.md` 的 **M0**,整个 Lua modding 平台的可行性验证。
- **M0 故意不做沙箱、不做注解处理器**(那是 M1 的事),只为打通"Lua 脚本 → Java 物品 → 游戏内可见"的链路。
- 参考姐妹项目 `../remixed-dungeon/`:
  - `../remixed-dungeon/luaj/` — 它的 luaj 模块(自己打包的 luaj;**M0 不直接搬**,用 Maven 标准版即可,见下)
  - `../remixed-dungeon/scripts/items/HookedDagger.lua` — Lua 物品写法参考(用 `require "scripts/lib/item"` 工厂;M0 不需要这么复杂)
  - `../remixed-dungeon/luaj/` 的 `LuaEngine` / `LuaScript` — 引擎设计参考
- **关键约束**(贯穿,来自 ROADMAP §2):
  - **C2 包隔离**:所有 fork 加的 Java 代码进 `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/` 子包,不散回上游根包/windows 子包
  - **C3 回归基线**:原版一周目玩法不受影响——测试剑**不能进原版掉落池**,只能通过隔离的调试/演示入口拿到
  - **C5 proguard 安全**:luaj 走反射,release 构建的 keep 规则要跟上(`android/proguard-rules.pro`)。M0 以 desktop debug 验收为主,release 验证作为加分项
  - **C1 版本对齐 / C4 merge 友好**:对上游文件的改动收敛到最小(目前只动 `core/build.gradle` 加依赖,可能动 `android/proguard-rules.pro`)

## Files

预计改/新增的文件(`✚` 新增,`✎` 修改,`?` worker 调研后定):

- `✎ core/build.gradle` — 加 luaj 依赖 `implementation 'org.luaj:luaj-jse:3.0.1'`(Maven Central)。**决策:先用 Maven 标准版**,3.0.1 是 2014 老库但纯 Java 实现,无 native 依赖,与 libgdx 无冲突面。若 desktop 验证踩坑再 fallback 到 Remixed 的 luaj fork。理由:(a) Maven 标准版依赖一行搞定,不需建独立 Gradle 模块;(b) ROADMAP §4 M0 原文说"独立 Gradle 模块",但那是理想态,M0 PoC 优先打通链路,模块化留给 M1;(c) 已确认 `org.luaj:luaj-jse:3.0.1` 在 Maven Central 存在。
- `✚ core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaEngine.java` — 最小引擎(单例)。职责:
  - `init()`:构造 `Globals`(用 `JsePlatform.standardGlobals()` 一步到位,内含 BaseLib/PackageLib/MathLib/StringLib/TableLib 等;M0 不需要 Remixed 那套手拼 lib),设置 `globals.finder` 让 `require`/`dofile` 能从 libgdx assets 读 `.lua`
  - `eval(String luaSource, String chunkName)`:执行一段 Lua 字符串
  - 暴露 Java 工厂 global `register_item(LuaTable)`:Lua 侧调 `register_item({...})` 时,Java 校验必需字段(`id` 用 `tbl.get("id").checkjstring()`、`tier`/`image` 用 `checkint()`,`name` 用 `checkjstring`,`desc` 用 `optjstring("")`)→ 存进 `LuaItemRegistry`。字段缺失时 `Gdx.app.error` 记录 + 不注册(codex review suggestion,防脏数据进 registry)
  - 提供 `runInitScript()`:读 `scripts/init.lua` 并执行
  - 异常用 `Gdx.app.error("LuaEngine", ...)` 记录(M0 不抛到游戏崩溃,失败只 log + 不注册物品)
- `✚ core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaItemRegistry.java` — 极简注册表。`register(id, LuaTable)` + `get(id)` + `create(id)`(返回 `new LuaItem(table)`)。M0 就一个物品,用 `HashMap<String, LuaTable>` 即可。
- `✚ core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaItem.java` — `extends MeleeWeapon`(已确认 `MeleeWeapon` 非 abstract,实现了 `min(int lvl)`/`max(int lvl)`/`STRReq(int lvl)`,基于 `tier` 算)。从 Lua 表读字段:
  - `name`、`desc` → 存为 String 字段,override `name()`/`desc()` 返回(走旁路,因为 `trueName()` 是 final 但 `name()` 不是;`desc()` 默认走 `Messages.get` 会被 i18n 体系找不到)
  - `tier`(`int`,直接赋 public 字段)、`image`(`int`,复用 `ItemSpriteSheet` 现有索引)
  - 构造:`LuaItem(LuaTable tbl)` 读上述字段 + 记 `luaItemId = tbl.get("id").tojstring()`
  - **不 override** `min/max/STRReq/damageRoll/proc` —— MeleeWeapon 已给
  - **序列化(必须,codex review 发现)**:`Item.storeInBundle/restoreFromBundle` 只存 quantity/level/cursed 等,**不存 image/tier/name**;且 Bundle 反序列化用 `Reflection.newInstance(getClass())`(无参构造)。若不处理,测试剑进背包 → 存档 → 读档后 LuaItem 变空壳(image=0/tier=0/name=null),制造不可恢复状态。处理:
    - 加无参构造 `LuaItem()`(供 Reflection;字段留默认空)
    - override `storeInBundle`:super + `bundle.put("lua_item_id", luaItemId)`
    - override `restoreFromBundle`:super + `luaItemId = bundle.getString("lua_item_id")` → 从 `LuaItemRegistry.getTable(luaItemId)` 重新填 name/desc/tier/image(此时 `LuaEngine.init()` 已在 `ShatteredPixelDungeon.create()` 跑过,registry 就绪;Belongings 恢复在 create 之后)
- `✚ core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaDebugService.java` — hook 入口服务(照 `saveslot/SaveSlotService` 的 subpackage 注入范式)。暴露:
  - `static void addMenuButton(WndGame wnd)`:首行 `if (!DeviceCompat.isDebug()) return;` 守卫(release 不出现);追加一个 RedButton"给测试剑(Lua)",点击 → `giveTestItem(Dungeon.hero)`
  - `static void giveTestItem(Hero hero)`:`LuaItemRegistry.create("test_sword")` → `item.collect()`(`Item.collect()` 一行入背包)。**防御(codex review suggestion)**:`hero == null` / `create` 返回 null / `collect()` 返回 false 时,用 `Gdx.app.error` 或 `GLog.w` 记录而不是 NPE 把游戏打崩(debug 入口不能搞坏存档)
- `✚ core/src/main/assets/scripts/init.lua` — 启动入口。**统一用 assets 根相对路径**:`dofile("scripts/items/test_sword.lua")`(让 test_sword.lua 跑 `register_item(...)`)。**不用 `require`(避免 ResourceFinder 模块名映射的复杂性,M0 直接 dofile)。**
- `✚ core/src/main/assets/scripts/items/test_sword.lua` — 测试剑定义。调 `register_item{ id="test_sword", name="测试剑(Lua)", desc="一把由 Lua 脚本定义的剑(M0 PoC)。", image=<ItemSpriteSheet.SHORTSWORD 数值=104>, tier=2 }`。伤害由 tier=2 推出(min=2, max=15,与 Shortsword 同档)。
- `✎ core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ShatteredPixelDungeon.java` — `create()` 末尾(line 78 `SaveSlotService.cleanupLeftovers()` 之后)加一行 `LuaEngine.init();`(fork 注释块包裹)。**已确认此处 Gdx.files 已就绪。**
- `✎ core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndGame.java` — line 117-118 fork 块内,紧跟 `SaveSlotService.addMenuButtons(this);` 后加一行 `LuaDebugService.addMenuButton(this);`。`addButton` 已是 public(line 123),无需改可见性。
- `? android/proguard-rules.pro` — 加分项,验证 release 时再补 keep 规则(C5)。M0 以 desktop debug 验收为主。

## Steps

每步可独立验证,完成后进入下一步。

1. **接 luaj 依赖** — `core/build.gradle` 的 `dependencies` 块加 `implementation 'org.luaj:luaj-jse:3.0.1'`;跑 `./gradlew :core:compileJava` 确认依赖解析通过、无版本冲突。**判定标准**:编译成功,Grep 不到 luaj 版本冲突警告。
2. **写 LuaEngine(含 Java→Lua 桥)** — 实现:
   - `LuaEngine.init()`:`globals = JsePlatform.standardGlobals();` → 设置 `globals.finder`(实现 `ResourceFinder`,用 `Gdx.files.internal(fileName).read()` 返回 InputStream,让 `require`/`dofile` 能找 assets)→ 注册 Java 工厂 global `register_item`(用一个匿名 `OneArgFunction` 子类,`call(LuaValue arg)` 里把 `arg.checktable()` 交给 `LuaItemRegistry.register(id, table)`,id 从 `table.get("id").tojstring()` 取)
   - 异常 catch 用 `Gdx.app.error("LuaEngine", "init failed", e)`,不抛
   - 验证:写一个临时 JUnit 测试 `core/src/test/java/.../modding/LuaEngineTest.java`(headless,assets 已在 test resources 路径里),`init()` 后 eval 字符串 `register_item{ id="t", name="T", tier=1, image=0 }`,断言 `LuaItemRegistry.get("t") != null` 且 `LuaItemRegistry.create("t")` 返回的 `LuaItem` 的 `name()` 等于 "T"、`tier==1`。**这条验证 Lua→Java 调用链 + LuaItem 构造都通,且不依赖游戏 UI。**
3. **写 LuaItem + test_sword.lua** — `LuaItem(LuaTable tbl)` 构造里:`this.tier = tbl.get("tier").checkint(); this.image = tbl.get("image").checkint(); this.nameStr = tbl.get("name").tojstring(); this.descStr = tbl.get("desc").optjstring("");` + override `name()` return nameStr、`desc()` return descStr。`test_sword.lua`:`register_item{ id="test_sword", name="测试剑(Lua)", desc="...", image=<SHORTSWORD 值>, tier=2 }`。
4. **物品注册** — `LuaItemRegistry` 是 `static HashMap<String, LuaTable>` + `register`/`get`/`getTable`/`create`。`init.lua` 用 `dofile("scripts/items/test_sword.lua")`(assets 根相对路径,与 ResourceFinder 的 `Gdx.files.internal` 一致),脚本里直接调 `register_item`。
5. **加隔离的 hook 入口**(选项 B+):**用 `LuaDebugService.addMenuButton(WndGame)` 注入,`DeviceCompat.isDebug()` 守卫**(调研结论:SPD 无现成 debug 菜单,`DeviceCompat.isDebug()` 是版本号含 "INDEV" 判定,debug 构建为 true、release 为 false,与 `journal/Document.java:260` 同风格)。点击按钮调 `giveTestItem(hero)`:`LuaItemRegistry.create("test_sword").collect()`(一行入背包)。**C3 保证:不注册进 `Generator.Category`,原版任何掉落路径无法 spawn 它,唯一创建路径是这个 debug 按钮。**
   - 选项 C(headless 单测)在 step 2 已做,作为保底验收。
   - **决策记录:选选项 B(而非 A),因为 SPD 无现成 debug 菜单可复用;选项 C 单测作为辅助而非主验收(PLAN 要求"游戏内拿到")。**
6. **desktop debug 验证** — `./gradlew :desktop:debug` 构建,启动游戏,开一局新游戏 → 打开菜单(WndGame)→ 点"给测试剑(Lua)"按钮 → 确认背包出现"测试剑(Lua)",有名字/贴图(Shortsword sprite)/攻击力数值(tier=2 → 2-10),能装备/攻击。
7. **回归验证(C3)** — 正常开一局新游戏,**不点 debug 按钮**,打几层,确认掉落/平衡未变(测试剑不在掉落池里——因为它没进 Generator)。Grep 确认 `Generator.java` 未被改动。
8. **(加分)release proguard 验证** — `./gradlew :android:assembleRelease` 看 luaj 反射是否被裁;若裁了,`android/proguard-rules.pro` 加 `-keep class org.luaj.** { *; }` + `-keep class com.shatteredpixel.shatteredpixeldungeon.modding.** { *; }`。M0 不阻塞在此。

## Acceptance

- [ ] `./gradlew :core:compileJava` 通过(luaj 依赖解析 OK)
- [ ] `./gradlew :desktop:debug` 构建成功并启动
- [ ] 游戏内能拿到 Lua 定义的"测试剑",有名字 / 贴图 / 攻击力(三项都从 Lua 来)
- [ ] 原版一周目不受影响:测试剑不进原版掉落池,正常开局平衡未变(C3)
- [ ] 所有 fork 加的 Java 代码在 `core/.../modding/` 子包内(C2)
- [ ] 对上游文件的改动收敛到 3 个单点(每个一行 hook + import):`core/build.gradle`(luaj 依赖)、`ShatteredPixelDungeon.java`(create() 末尾 `LuaEngine.init()`)、`WndGame.java`(fork 块 `LuaDebugService.addMenuButton(this)`)+ 可选 `android/proguard-rules.pro`(C1/C4)
- [ ] `LuaItem` 存档可恢复:拿到测试剑 → 存档 → 读档不崩、字段(name/tier/image)完整(codex review must-fix)
- [ ] (可选)`./gradlew :android:assembleRelease` 通过 + proguard keep 到位(C5)

## 实施记录(worker 维护)

**hook 入口选型**:选项 B(`WndGame` 加按钮 + `DeviceCompat.isDebug()` 守卫)。SPD 无现成 debug 菜单,`DeviceCompat.isDebug()` = `Game.version.contains("INDEV")`,debug 构建为 true、release 为 false。选项 C(headless 单测)作为辅助验收已实现(`LuaEngineTest`)。

**image 取值**:`ItemSpriteSheet.SHORTSWORD` = `xy(9,7)` = `(9-1) + WIDTH*(7-1)`,WIDTH = TX_WIDTH/SIZE = 256/16 = 16,即 `8 + 16*6 = 104`。Lua 脚本里硬编码 `image = 104`。

**MeleeWeapon tier=2 实际数值**(tier=2):`min(0)=2`,`max(0)=5*(2+1)=15`(公式 `5*(tier+1)+lvl*(tier+1)`)。与 Shortsword 同档。

**验证状态**:
- ✅ `./gradlew :core:compileJava` 通过(luaj 依赖解析 OK)
- ✅ `LuaEngineTest`(headless)通过:`LuaEngine.init()` 成功 → 加载 `scripts/init.lua` → dofile `scripts/items/test_sword.lua` → `register_item` 注册 `test_sword` → `LuaItem` 的 name/tier/image/min/max 全部从 Lua 正确读入。Lua→Java→Item 链路打通。
- ✅ `./gradlew :desktop:debug` BUILD SUCCESSFUL(core + desktop 编译打包通过)。
- ⚠️ **GUI 内交互验证(点 debug 按钮拿剑、装备、攻击、存档读档)受当前无头环境限制**(无 DISPLAY、无 xvfb、GLFW 无法初始化)。代码层面:`LuaEngine.init()` 异常被 catch 不会崩 `create()`;`LuaDebugService.giveTestItem()` 对 hero null / item null / collect false 做了 `Gdx.app.error` 防御;测试剑未注册进 `Generator.Category`,原版掉落池不受影响。GUI 验证留给有显示环境(或 Android 设备 `adb -s 20210119085654`)做最终确认。

**序列化方案**(codex review must-fix #1):`LuaItem` 加 public 无参构造(供 `Reflection.newInstance`)+ `luaItemId` 字段;`storeInBundle` 存 id;`restoreFromBundle` 用 id 从 `LuaItemRegistry.getTable(id)` 重新 hydrate name/desc/tier/image。`LuaEngine.init()` 在 `ShatteredPixelDungeon.create()` 已跑,Belongings 恢复在其后,registry 就绪。

**codex 评审结果**:
- **阶段 1(PLAN 评审)**:实质 2 轮。第 1 轮提 3 个 must-fix(LuaItem 序列化、init.lua 路径一致性、Acceptance C4 冲突)+ 1 suggestion(防御 Lua 初始化失败),全部按建议修订;第 2 轮 APPROVED。
- **阶段 2(实施 review)**:实质 1 轮 APPROVED。codex 深入核对了 `LuaItem` 序列化(读 MeleeWeapon/Weapon 的 bundle 行为)确认 hydrate 方案正确,无 must-fix。
- infra 注记:阶段 1 首个 terminal `a0f1b43b` 因 codex 进程崩溃未产出,替换为 `bbfb64bb` 完成阶段 1;阶段 2 `bbfb64bb` 的 codex 在 PLAN 复评后 IDLE,不再消费 inbox 的实施 review 消息(CAO codex provider 已知行为),替换为 `ce514e61` 完成。

**Android 设备验证**(补充 desktop GUI 限制):`:android:assembleDebug` APK(33.3M)安装到 `adb -s 20210119085654`(Honor TYH201H)。游戏启动成功(Activity START_SUCCESS,进程 pid 3908),**无 FATAL EXCEPTION、无 LuaEngine error log** —— 客观证明 `LuaEngine.init()` 在真实设备 `create()` 流程中跑通、未崩溃。GUI 端到端点按钮拿剑的交互验证受 worker 视觉限制未完成,但代码链路(单测 + 设备启动)已验证。

## 风险

- **luaj 版本兼容**:`org.luaj:luaj-jse:3.0.1` 是 2014 老库,与 Java 11 / libgdx 1.14.0 可能有兼容摩擦。若踩坑,fallback 到 Remixed 的 luaj fork(`../remixed-dungeon/luaj/`)。
- **assets 读取**:libgdx 下 `Gdx.files.internal("scripts/init.lua")` 在 desktop 后端能否正确读到 `core/src/main/assets/`,需验证(desktop 通常会从 classpath 或打包后的 assets 读)。
- **proguard 裁反射**:release 构建大概率裁掉 luaj 的反射调用。M0 以 desktop debug 为主验收,release 作为加分项,不阻塞 M0 完成。

## 参考

- `CLAUDE.md`(本仓项目约定、build/verify 命令、proguard 注意)
- `docs/MODDING-ROADMAP.md` §4 M0(本 milestone 定义)
- `../remixed-dungeon/luaj/`(参考实现,M0 不直接搬)
- `../remixed-dungeon/scripts/items/HookedDagger.lua`(Lua 物品写法)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/weapon/Weapon.java`(武器基类)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Item.java`(Item 基类,18K)
