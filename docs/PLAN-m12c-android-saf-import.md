# PLAN: M12c — Android SAF zip import(平台 seam 的 android 侧)

## Goal
填上 M12b 留的 seam:在 android 模块实现 `ModImporter`(SAF `ACTION_OPEN_DOCUMENT` 选 zip → `ContentResolver.openInputStream` → `ModInstaller.installFromStream`),并在 `AndroidLauncher` 注册。合并后 android 上 WndModManager 的 "导入模组" 按钮从隐藏变可见,玩家能从任意 SAF 可达位置(下载/文件/云端)导入 mod zip。**与 M12b 零冲突**(只改 android 模块 + 一行静态注册,core/desktop 不碰)。

## Context
M12b 已交付 seam:
- `core/.../modding/ModImporter.java` — interface(`pickZip(ImportCallback)`)+ 嵌套 `Holder`(`setPlatformImpl`/`get`,volatile)。`get()==null` → WndModManager 按钮隐藏。
- `core/.../modding/ModInstaller.java` — `installFromStream(InputStream, ImportCallback)` 平台无关解压+校验(已含 SAF 需要的全部安全:路径穿越/256 entry/64MB cap/mod.json 校验/原子 rename)。
- `ImportCallback`:`onSuccess(String modId)` / `onError(String code)` / `onCancel()`。
- desktop 已注册 `DesktopModImporter`(JFileChooser on EDT)。

**关键现成基础设施(android 模块已有,M12c 直接复用)**:
- `AndroidLauncher` 已有 SAF 路由:`ActivityResultHandler` interface + `registerActivityResult(requestCode, handler)` + `unregisterActivityResult` + `onActivityResult` override(`AndroidLauncher.java:63-86`,fork 为 SaveSlot 加的)。
- `AndroidSaveSlotBridge`(`android/.../AndroidSaveSlotBridge.java`)已是 SAF import 的完整参考:`importSlot`(`:147-168`)用 `ACTION_OPEN_DOCUMENT` + `CATEGORY_OPENABLE` + `application/zip` → `registerActivityResult` + `startActivityForResult` → 结果在 worker 线程 `getContentResolver().openInputStream(uri)` → 流式处理 → `postRunnable` 回渲染线程 callback。`ActivityNotFoundException` → unregister + 失败码。
- 线程模型:SAF picker(主线程)→ worker 线程读流(`new Thread(...).start()`)→ `Gdx.app.postRunnable` 回渲染线程(M12b 的 WndModManager callback 期望在渲染线程更新 UI)。

**M12c = 镜像 `AndroidSaveSlotBridge.importSlot`,把 `SaveSlotService.importFromStream` 换成 `ModInstaller.installFromStream` + 注册 `ModImporter`。** 无新基础设施。

## Files
- **`android/.../android/AndroidSafModImporter.java`**(新):`implements ModImporter`。`pickZip(ImportCallback cb)`:
  - 构造 `Intent(ACTION_OPEN_DOCUMENT)` + `CATEGORY_OPENABLE` + `setType("application/zip")`。
  - `registerActivityResult(REQUEST_MOD_IMPORT, handler)` + `startActivityForResult(intent, REQUEST_MOD_IMPORT)`;`ActivityNotFoundException` → `unregisterActivityResult` + `cb.onError("saf_unavailable")`(postRunnable 包)。
  - handler.onResult:`resultCode != RESULT_OK || data==null || data.getData()==null` → `cb.onCancel()`(postRunnable);否则 worker 线程:`getContentResolver().openInputStream(uri)` → `ModInstaller.installFromStream(is, wrappedCb)`,finally close is。`wrappedCb` 把 `onSuccess`/`onError` 各自 `postRunnable` 回渲染线程再转给原 `cb`(`onCancel` 同理)。
  - 唯一 request code(如 `0x5303`,避开 SaveSlot 的 `0x5301`/`0x5302`)。
  - 持有 `AndroidLauncher activity` 引用(构造注入,镜像 `AndroidSaveSlotBridge(AndroidLauncher)`)。
- **`android/.../android/AndroidLauncher.java`**(改):`onCreate` 末尾(`initialize(...)` 前后均可,只要首个 WndModManager 打开前)加一行 `ModImporter.setPlatformImpl(new AndroidSafModImporter(this));`。加 import。
- **测试**:无单元测试(SAF 需真实 Activity + ContentResolver,headless 不可测)。镜像 `AndroidSaveSlotBridge`(同样无单测)。靠 `./gradlew :android:assembleDebug` 编译 + 真机手动验证。若 worker 能写一个不依赖 Activity 的纯逻辑测试(如 callback postRunnable wrap 的可测子集),鼓励但非必须。

### 显式延后(不在 M12c 范围)
- **mod 卸载/更新 UI**:import 后的逆向操作(删 `mods_user/<id>/`)。需 WndModManager 加删除按钮 + origin 感知,碰 core WndModManager(与 M12b 同文件)→ 独立 feature,顺序在后。
- **多选 import**:SAF 支持多选(`EXTRA_ALLOW_MULTIPLE`),本 milestone 单选。
- **进度 UI**:解压进度(64MB cap 兜底,worker 线程不卡 UI)。

## Steps
1. **读参考**:`AndroidSaveSlotBridge.java:147-210`(importSlot + handleImportResult)+ `AndroidLauncher.java:63-86`(ActivityResult 路由)。M12c 是它们的 mod 版。
2. **`AndroidSafModImporter`**:
   - `pickZip(cb)`:构造 SAF intent(`ACTION_OPEN_DOCUMENT` + `CATEGORY_OPENABLE` + `application/zip`)。
   - `try { activity.registerActivityResult(REQ, handler); activity.startActivityForResult(intent, REQ); } catch (ActivityNotFoundException e) { activity.unregisterActivityResult(REQ); invoke(cb, () -> cb.onError("saf_unavailable")); }`
   - handler:cancel → `invoke(cb, () -> cb.onCancel())`;ok → worker 线程跑 unzip。
   - worker:`is = activity.getContentResolver().openInputStream(uri); if (is==null) { invoke(cb, ()->cb.onError("io_error")); return; } ModInstaller.installFromStream(is, wrap(cb));` finally close。
   - `wrap(cb)`:`onSuccess`/`onError`/`onCancel` 都 `postRunnable` 后转给 cb(ModInstaller 在 worker 线程调 cb,UI 更新必须在渲染线程)。
   - `invoke(cb, r)`:`Gdx.app.postRunnable(r)` 辅助。
3. **`AndroidLauncher.onCreate`**:加 `ModImporter.setPlatformImpl(new AndroidSafModImporter(this));`(建议放 `initialize(new ShatteredPixelDungeon(support), config);` 之前,impl holder 是 static,早注册无副作用)。
4. **编译**:`./gradlew :android:assembleDebug`(确认无编译错;AndroidX/SAF API 已在 SaveSlot 用过,依赖已就绪)。
5. **codex 评审**:Phase 1 PLAN + Phase 2 diff(`codex exec --sandbox read-only`)。重点:线程模型(picker 主线程 / unzip worker / callback 渲染线程)、request code 唯一、cancel vs error 语义、Activity 引用泄漏(无长期持有,handler 用完即 unregister)。
6. **真机手动验证**(`adb -s 20210119085654`):
   - 装 debug APK,打开 暂停菜单 → 模组管理 → 看到 "导入模组" 按钮(M12b 在 desktop 显示,android 此前隐藏,M12c 后可见)。
   - 点按钮 → SAF 弹出 → 选一个测试 zip(含合法 mod.json)→ 提示 "已导入 <id>,重启生效"。
   - 重启 → 模组列表出现新条目,标 `[外部]`(M12a origin 角标)。
   - 异常路径:取消 SAF → 静默(不崩);选非法 zip(无 mod.json)→ 提示错误码;已存在 id → "已存在"。

## Acceptance
- [ ] `AndroidSafModImporter implements ModImporter`,SAF `ACTION_OPEN_DOCUMENT` 选 `.zip`
- [ ] `ContentResolver.openInputStream(uri)` → `ModInstaller.installFromStream`(复用 M12b 的解压+校验,不重写)
- [ ] worker 线程跑 unzip,callback 经 `postRunnable` 回渲染线程(镜像 AndroidSaveSlotBridge)
- [ ] `ActivityNotFoundException` → `onError("saf_unavailable")` + unregister(不崩)
- [ ] SAF 取消 → `onCancel()`(静默)
- [ ] `AndroidLauncher.onCreate` 注册 → android 上 `ModImporter.get() != null` → WndModManager 按钮可见
- [ ] request code 与 SaveSlot 不冲突(`0x5301`/`0x5302` 占用,M12c 用新值如 `0x5303`)
- [ ] `./gradlew :android:assembleDebug` 编译过
- [ ] 真机:导入合法 zip 成功 + 重启后 [外部] mod 出现 + 取消/错误路径不崩
- [ ] C3 不破:不导入任何 mod 时原版一周目不受影响(ModImporter 注册本身零副作用)
- [ ] 与 M12b/desktop 零文件冲突(只改 android 模块)

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,**不 assign codex_reviewer**(memory:必超时)
- **不重写解压逻辑** —— 全部走 `ModInstaller.installFromStream`(M12b 已验证,577 tests 含 11 ModInstallerTest)。M12c 只产 InputStream。
- SAF 用 `ACTION_OPEN_DOCUMENT`(content URI,**无需 READ_EXTERNAL_STORAGE 权限**),与 SaveSlot import 一致
- Activity 引用:`AndroidSafModImporter` 由 `AndroidLauncher` 持有(静态 holder),Activity 生命周期 = 进程生命周期,无泄漏风险(handler 用完即 unregister)
- 不热加载、不覆盖已存在 mod(M12b 契约,ModInstaller 层已守)
- 本 feature 只做 android 侧;mod 卸载/更新 UI 是后续独立 feature
