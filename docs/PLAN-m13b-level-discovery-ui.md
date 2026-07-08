# PLAN: M13b — 自定义关卡发现/选择 UI(让导入/注册的关卡玩家可进)

## Goal
玩家从暂停菜单进入「自定义关卡」窗口,浏览所有已注册关卡(builtin + external),选一个进入。当前 `enterLevel` 是 debug-gated(`LuaLevelService:104-108`),玩家无入口;导入/注册的关卡(M12d external levels / M4a builtin)玩家进不去。本 feature 放开 registered 关卡的 debug 门 + 加发现 UI。**与 M13a/c 零文件冲突**(LuaLevelService + 新窗口 + WndGame 单点)。

## Context(Explore 2026-07-09 核实)
- **WndGame 菜单注入模式**(fork 已有):`WndGame.java:119` 调 `SaveSlotService.addMenuButtons(this)`,`:123` `ModdingService.addMenuButtons(this)`,`:127` `LuaDebugService.addMenuButton(this)`;`WndGame.addButton` 是 public(`:133`)。每个 service 构造 `RedButton` + `wnd.addButton(btn)`(见 `SaveSlotService.java:447-469`)。**这是 M13b 加「自定义关卡」按钮的模板**。
- **LuaLevelRegistry 迭代**:`LuaLevelRegistry.ids()`(`:83`)返回 `Set<String>`;`get(id)`(`:74`)返回 `Entry`(含 `table`,table 里有注册时的 `name` 字段)。可构 (id, name) 列表。
- **enterLevel debug 门**(`LuaLevelService.java:104-108`):`if (!DeviceCompat.isDebug()) { log; return; }`,**所有** enter 走此门。**放开先例**:`injectLevelTraps`(`:388`)**非 debug-gated**,gate 在 `LuaTrapRegistry.hasAny()` —— 这是"把原 debug 功能放开给 release"的既成模式(M13b 照此)。
- **列表窗口模式**:`WndOptions`(`windows/WndOptions.java:32`)接收 `String... options` 渲染垂直按钮列表;`WndOptionsCondensed` 紧凑变体;`WndCatalog` 可滚动参考。最简:动态 options 数组从 `LuaLevelRegistry.ids()` 构造。
- **M12d external levels**:register_level 注册时存 origin/baseDir;enterLevel 按 origin 分流加载。M13b 只消费 registry(列 id+name + enter),不改加载逻辑。

**设计决策**:
- **放开门**:`enterLevel` 的 gate 从 `!isDebug()` 改为"registered 关卡允许 / 未注册仍 debug-only":`if (!LuaLevelRegistry.contains(id) && !DeviceCompat.isDebug()) { reject }`。即 builtin/external 已注册关卡 release 可进;裸 asset id(`mods/levels/<id>.json` 未 register)仍 debug-only(保 C3 — 不让玩家随意进任意 asset 关卡)。镜像 injectLevelTraps 的 hasAny 放开先例。
- **listEnterableLevels()**:`LuaLevelService.listEnterableLevels()` 返回 `List<String>`(id),从 `LuaLevelRegistry.ids()` 构造(可过滤,如排除明确 unsafe 的)。UI 用 `get(id).table.get("name")` 取显示名。
- **新窗口 WndCustomLevels**(`modding/` 或 `windows/`):垂直列表,每行 = 关卡名,点击 → 确认(`WndOptions` "进入 <name>?")→ `LuaLevelService.enterLevel(id)`。空列表显示提示("无自定义关卡")。
- **WndGame 入口**:新 `LuaLevelService.addMenuButtons(wnd)`(镜像 SaveSlotService),构「自定义关卡」RedButton,**仅当 `!listEnterableLevels().isEmpty()` 时 addButton**(无关卡不显示空按钮)。

## Files (worker-verified)
- **`core/.../modding/LuaLevelService.java`**:
  - `enterLevel` gate 改:`if (!LuaLevelRegistry.contains(id) && !DeviceCompat.isDebug()) { log + return }`(registered 关卡放行)。
  - 新增 `public static List<String> listEnterableLevels()`:从 `LuaLevelRegistry.ids()` 构 id 列表(排序:字典序或注册序)。
  - 新增 `public static void addMenuButtons(WndGame wnd)`(镜像 SaveSlotService):构 RedButton,gated on `!listEnterableLevels().isEmpty()`,`wnd.addButton` → 打开 WndCustomLevels。
- **`core/.../modding/WndCustomLevels.java`**(新,或 `windows/`):垂直 ScrollPane + 每关一行(RedButton/自定义 Component);点击 → 确认对话框 → `enterLevel`。文案硬编码 ZH/EN。
- **`core/.../windows/WndGame.java`**:构造函数加 `LuaLevelService.addMenuButtons(this);`(在现有 addMenuButtons 调用旁,如 line 127 后)。
- **测试**(`LuaLevelServiceTest` 或新):listEnterableLevels 返回注册关卡 + enterLevel registered 门放行(builtin/external 可进,非 debug)+ 未注册 id 仍拒(release)。镜像 LuaEngineExternalLoadTest 的 enable-mod + init 模式。

### 显式延后
- **关卡详情/预览**:列表只显示名,不预览缩略图/描述。留后续。
- **关卡排序/分类**:字典序或注册序;无分类 UI。
- ** builtin `mods/levels/` 扫描入列表**:只列 register_level 注册的(运行期 registry)。未注册的 asset 关卡(debug-only)不入玩家列表。

## Steps

### Worker-verified refinements (Explore 已核实 line 号,2026-07-09)

- **门先例精确确认**:`injectLevelTraps`(`LuaLevelService.java:385-390`)的 gate 是 `if (!LuaTrapRegistry.hasAny()) return;`(**非 debug-gated**,M9 release 开放)。M13b 照此:registered 关卡放行,未注册仍 debug-only。
- **`name` 字段必然存在**:`register_level`(`LuaEngine.java:891-892`)**强制要求** `id` + `name`(`checkjstring()` 会抛),所以每个 registered 关卡都有合法 name。UI 取 `table.get("name")` 安全(仍加 fallback→id 防御)。
- **线程**:RedButton `onClick()` 已在 render 线程(同 `LuaDebugService.java:56-66` debug 按钮直接调 `enterLevel` 无 wrap)。**不需要 `Game.runOnRenderThread`**(那只在 actor 线程调用时需要,如 `RpdApi.EnterTown:981`)。
- **可测 seam(关键设计)**:整 `enterLevel` 依赖 `Dungeon.hero`/`saveAll`/`switchLevel`,headless 单测跑不起(现有测试也不测 enter 全流程,desktop 手验)。**按代码库惯例**(`injectLevelNpcs→spawnForDepth`、`injectLevelTraps→placeLuaTraps`、`captureLiveState/applyLiveState` 都是提取可测 seam),抽出 gate 谓词:
  ```java
  public static boolean isEnterAllowed(String id) {
      return LuaLevelRegistry.contains(id) || DeviceCompat.isDebug();
  }
  ```
  `enterLevel` 首行改 `if (!isEnterAllowed(id)) { log + return; }`。直接单测谓词,无需 Dungeon 栈。
- **窗口位置**:`modding/WndCustomLevels.java`(镜像 `saveslot/WndSaveSlotSelect.java` 就近其 service;CLAUDE.md fork 代码归子包约定)。
- **LANG 标志**:镜像 `SaveSlotService:75-76` `LANG_ZH = Locale.getDefault().getLanguage().equalsIgnoreCase("zh")`。

### 实施步骤(可执行粒度)

1. **`LuaLevelService.enterLevel` 门**(改 `:104-108`):
   - 新增 `public static boolean isEnterAllowed(String id)` 谓词(见上)。
   - `enterLevel` 首段 `if (!DeviceCompat.isDebug()) {…return;}` 换成 `if (!isEnterAllowed(id)) { Gdx.app.error(TAG, "enterLevel ignored: '" + id + "' not registered (release)"); return; }`。
2. **`listEnterableLevels()`**(新增,LuaLevelService):
   ```java
   public static List<String> listEnterableLevels() {
       List<String> ids = new ArrayList<>(LuaLevelRegistry.ids());
       Collections.sort(ids);
       return ids;
   }
   ```
   (字典序,确定性。需 import `java.util.List`/`Collections`。)
3. **`levelDisplayName(String id)`**(新增 package-static,LuaLevelService):`LuaLevelRegistry.get(id).table.get("name")` → `.tojstring()`,entry null / 非 string / 异常 fallback `id`。供 UI + 测试用。
4. **`WndCustomLevels extends WndOptions`**(新,`modding/`):
   - 构造:`new WndCustomLevels()` —— 内部从 `LuaLevelService.listEnterableLevels()` 取 id 列表,构 `String[] names`(`levelDisplayName(id)`)。
   - `super(title, message, names)` —— title "自定义关卡 / Custom Levels",message 可空或 "(N)"。
   - `onSelect(int index)`:取对应 id → 弹确认 `WndOptions("进入 <name>?", "", "进入 / Enter", "取消 / Cancel")`,Yes → `LuaLevelService.enterLevel(id)`(直接调,render 线程)。
   - 空列表处理:列表为空时不进此窗口(`addMenuButtons` 已 gate);防御性构造时若空,显示单行提示 "无自定义关卡 / No custom levels"。
   - 文案硬编码 ZH/EN(镜像 `WndSaveSlotSelect.txt()` / SaveSlotService 硬编码约定,**不用** Messages.get)。
5. **`LuaLevelService.addMenuButtons(WndGame wnd)`**(新增,镜像 SaveSlotService:447-469):
   - `if (listEnterableLevels().isEmpty()) return;`(无关卡不显示按钮)
   - 构 RedButton("自定义关卡 / Custom Levels")`onClick` → `wnd.hide(); GameScene.show(new WndCustomLevels());`,icon `Icons.get(Icons.DEPTH)`,`wnd.addButton(btn)`。
6. **`WndGame.java:127` 后**(LuaDebugService hook 后)加:
   ```java
   // --- Fork(M13b): custom-level discovery UI (registered levels enterable in release) ---
   LuaLevelService.addMenuButtons(this);
   // --- Fork end ---
   ```
7. **测试**(`modding/LuaLevelDiscoveryTest.java`,镜像 `LuaExternalLevelTest` 的 headless + `ModRegistry.scanExternal` + `LuaEngine.init()`):
   - `isEnterAllowed`:registered id(非 debug)`true`;未注册 id(非 debug)`false`;debug 下两者皆 `true`。
   - `listEnterableLevels`:注册 N 个 → 返回 N 个 id、字典序、`containsAll` registry。
   - `levelDisplayName`:返回注册 name;未注册 fallback id。
   - 注:不需真开关 `DeviceCompat.isDebug()`(它是 final 常量,测 registered/未注册 路径即可覆盖主逻辑;debug 分支由 `||` 短路天然包含)。
8. **`./gradlew :core:test`** 全绿(583 + 新增 1 class)。
9. **codex 评审**(Phase 1/2,`codex exec --sandbox read-only`):重点 —— 门放开安全性(只 registered 放行,未注册仍锁,C3)、isEnterAllowed seam 是否泄漏未注册入口、空列表 UI、与 M13a/c 零冲突、文案硬编码(不依赖 Messages.get)。
10. **desktop 手动验证**(可选):注册一个 level → 暂停菜单见「自定义关卡」→ 进 → 确认进得去 + 能返回。

## Acceptance
- [ ] `enterLevel` 门放开:registered 关卡 release 可进;未注册仍 debug-only(C3 守)
- [ ] `listEnterableLevels()` 返回注册关卡 id 列表
- [ ] WndCustomLevels 列表 + 点击确认 + 进入
- [ ] WndGame「自定义关卡」按钮仅有关卡时显示
- [ ] `./gradlew :core:test` 全绿(583 + 新增)
- [ ] C3 不破:无注册关卡时原版一周目不受影响(按钮不显示 + 门未注册仍锁)
- [ ] 与 M13a/c 零文件冲突

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,**不 assign codex_reviewer**(memory:必超时;若 502/503 按政策 B 以 :core:test 硬验收)
- **门放开要精确**:只 registered 放行(`LuaLevelRegistry.contains(id)`),未注册裸 asset id 仍 debug-only —— 防 C3 破(玩家不能任意进未审核 asset)
- enterLevel 复用现有 switchLevel/save 逻辑(debug 已验证),**不重写**进入/返回流程
- 按钮显隐 gated on `!listEnterableLevels().isEmpty()`(无关卡不显示空入口)
- 与 M13a(WndModManager/ModInstaller)/ M13c(assets)零重叠
