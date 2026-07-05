# PLAN: M5b — mod 开关 UI + LuaEngine entry 加载

## Goal

建 **mod 开关 UI**(`WndModManager`:列 mod + on/off CheckBox,`WndGame` 加 "Mods" 入口仿 `SaveSlotService.addMenuButtons`)+ **LuaEngine entry-script 加载机制**(每个 enabled mod 的 `mod.json` 可声明 `entry` 指向一个 init.lua,该脚本调 `register_*` 注册内容;disabled mod 跳过)。让玩家能开关 mod 且对 entry-mod 生效。

**交付可工作开关**:test_mod 加 `entry: "init.lua"`,该 init.lua 注册一个新测试物品 `test_mod_item`。开关 test_mod → 重启 → `test_mod_item` 是否注册。**不动现有扁平 `scripts/` 脚本**(M5c 收拢),零破坏现有 208 测试。

## Context

M5a 建了 mod 清单机制(`ModManifest`/`ModScanner`/`ModRegistry`,版本门 + prefs 持久化 enabled 状态),但:
- ModRegistry **未接入任何启动点**(M5a 故意留 M5b)
- **无 UI** 让玩家开关 mod
- **LuaEngine 无 mod 概念**——`loadItemScripts/loadNpcScripts/...` 扁平扫 `scripts/<type>/*.lua` 全加载,无 mod 归属过滤
- 玩家无法关扩展包(C3 回归基线守不住)

M5b 补这三件:① UI ② 启动接入 ③ entry-script 加载(让开关对 entry-mod 真实生效)。M5c 把所有 test_*.lua 收进 test_mod entry + 标 default_enabled=false + 删扁平全加载 + 平衡回归。

**entry-script 机制(Remixed 风格)**:每个 mod 的 `mod.json` 可声明 `entry: "init.lua"`(相对 mod 目录)。LuaEngine 对 enabled mod 加载 `mods/<id>/<entry>`,该 init.lua 调 `register_item/register_npc/...` 注册内容。disabled mod 跳过。这是 Remixed 的 `mod.json` + 入口脚本范式。

**调研要点**(worker Phase 1 先做):
- `modding/LuaEngine.java:init`(L70-135):register_* globals.set 序列 + loadXxxScripts 序列 + loadScriptsFrom 模式(决定 entry 加载插入点)
- `modding/LuaEngine.loadScriptsFrom`(枚举目录 + 编译每个 .lua 的核心 helper):entry 加载复用编译逻辑
- `saveslot/SaveSlotService.addMenuButtons`(L360):fork 注入 WndGame 的范式(M5b 仿此建 `ModdingService.addMenuButtons`)
- `saveslot/WndSaveSlotSelect.txt()`:硬编码 ZH/EN i18n 模式(Messages.get 跨包失效,M5b 沿用)
- `ui/CheckBox`(extends RedButton):mod 开关 UI 控件
- `windows/WndGame`(L103 menu 按钮 + L118 `SaveSlotService.addMenuButtons(this)` + L128 `public void addButton`):Mods 入口注入点
- M5a `ModRegistry.all()/isEnabled/setEnabled`:UI 数据源
- 现有 headless 测试模式(`LuaEngineTest/LuaItemTest`):如何初始化 LuaEngine + 验证注册

## Files

- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/WndModManager.java`(新)— mod 列表窗口(CheckBox 开关 + setEnabled + "重启生效"提示)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModdingService.java`(新)— `addMenuButtons(WndGame)` 仿 SaveSlotService,加 "Mods" 按钮 → 打开 WndModManager
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModManifest.java`(改)— 加 `entry` 可选字段(相对 mod 目录的 .lua 路径,可空)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaEngine.java`(改)— init 末尾调 `loadModEntryScripts()`(内部用 `ModRegistry.all()`,**不**显式 scan;对 enabled mod 加载其 entry)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndGame.java`(上游 +1 hook)— `SaveSlotService.addMenuButtons(this);` 旁加 `ModdingService.addMenuButtons(this);`(fork 注释)
- `core/src/main/assets/mods/test_mod/mod.json`(改)— 加 `"entry": "init.lua"`
- `core/src/main/assets/mods/test_mod/init.lua`(新)— 示范 entry,内联 `register_item({...})` 注册 `test_mod_item`
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaModEntryTest.java`(新)— entry 加载 + 开关生效测试
- **上游改动**:`WndGame.java` +1 hook(仿 save slot 范式,fork 注释)

## WndModManager 设计(定稿)

```
class WndModManager extends Window:
  构造:
    title "Mods / 模组"
    scrollArea = new ScrollArea(...)
    container = vertical layout
    for mod in ModRegistry.all():              # 按 M5a scan 结果(name/version/default_enabled)
      row = mod row(container)
        CheckBox(mod.name + " v" + mod.version, ModRegistry.isEnabled(mod.id)):
          onChange(checked) → ModRegistry.setEnabled(mod.id, checked); update hint
      description line(mod.description, gray small)
    hint = "更改在重启游戏后生效 / Changes apply after restart"(gray small)
    if ModRegistry.all().isEmpty(): show "未发现 mod / No mods found"
  txt(key) → 硬编码 ZH/EN map(沿 WndSaveSlotSelect.txt 模式)
```

**关键决策**:
- **CheckBox 控件**:`ui.CheckBox extends RedButton`,有 checked 状态 + 文字。沿 SPD UI 模式。
- **i18n**:硬编码 ZH/EN(沿 `WndSaveSlotSelect.TXT_ZH/TXT_EN` + `txt()`),不依赖 Messages.get(跨包失效)。
- **重启生效**:MVP 不热重载。setEnabled 写 prefs,下次启动 LuaEngine.init 时 entry 加载才读它。UI 提示"重启生效"。worker 评估热重载可行性(codex round-1 可提;MVP 不做)。
- **layout**:沿 SPD Window 模式(固定宽 + ScrollArea + 垂直排列)。参考 `WndSaveSlotSelect` 或 `WndSettings` tab。

## ModdingService.addMenuButtons 设计(定稿)

```
class ModdingService:
  static void addMenuButtons(WndGame wnd):
    RedButton btn = new RedButton(txt("mods")):   # "Mods / 模组"
      onClick → GameScene.show(new WndModManager())   # 或 ShatteredPixelDungeon.scene().add(...)
    wnd.addButton(btn)
  txt(key) → 硬编码 ZH/EN(沿 SaveSlotService.addMenuButtons label 模式)
```

**注入点**:`WndGame` 构造函数 `SaveSlotService.addMenuButtons(this);` 行旁,`+1` 行 `ModdingService.addMenuButtons(this);`(`// FORK(modding-M5b)` 注释)。沿 save slot 的单点 hook 范式(C1/C4)。

## LuaEngine entry 加载设计(定稿)

```
LuaEngine.init():
  ... existing: set register_* globals (L74-102); load INIT_SCRIPT (L107); loadItemScripts/.../loadShopScripts (L116-134) ...

  # M5b: mod entry-script loading (Phase 1 refine: 用 all() 而非显式 scan())
  loadModEntryScripts();                        # 内部用 ModRegistry.all()(lazy scan)

loadModEntryScripts():
  for mod in ModRegistry.all():                 # all() 若 !initialized 会自动 scan();已 initialized(test 预填 scanDir)则不重扫,避免 clobber
    if !ModRegistry.isEnabled(mod.id): continue   # disabled skip
    if mod.entry == null || mod.entry.isEmpty(): continue   # 无 entry 的 mod(纯清单)skip
    String path = "mods/" + mod.id + "/" + mod.entry
    try (InputStream in = findResource(path)):   # 复用 LuaEngine.findResource(Gdx.files.internal + 容错)
      if in == null: Gdx.app.error(...); continue
      globals.load(new InputStreamReader(in, "UTF-8"), "mod:"+mod.id+":"+mod.entry).call()
    catch e:
      Gdx.app.error(TAG, "mod "+mod.id+" entry load fail: "+path, e); continue   # 单 mod 失败不拖垮
```

**关键决策**:
- **加载顺序**:entry 在 `loadXxxScripts` 之后 → register_* globals 已 set(在 init 前半段)→ entry script 能调 `register_item` 等。worker 确认 globals.set 在 loadXxxScripts 之前(L70-102 已是)。
- **entry script 编译**:复用 `globals.load(InputStreamReader, name).call()` 模式(沿 INIT_SCRIPT / loadScriptsFrom)。
- **沙箱**:entry script 用同一 globals(register_* 已是受限 API,N2 dofile 被剥)。entry 不能 dofile(沿现状)。
- **容错**:单 mod entry 失败(文件缺/编译错/运行错)→ log + continue,不拖垮其他 mod 或启动。
- **ModRegistry 数据源(Phase 1 决定)**:`loadModEntryScripts` 遍历 `ModRegistry.all()`。`all()` 在 `!initialized` 时自动 `scan()`(production 首次 init 时触发),已 initialized 则直接返回缓存。**不显式调 `scan()`** —— 显式 scan 会覆盖测试预填的 `scanDir(tempDir)`,破坏 headless 注入。`all()` 的 lazy-scan 同时满足"production 确保清单就绪"和"测试可预填"。
- **versionCode 时序(Phase 1 已验)**:`LuaEngine.init()` 在 `ShatteredPixelDungeon.create()` L83 调用,此时平台 launcher 已设 `Game.versionCode`(M5a ModScanner 同样依赖)。`ModScanner.scan` 版本门安全。

## test_mod entry 示范(定稿)

`assets/mods/test_mod/mod.json`(改):
```json
{
  "id": "test_mod",
  "name": "测试内容包",
  "version": "0.1.0",
  "spd_version": 896,
  "author": "fork",
  "default_enabled": false,
  "entry": "init.lua",
  "description": "M0-M4 测试内容(剑/NPC/商店/城镇)的占位清单"
}
```

`assets/mods/test_mod/init.lua`(新):
```lua
-- test_mod entry: 注册一个示范物品,证明 entry 机制工作
register_item({
  id = "test_mod_item",
  name = "Entry 示范剑",
  ... (沿 test_sword.lua 字段集)
})
```

**关键**:用**新 id** `test_mod_item`(不与扁平 `test_sword` 冲突)→ 现有 `scripts/items/test_sword.lua` 扁平加载不变 → 208 测试零破坏。test_mod_item 只通过 entry 注册,开关 test_mod 控制其是否注册。

## Steps

### 1. 调研(worker 先做,产出笔记)

- 读 `LuaEngine.init`(L70-135 全):confirm register_* globals.set 在 loadXxxScripts 之前 + INIT_SCRIPT 加载模式 → 决定 `loadModEntryScripts` 插入点
- 读 `LuaEngine.loadScriptsFrom`(编译 helper):entry 加载复用编译+异常处理模式
- 读 `saveslot/SaveSlotService.addMenuButtons`(L360)+ `saveslot/WndSaveSlotSelect.txt()`:fork UI 注入 + 硬编码 i18n 范式
- 读 `ui/CheckBox`:checked 状态 API + 文字布局
- 读 `windows/WndGame`(L100-130):addMenuButtons 注入点 + addButton public 签名
- 读 `scripts/items/test_sword.lua`(M0 item 字段集):test_mod_item entry 示范的字段模板
- 读 M5a `ModRegistry.all/isEnabled/setEnabled` + `ModManifest`:UI 数据源 + entry 字段加在哪
- 读 headless 测试如何 set Game.versionCode + init LuaEngine(沿 M5a ModScannerTest 模式)
- **产出**:entry 加载插入点确认 + CheckBox layout 方案 + 测试 setup

### 2. ModManifest 加 entry 字段(+ 路径校验,codex round-1 must-fix #4)

- 加 `String entry`(可空),`fromJson` 用 `optionalString(v, "entry", null)` 解析(可选)
- 默认 null(无 entry 的 mod 是纯清单,跳过 entry 加载)
- **路径校验(codex round-1 #4,安全)**:`entry` 非空时必须满足:
  - 相对路径(无前导 `/`)
  - 无 `..` 段(防 `../other_mod/x.lua` 跨 mod 逃逸 / 读其他 internal 资产)
  - 无 `\`(防 Windows 路径绕过)
  - 以 `.lua` 结尾
  - 校验失败 → throw IllegalArgumentException(ModScanner 捕获 skip 该 mod,沿现有"坏清单跳过"范式)
  - 抽一个 `validateEntryPath(String)` 私有方法,非空时校验

### 3. LuaEngine 加 loadModEntryScripts(codex round-1 must-fix #1:不显式 scan)

- `initInternal()` 末尾(loadShopScripts 之后、`initialized=true` 之前)调 `loadModEntryScripts()` —— **不**显式调 `ModRegistry.scan()`
- `loadModEntryScripts()`:遍历 `ModRegistry.all()`(lazy-scan:production 首次 init 自动 scan;test 预填 scanDir 后不 clobber),enabled 且有 entry 的加载,disabled/无 entry/失败 skip + log
- 复用 `findResource(path)` + `globals.load(InputStreamReader, name).call()` 编译模式 + try-with-resources + 容错

### 4. WndModManager 窗口

- extends Window,ScrollArea + 垂直 CheckBox 列
- 每行:CheckBox(name+version, isEnabled(id)) + onChange → setEnabled + 更新重启提示
- txt() 硬编码 ZH/EN(沿 WndSaveSlotSelect.TXT_ZH/TXT_EN 模式)
- 空列表提示 + 重启生效提示

### 5. ModdingService.addMenuButtons(codex round-1 must-fix #2:isDebug 守卫)

- 仿 SaveSlotService:addMenuButtons(WndGame) 加 "Mods" 按钮 → 打开 WndModManager
- **首行 `if (!DeviceCompat.isDebug()) return;`**(R7,沿 LuaDebugService 模式)—— M5 前不污染 release 菜单
- txt() 硬编码 label(LANG_ZH 三元,沿 SaveSlotService 单按钮范式)

### 6. WndGame 上游 hook(codex round-1 #3:import 不可避免)

- 构造函数 `SaveSlotService.addMenuButtons(this);` 旁加:
  - `+1 import` `import com.shatteredpixel.shatteredpixeldungeon.modding.ModdingService;`(沿现有 `SaveSlotService`/`LuaDebugService` 两处 fork import 范式,无法避免)
  - `+1 call` `ModdingService.addMenuButtons(this);`(`// FORK(modding-M5b)` 注释)
- "唯一上游改动 = WndGame" 指的是**该文件是唯一被改的上游文件**,hook = 1 import + 1 call(沿 SaveSlotService/LuaDebugService 已有先例的 minimal 形态)。不用 fully-qualified 写法(与同区块两处 fork hook 不一致)。

### 7. test_mod 示范

- mod.json 加 `"entry": "init.lua"`
- 新建 mods/test_mod/init.lua:register_item({id="test_mod_item", ...})(字段沿 test_sword.lua)
- **不动 scripts/items/test_sword.lua**(保持扁平加载,208 测试零破坏)

### 8. 测试 `LuaModEntryTest`(headless,沿 ModScannerTest 范式)

- `@BeforeClass`:HeadlessApplication + `Game.versionCode=896`(必须,否则 scan 跳过 test_mod)
- `@Before`:FakePreferences(HashMap backed)+ `ModRegistry.resetForTests()` + `LuaItemRegistry.clear()` + `LuaEngine.resetForTests()`
- `entry_enabled_loadsTestModItem`:`ModRegistry.setEnabled("test_mod", true)` + `LuaEngine.init()` → `LuaItemRegistry.contains("test_mod_item")`(real assets test_mod + init.lua)
- `entry_disabled_skipsTestModItem`:默认 false + init → `!contains("test_mod_item")`
- `entry_modWithoutEntryFieldSkipsGracefully`:scanDir(tempDir 无 entry mod)+ init → 不报错、init 完成
- `entry_missingFileLogsAndContinues`:scanDir(tempDir,entry="ghost.lua" default_enabled=true)+ init → findResource 返回 null → log + continue,init 完成
- `entry_pathValidation_rejectsTraversal`(codex round-1 #4):`ModManifest.fromJson` 对 `entry="../x.lua"`/`"/abs"`/`"a\b.lua"`/`"noext"` → throw IllegalArgumentException(沿 manifest_parsesAllFields 的 assertManifestRejected 范式,可放 ModScannerTest 或新 test)
- 回归:208 既有测试零回归(LuaEngineTest 不设 versionCode → scan 全 skip → 不影响其 test_sword 验证)

### 9. codex 评审 + 回归

- `codex exec --sandbox read-only`(沿 M4d/M5a workaround,smoke 过即采用)
- `./gradlew :core:test` 全过(208 + M5b 新增)
- C3 基线:WndGame hook(isDebug 守卫)不破坏 release 菜单 —— debug 构建里 "Mods" 按钮出现,release 构建里 ModdingService.addMenuButtons 首行 isDebug return,no-op

## Acceptance

- ✅ WndModManager 窗口列 ModRegistry.all(),CheckBox 开关 + setEnabled 持久化
- ✅ WndGame 主菜单(debug 构建)有 "Mods" 按钮 → 打开 WndModManager;release 构建因 isDebug 守卫不显示(R7)
- ✅ LuaEngine.init 末尾调 `loadModEntryScripts()`(内部用 `ModRegistry.all()`,**不**显式 scan),enabled mod 的 entry 加载
- ✅ **开关生效**:test_mod enabled → test_mod_item 注册;disabled → 不注册(测试覆盖)
- ✅ entry 容错:文件缺/编译失败/无 entry 字段 → log + continue,不拖垮启动
- ✅ **entry 路径校验**(codex round-1 #4):`../`、绝对路径、`\`、非 `.lua` → manifest 拒绝 → mod 被 scan 跳过
- ✅ 现有 scripts/ 扁平加载不变,208 测试零回归
- ✅ 上游改动:仅 WndGame.java(1 import + 1 call hook,沿 SaveSlotService/LuaDebugService 先例,C1/C4/C8)
- ✅ codex_reviewer APPROVED

## 风险 + 注意

- **R1: globals.set 时序**。entry script 调 register_*,这些 global 必须在 entry 加载前 set。LuaEngine.init L70-102 已 set 全部 register_*(在 loadXxxScripts 之前)。entry 加载放 init 末尾 → globals 已就位。worker 确认。
- **R2: ModRegistry.scan 时序**。scan 用 Game.versionCode 做版本门(M5a R3)。LuaEngine.init 时 versionCode 必须已设。worker 沿 M5a 验证(若未设,scan 全 skip → entry 不加载 → 测试暴露)。
- **R3: i18n(Messages.get 跨包失效)**。WndModManager/ModdingService 用硬编码 ZH/EN txt()(沿 WndSaveSlotSelect 模式)。
- **R4: UI headless 不可验**。WndModManager 渲染/headless 跑不起来。测试验 entry 加载逻辑 + setEnabled round-trip(数据层),不验渲染。desktop 实机验证留 worker 环境若可(GLFW 之前的 M4d 遗留:无显示跑不起来)。
- **R5: entry script 沙箱**。entry 用同一 globals(register_* 受限 API)。entry 不能 dofile(沿 N2 现状)。一个 mod 的 entry 不能 load 另一个 mod 的脚本(隔离)。
- **R6: 重启生效 UX**。MVP 不热重载。WndModManager 提示"重启生效"。worker 评估热重载(卸载已注册内容复杂,LuaEngine 注册是不可逆的 add)→ MVP 不做,文档化。
- **R7: "Mods" 按钮始终显示 vs isDebug 守卫**。原版一周目(无 mod)菜单多个 "Mods" 按钮是否破坏体验?**决定采纳 isDebug 守卫**(沿 `LuaDebugService.addMenuButton` 模式:`if (!DeviceCompat.isDebug()) return;`)。M5 前不污染 release 菜单,与同处 WndGame 的 LuaDebugService 一致。M5c/正式发布前再评估放开。
- **R8: C2 包隔离**。WndModManager/ModdingService 进 modding/ 子包。WndGame +1 hook 是唯一上游改动。

## Phase 1 调研结论(worker 实施依据)

1. **LuaEngine.init 插入点确认**:`initInternal()` L74-102 set 全部 register_* globals → L107 INIT_SCRIPT → L116-134 loadXxxScripts → L136 `initialized=true`。`loadModEntryScripts()` 插在 L134 之后、L136 之前。globals 已就位,entry script 可调 register_*。(R1 ✓)
2. **CheckBox 控件**:`ui.CheckBox extends RedButton`,构造 `CheckBox(label)`,无 onChange listener —— 点击时 `onClick()` 自动 `checked(!checked())`。要感知 toggle:子类化 override `onClick()`,先 `super.onClick()`(完成 toggle),再读 `checked()` 持久化 `ModRegistry.setEnabled(id, checked())`。
3. **i18n 范式确认**:`SaveSlotService` 用 `LANG_ZH` 静态 boolean + `MENU_*_ZH/_EN` 常量三元;`WndSaveSlotSelect` 用 `TXT_ZH/TXT_EN` map + `txt(key)`。M5b:ModdingService label 用前者(单按钮,常量够),WndModManager 用后者(多 key,map 合适)。`LANG_ZH` 判定沿 WndSaveSlotSelect(Locale + Messages.lang() 双判)更稳。
4. **WndGame 注入点确认**:L118-122 `SaveSlotService.addMenuButtons(this);` 已是 fork hook,`addButton` 已 public(L126)。`ModdingService.addMenuButtons(this);` 加在其后。注意 L123 已有 `LuaDebugService.addMenuButton(this);`,M5b hook 放在 SaveSlotService 与 LuaDebugService 之间(fork 区块内)。
5. **测试 setup 模式**:`ModScannerTest` 提供 headless 范式 —— `HeadlessApplication` + `@BeforeClass` 设 `Game.versionCode=896` + `@Before` 注入 `FakePreferences`(HashMap backed) + `ModRegistry.resetForTests()` + `TemporaryFolder`。`LuaModEntryTest` 沿此。`LuaEngineTest` 不设 versionCode(scan 全 skip,只验 flat 脚本)—— M5b 新测试**必须**设 versionCode=896 否则 scan 跳过 test_mod。
6. **test_mod_item 字段模板**:`test_sword.lua` 用 `register_item{id/name/desc/image/tier}`。test_mod_item 沿此(image 用不同值避免视觉混淆,tier=2 沿用)。
7. **测试可注入性(关键)**:`ModRegistry.scanDir(FileHandle)` 是 package-private,测试(同包)可调以预填 temp mods。`loadModEntryScripts` 用 `ModRegistry.all()`(lazy)不 clobber 预填。temp mod 的 entry 路径解析走 `Gdx.files.internal("mods/<id>/<entry>")` —— temp 目录不在 classpath,所以 "missing file" 分支可被 temp mod 真实触发(测容错)。

## 参考

- M5a `modding/ModManifest.java`(+entry 字段)、`ModRegistry.java`(all/isEnabled/setEnabled)、`ModScanner.java`
- M0-M4 `modding/LuaEngine.java`(`init` L70-135 / `loadScriptsFrom` 编译 helper / register_* globals)
- `saveslot/SaveSlotService.addMenuButtons`(L360,fork UI 注入范式)+ `saveslot/WndSaveSlotSelect.txt()`(硬编码 i18n)
- `ui/CheckBox`(extends RedButton,checked 控件)
- `windows/WndGame`(L100-130,菜单按钮 + SaveSlotService.addMenuButtons 注入点 + public addButton)
- `scripts/items/test_sword.lua`(test_mod_item entry 字段模板)
- M4d/M5a codex workaround:`codex exec --sandbox read-only`(CAO codex_reviewer v0.142.0 失效)
- modding 范式 + 约束 C1-C5 + CLAUDE.md
