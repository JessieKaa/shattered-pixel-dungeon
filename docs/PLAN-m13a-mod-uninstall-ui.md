# PLAN: M13a — mod 卸载/更新 UI(补 M12 import 的逆向缺口)

## Goal
玩家能从模组管理器**卸载**外部 mod(删 `mods_user/<id>/`)+ **更新**(re-import 已存在 id 时确认覆盖)。M12 只做了 import,M13a 补逆向闭环。builtin mod 不可卸载(随游戏发布)。**与 M13b/c 零文件冲突**(只碰 ModInstaller/WndModManager/ModRegistry + 测试)。

## Context(Explore + M12 已知)
- M12b `ModInstaller`(`modding/ModInstaller.java:44`)只有 `installFromStream(InputStream, ImportCallback)`(line 60),**无 remove**。`already_exists` 直接失败(line 241 附近 staging cleanup),不覆盖。
- `ModManifest` 有 `origin`(BUILTIN/EXTERNAL,M12a)+ `baseDir`。EXTERNAL mod 的 baseDir = `mods_user/<id>/`(`Gdx.files.local`)。
- `WndModManager`(`modding/WndModManager.java`):mod 列表(ModCheckBox 行)+ M12b 的 import 按钮。无删除入口。
- `FileHandle.deleteDirectory()`(libgdx)递归删目录;`FileUtils.deleteDir` 也在(SaveSlotService 用)。
- builtin mod(origin=BUILTIN)不能删(classpath asset,不可运行时删)→ 卸载只对 EXTERNAL 开放。
- **更新 = 卸载旧 + 装新**(或 install 时传 overwrite 标志)。最简:`removeMod(id)` + `installFromStream`。

**设计决策**:
- `ModInstaller.removeMod(String id, Callback)`:查 `ModRegistry.get(id)`;origin != EXTERNAL → 失败(`not_external` / builtin 不可删);否则 `mod.baseDir.deleteDirectory()`(= 删 `mods_user/<id>/`)。成功后提示重启(M12 契约:registry 注册不可逆,删后重启才从列表消失)。
- **更新路径**:`installFromStream` 的 `already_exists` 分支增加"确认覆盖"语义 —— 调用方(caller)收到 `already_exists` 错误码后弹确认对话框,确认 → `removeMod(id)` 再 `installFromStream`。**不改 ModInstaller 签名**(保持 `installFromStream` 单一职责;overwrite 编排在 UI 层)。这样 ModInstaller 的测试不破,UI 层组合 remove+install。
- **UI**:WndModManager 的 EXTERNAL mod 行加"卸载"入口。SPD 交互惯例:长按行 / 行末小按钮 → `WndOptions` 确认("卸载此模组?需重启")→ removeMod → 刷新列表。worker 选具体交互(长按 vs 按钮),但必须带确认对话框(防误删)。
- **刷新**:卸载后从 ModRegistry 列表移除该条(或标 disabled + 提示重启)。最简:卸载成功后 close + reopen WndModManager(re-scan)。

## Files (worker-verified)
- **`core/.../modding/ModInstaller.java`**:新增 `removeMod(String id, ImportCallback cb)` —— 查 origin;EXTERNAL → `baseDir.deleteDirectory()` + `cb.onSuccess(id)`;否则 `cb.onError("not_external")`。失败(IO)→ `cb.onError("io_error")`。
- **`core/.../modding/WndModManager.java`**:EXTERNAL mod 行加卸载入口(确认对话框 `WndOptions`/`WndMessage`)+ 调 `ModInstaller.removeMod`;import 的 `already_exists` 回调加"覆盖?"确认 → removeMod + 重试 install。文案硬编码 ZH/EN(WndSaveSlotSelect 模式)。
- **`core/.../modding/ModRegistry.java`**(可能):若需运行时移除条目(不重启就消失),加 `remove(String id)`(内存 map 移除,不碰磁盘;磁盘由 ModInstaller 删)。可选 —— 也可只提示重启,不即时移除。
- **测试**(`ModInstallerTest` 扩展):`removeMod` external 成功删目录 / builtin 拒(`not_external`)/ 不存在 id / io 错误路径。镜像现有 ModInstallerTest 的 temp Local 模式。

### 显式延后
- **批量卸载**:一次删多个。本 feature 单个。
- **卸载前备份/导出**:删前可选导出 zip(M12 import 的逆)。留后续。
- **即时从列表消失不重启**:若 ModRegistry.remove 实现,列表即时刷新;但 LuaEngine registry(已 register 的 item/spell)不可逆 unregister,仍需重启才彻底。本 feature 接受"删文件 + 提示重启"。

## Steps
1. **读参考**:`ModInstaller.installFromStream`(staging/already_exists 模式)+ `ModManifest.origin`/`baseDir` + `WndModManager` 行布局 + M12b import 按钮的 callback 模式。
2. **`ModInstaller.removeMod`**:按设计决策实现。复用 `Gdx.files.local` / `mod.baseDir`。
3. **WndModManager 卸载入口**:EXTERNAL 行加交互(长按/按钮)+ `WndOptions` 确认 + removeMod + 结果提示(成功 "已卸载,重启生效")。
4. **import 覆盖路径**:`already_exists` 回调 → `WndOptions`("已存在,覆盖?")→ 确认 → `removeMod(id)` + 重试 `installFromStream`。
5. **测试**:`removeMod` 各路径。
6. **`./gradlew :core:test`** 全绿。
7. **codex 评审**(Phase 1 PLAN + Phase 2 diff,`codex exec --sandbox read-only`):重点 —— builtin 不可删(origin 守卫)、确认对话框防误删、删文件路径安全(只删 `mods_user/<id>/`,不越界)、C3(不影响 builtin/vanilla)。
8. **desktop 手动验证**(可选):导入一个 zip → 卸载 → 重启 → 列表消失;re-import 同 id → 覆盖确认 → 成功。

## Acceptance
- [ ] `ModInstaller.removeMod(id, cb)` 只删 EXTERNAL(builtin 拒 `not_external`)
- [ ] 删 `mods_user/<id>/`(baseDir.deleteDirectory),不越界
- [ ] WndModManager EXTERNAL 行有卸载入口 + 确认对话框(防误删)
- [ ] import `already_exists` → 覆盖确认 → removeMod + 重装
- [ ] 成功提示"重启生效"(M12 契约)
- [ ] `./gradlew :core:test` 全绿(583 + 新增)
- [ ] C3 不破:builtin mod / vanilla 不受影响;误删防护(确认框 + origin 守卫)
- [ ] 与 M13b/c 零文件冲突

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,**不 assign codex_reviewer**(memory:必超时;若 502/503 按政策 B 以 :core:test 硬验收)
- **builtin 不可删**(origin 守卫是安全核心 —— 防删游戏自带 asset)
- 删目录只用 `mod.baseDir.deleteDirectory()`(限定到该 mod 目录),**绝不**递归删 `mods_user/` 根
- overwrite 编排在 UI 层(remove + install),**不改 ModInstaller.installFromStream 签名**(保 M12b 测试不破)
- 与 M13b/c 零重叠(只碰 ModInstaller/WndModManager/ModRegistry)

---

## Refinement (worker, Phase 1 — 2026-07-09)

代码核对结论(读 ModInstaller/ModImporter/WndModManager/ModRegistry/ModScanner/ModManifest/DesktopModImporter/ModInstallerTest):

### removeMod 实现细节(已锁定)
- **签名**:`public static void removeMod(String id, ModImporter.ImportCallback cb)`。复用 `ImportImporter.ImportCallback`(onSuccess/onError/onCancel),`onCancel` 不调用(无取消语义),与 PLAN 一致,**不改 installFromStream 签名**。
- **逻辑**:
  1. `cb == null` → 静默 return(镜像 installInto 的边界 null 守卫);`id` 不单独判空 —— `ModRegistry.get(null)` 自然返回 null → 走 not_found。
  2. `ModManifest m = ModRegistry.get(id)`;`m == null` → `cb.onError("not_found")`。
  3. `m.origin != EXTERNAL` → `cb.onError("not_external")`(**安全核心**:origin 为 BUILTIN 或 null 都拒绝,失败即安全 —— 永不删 classpath asset / 未知 origin 的目录)。
  4. `boolean ok = m.baseDir.deleteDirectory()`;`!ok` → `cb.onError("io_error")`;catch Throwable → `cb.onError("io_error")`。
  5. 成功 → `cb.onSuccess(id)`。
- **路径安全**:baseDir 对 EXTERNAL mod = `mods_user/<id>/`(由 ModScanner.scanChildren 设定,见 ModScanner.java:180 `setRuntimeMeta`)。deleteDirectory 只删该子树,**绝不碰 `mods_user/` 根**(无根句柄)。origin 守卫保证只有 EXTERNAL 的 baseDir(= local handle)会被删,builtin 的 baseDir(internal/classpath)永不进入删除路径。
- **不需要额外测试缝**:removeMod 用 `mod.baseDir`(扫描时已解析为绝对 FileHandle),**不碰 `Gdx.files.local`**,所以测试直接调 public removeMod + 用 `ModRegistry.scanExternal(new FileHandle(tmp))` 播种即可(镜像 LuaEngineExternalLoadTest / ModInstallerTest 现有模式)。

### WndModManager 交互(已锁定)
- **卸载入口**:EXTERNAL mod 的 `ModCheckBox.onLongClick()`(继承自 Button.java:177,SPD 惯用长按)→ 开 `WndOptions` 确认。builtin 长按 → `WndMessage`("内建模组不可卸载")(教用户手势 + 解释为何无效)。
- **ModCheckBox 改非 static**(原 `private static final class` → `private class`):onLongClick 需 `WndModManager.this` 来刷新窗口。私有嵌套类,外部无影响。
- **确认对话框**:`WndOptions(mod name 标题, "需重启才彻底生效", ["卸载","取消"])`(WndOptions.java:57 构造)→ onSelect(0) → `ModInstaller.removeMod(id, cb)`。
  - onSuccess → `ModRegistry.scan()`(重扫,删掉的条目消失)+ `hide()` + `GameScene.show(new WndModManager())`(re-show 刷新列表;镜像 WndUpgrade.java:460 自身 re-show 模式)。
  - onError → `GameScene.show(new WndMessage(<错误文案>))`(卸载错误走独立 alert,不耦合 importResult 行;卸载只对 EXTERNAL 行暴露,not_external/not_found 实际不会触发,io_error 罕见)。
- **文案**(硬编码 ZH/EN map,WndSaveSlotSelect 模式):新增 `uninstall_title` / `uninstall_confirm`("将卸载 %s,需重启游戏才能彻底生效。") / `uninstall_btn` / `cancel` / `uninstall_ok`("已卸载,重启生效") / `builtin_no_uninstall`("内建模组不可卸载。") / `err_uninstall`("卸载失败(%s)")。
- **可发现性**:更新底部 hint → "长按外部模组可卸载,更改重启后生效。"(教用户长按手势)。
- **import already_exists 文案更新**:现文案 "如需更新请先在文件夹删除旧版本" → 改 "如需更新,请先卸载已安装的版本,再重新导入。"(指向新卸载入口,不再要求手动改文件)。见下「Overwrite 范围决策」。

### Overwrite 范围决策(关键,需 codex/dispatcher 确认)

**发现的问题**:PLAN Step 4 / Acceptance 写 "import already_exists → 覆盖确认 → removeMod + 重装",但 UI 层无法干净实现单次确认自动覆盖:
1. **拿不到冲突 id**:`ImportCallback.onError(String code)` 只传 code,不传冲突的 mod id。UI 不知道该 removeMod 哪个 id(冲突 id = zip 里 mod.json 声明的 id,但 UI 不解析 zip)。
2. **拿不到 stream**:zip 的 InputStream 由 picker(DesktopModImporter.pickZip)持有并消费,installFromStream 返回 already_exists 时流已关。UI 无法 "重试 installFromStream"。

**根因**:`ModImporter` 抽象是 "pick + install 一体"(picker 内部调 installFromStream),UI 只收 callback。要做单次确认覆盖,必须改 `ModInstaller`(暴露冲突 id,或加 overwrite 重载)和/或 `ModImporter`(暴露文件 / 加 overwrite 参数)+ 两个平台实现(DesktopModImporter、AndroidSafModImporter)。这超出 "只碰 ModInstaller/WndModManager/ModRegistry + 零 M13b/c 冲突" 的硬约束。

**M13a 的交付(约束内)**:**Option C** —— already_exists 文案改为指向新卸载入口(卸载旧版 + 重新导入,两步手动)。更新工作流可用、零范围扩张、零文件冲突。
- Acceptance 第 4 条调整为:**"import already_exists → 文案指向卸载入口;更新 = 卸载旧版(EXTERNAL 长按)+ 重新导入"**。

**显式延后(写入 Pending Issues)**:单次确认自动覆盖(需 ModInstaller overwrite 重载 + ModImporter 加 default `pickZip(cb, overwrite)` + DesktopModImporter 覆写;接受 "覆盖后重新选文件" 的 UX)。留 M13d 或后续,需扩范围决策。

### 测试细节(已锁定)
扩展 `ModInstallerTest`(@Before 加 `ModRegistry.resetForTests()` 隔离静态态):
1. `removeMod_external_success_deletesDir` — scanExternal 播种 imp_a → removeMod → onSuccess("imp_a") + `mods_user/imp_a/` 消失。
2. `removeMod_builtin_refused` — scanDir 播种 builtin mod(origin BUILTIN)→ removeMod → onError("not_external") + 目录仍在。
3. `removeMod_unknownId_notFound` — 播种 imp_a → removeMod("nope") → onError("not_found")。
4. `removeMod_dirMissing_ioError` — 播种后手动删 dir → removeMod → onError("io_error")(deleteDirectory 对不存在路径返回 false)。若 libgdx 行为不符(返回 true),改用 read-only-parent 触发或移除该 case + 注释说明。

### Acceptance(调整后)
- [x] `ModInstaller.removeMod(id, cb)` 只删 EXTERNAL(builtin 拒 `not_external`)
- [x] 删 `mods_user/<id>/`(baseDir.deleteDirectory),不越界
- [x] WndModManager EXTERNAL 行有卸载入口(长按)+ 确认对话框(防误删)
- [~] import `already_exists` → 文案指向卸载入口(Option C;自动单次覆盖延后 —— 见 Overwrite 范围决策)
- [x] 成功提示"重启生效"(确认框文案 + 底部 hint + 列表刷新)
- [ ] `./gradlew :core:test` 全绿(待跑)
- [x] C3 不破:builtin mod / vanilla 不受影响;误删防护(确认框 + origin 守卫)
- [x] 与 M13b/c 零文件冲突(只碰 ModInstaller/WndModManager + 测试;不碰 ModImporter/平台实现)

## Pending Issues
- **自动单次覆盖(overwrite)**:见上「Overwrite 范围决策」。M13a 交付 Option C(卸载 + 重导);真正单次确认覆盖需扩范围(ModInstaller 重载 + ModImporter/平台改动),建议留 M13d。
