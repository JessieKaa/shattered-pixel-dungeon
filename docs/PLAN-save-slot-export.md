# PLAN: Save Slot Export / Import

**Slug**: `save-slot-export`
**Branch**: `feature/save-slot-export` (based on `feature/local-save-slots`)
**Date**: 2026-06-30 (rev 3 — applied codex_reviewer round-2 should-fix items)

---

## Goal

为现有 save slot 系统增加**导出**(把一个 slot 打包成 zip,写到玩家可见的外部存储 / 分享)和**导入**(从 zip 恢复成一个新 slot)能力。当前 `SaveSlotService` 只能在 app 内部 `save_slots/{name}/` ↔ `game{1..6}/` 之间拷贝,无法跨安装/跨设备迁移存档。

## Context

- 现状:`SaveSlotService.saveToSlot/loadFromSlot` 已工作,使用 libgdx `FileHandle` + `FileUtils.copyDir`,但只在 app private 目录里操作。
- 用户场景:(a) 重装 APK 前备份;(b) 换手机迁移;(c) 把挑战存档分享给朋友。
- **硬约束(用户明确)**:`core` 模块必须保持**无 Android / desktop / AWT / Swing** 依赖。`core` 已经依赖 libgdx 与 `SPD-classes`,所以 `FileHandle` / `FileUtils` 可以继续用;真正禁止的是 `android.*` / `org.lwjgl.*` / `java.awt.*` / `javax.swing.*`。
- 现有 hook 模式:`core/.../ShatteredPixelDungeon.java` 构造函数注入 `PlatformSupport`(`SPD-classes/.../utils/PlatformSupport.java` 是抽象基类,`AndroidPlatformSupport` / `DesktopPlatformSupport` 是实现)。**复用这个模式注入 SaveSlotBridge** —— 但接口本身必须放在 `SPD-classes`,不能放 `core`,否则 `SPD-classes -> core` 反向依赖,编译会挂。

## Architecture (rev 2)

```
SPD-classes                            core                              android/desktop
─────────────                          ────                              ──────────────
PlatformSupport.java                   SaveSlotService.java              AndroidSaveSlotBridge
  + default saveSlotBridge() ────────▶ bridge = platform.saveSlotBridge()  implements SaveSlotBridge
        returns SaveSlotBridge              IO_LOCK                          (ActionResolve / SAF)
                                            exportToStream(...)              
com.watabou.utils.SaveSlotBridge       importFromStream(...)              DesktopSaveSlotBridge
  interface                             commitImport(...)                   (JFileChooser)
   - exportSlot(name, cb)               uses SaveSlotIO                    
   - importSlot(cb)                                                       
   - available()                       SaveSlotIO.java                    
                                       (zip pack/unpack, path-traversal   
                                        defense, atomic staging)          
                                       
                                       WndSaveSlotSelect.java            
                                       enum Mode { SAVE, LOAD, DEATH_LOAD }
```

注入链:
- `AndroidLauncher.onCreate` 已经 `new ShatteredPixelDungeon(support)` 把 `AndroidPlatformSupport` 传进去。
- `ShatteredPixelDungeon` 构造函数里调一次 `SaveSlotService.setBridge(platform.saveSlotBridge())`。
- `AndroidPlatformSupport.saveSlotBridge()` lazy `new AndroidSaveSlotBridge(AndroidLauncher.instance)`。
- `DesktopPlatformSupport.saveSlotBridge()` lazy `new DesktopSaveSlotBridge()`,或返回 null。
- iOS 默认走 `PlatformSupport.saveSlotBridge()` 的 default → null,UI 自动隐藏按钮。

## Files

### 新增(SPD-classes,平台无关接口)

- `SPD-classes/src/main/java/com/watabou/utils/SaveSlotBridge.java` — 平台抽象接口
  ```java
  package com.watabou.utils;

  public interface SaveSlotBridge {
      void exportSlot(String slotName, ExportCallback cb);
      void importSlot(ImportCallback cb);
      boolean available();

      interface ExportCallback {
          void onComplete(boolean ok, String message);
      }
      interface ImportCallback {
          void onComplete(boolean ok, String importedName, String message);
      }
  }
  ```
  **关键**:回调签名只用 Java 基本类型 + String,不能引用 `core` 类型(否则反向依赖)。`importedName` 由平台层决定 —— Android 从 `OpenableColumns.DISPLAY_NAME` 去后缀或 zip 内 `meta.bundle` 的 `name` 字段取,desktop 从用户输入的文件名取。

### 改动(SPD-classes)

- `SPD-classes/.../utils/PlatformSupport.java` 加 default 方法:
  ```java
  public SaveSlotBridge saveSlotBridge() { return null; }
  ```
  default 返回 null,UI 自动隐藏 export/import 按钮。**这是本次唯一一处上游文件改动**。

### 新增(core 纯逻辑)

- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/saveslot/SaveSlotIO.java` — zip 打包/解包
  - 允许 import `com.badlogic.gdx.files.FileHandle` / `com.watabou.utils.FileUtils` / `com.watabou.utils.Bundle`(这些都已在 core 现有依赖里);**禁止** import `android.*` / `org.lwjgl.*` / `java.awt.*` / `javax.swing.*`。
  - `void writeSlotToStream(String slotName, OutputStream out)` — 列 `save_slots/{name}/` 下所有 root-level 文件(不递归子目录,与 `FileUtils.copyDir` 一致);把 `meta.bundle` 强制放第一个 entry,后续 entry 按字母序。
  - `SlotImportResult readSlotFromStream(InputStream in, String suggestedName)` — 解 zip 到唯一 staging 目录,做版本/路径/数量/大小校验,返回结果对象(包含 staging dir 相对路径 + meta + conflict 标志)。
  - `void commitImport(String stagingRelPath, String finalName, boolean overwrite)` — 把 staging 目录原子搬到 `save_slots/{finalName}/`,如果覆盖则先把旧目录 rename 到 `.bak` 再 rename 新目录,失败时回滚。
  - `void cleanupStaging(String stagingRelPath)` — 取消/失败时调,删 staging 目录。
  - `static void cleanupLeftovers()` — 启动时调,扫 `save_slots/` 下所有 `.*-import-*` / `*.tmp` / `*.bak` 残留并清理。

- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/saveslot/SlotImportResult.java` — 不可变结果对象
  - `boolean ok` / `String name` / `SaveSlotMeta meta` / `String stagingRelPath` / `boolean conflict` / `String message`

### 改动(core)

- `core/.../saveslot/SaveSlotService.java`
  - 加 `private static final Object IO_LOCK = new Object();` —— `saveToSlot / loadFromSlot / deleteSlot / exportToStream / commitImport / cleanupLeftovers` 全部 `synchronized(IO_LOCK)`,防止 export 中途 slot 被删/被覆盖、import commit 与 save 撞车。
  - 加 `private static SaveSlotBridge bridge;` + `static void setBridge(SaveSlotBridge)` + `static SaveSlotBridge getBridge()`。
  - 新增 `exportToStream(String name, OutputStream)`:`synchronized(IO_LOCK)`,检查 `isValidName` + `isSaveAllowed` + `slotExists`,委托 `SaveSlotIO.writeSlotToStream`。
  - 新增 `importFromStream(InputStream, String suggestedName)`:`synchronized(IO_LOCK)`,检查 `isSaveAllowed`,委托 `SaveSlotIO.readSlotFromStream`,返回 `SlotImportResult`(**不在这一步覆盖已存在的 slot**,UI 层决定)。
  - 新增 `commitImport(SlotImportResult, String finalName, boolean overwrite)`:`synchronized(IO_LOCK)`,委托 `SaveSlotIO.commitImport`。
  - 新增 `cancelImport(SlotImportResult)`:`synchronized(IO_LOCK)`,委托 `SaveSlotIO.cleanupStaging`。
  - 在 `ShatteredPixelDungeon` 构造函数(已有 `platformSupport` 字段)末尾调一次 `SaveSlotService.setBridge(platformSupport.saveSlotBridge())` + `SaveSlotService.cleanupLeftovers()`。
  - 入口检查 `isSaveAllowed()`(daily 禁用)。

- `core/.../saveslot/WndSaveSlotSelect.java`
  - 把 `boolean saving` 改成 `enum Mode { SAVE, LOAD, DEATH_LOAD }`。`DEATH_LOAD` 用于死亡读档(`interceptDeath` 调用时传),`LOAD` 用于菜单"Load from Slot",`SAVE` 用于菜单"Save to Slot"。
  - **Export 按钮**:每个 `SlotRow` 在 SAVE 和 LOAD 模式下显示(`DEATH_LOAD` 不显示)。
  - **Import 按钮**:底部,只在 SAVE 模式显示(`LOAD` 模式不显示,因为 import 等同新建 slot)。
  - `bridge == null || !bridge.available()` 时两个按钮都不渲染。
  - 文案继续走 `TXT_ZH` / `TXT_EN`,**不**回到 properties。

### 新增(android)

- `android/src/main/java/com/shatteredpixel/shatteredpixeldungeon/android/AndroidSaveSlotBridge.java` — 实现 `SaveSlotBridge`
  - 持有 `AndroidLauncher` 引用(构造时传 `AndroidLauncher.instance`,fallback 用静态 `instance`)。
  - `exportSlot(slotName, cb)`:
    1. 构造 `Intent.ACTION_CREATE_DOCUMENT`,MIME `application/zip`,`EXTRA_TITLE = "{slotName}.zip"`,加 `CATEGORY_OPENABLE`。
    2. `activity.startActivityForResult(intent, REQUEST_EXPORT)` —— 用 ActivityResult 回调表(注册 `Map<Integer, Consumer<Intent>>`)。
    3. 回调线程(主线程):用户取消(`resultCode != RESULT_OK || data == null || data.getData() == null`)→ `Game.runOnRenderThread(() -> cb.onComplete(false, "cancelled"))`,**不**调 `reportException`。
    4. 用户选中 → 起 worker thread:`try (OutputStream os = activity.getContentResolver().openOutputStream(uri)) { SaveSlotService.exportToStream(slotName, os); }` —— try-with-resources 保证关闭。
    5. worker 完成后 `Game.runOnRenderThread(() -> cb.onComplete(ok, msg))`。
    6. `ActivityNotFoundException`(老 ROM 没 SAF)→ `Game.runOnRenderThread(() -> cb.onComplete(false, "saf_unavailable"))`。
  - `importSlot(cb)`:
    1. `Intent.ACTION_OPEN_DOCUMENT`,MIME `application/zip`,`CATEGORY_OPENABLE`。
    2. `startActivityForResult`,注册回调。
    3. 回调线程:用户取消 → `cb.onComplete(false, "cancelled")`。
    4. 用户选中 → **worker thread 第一阶段**:
       - 从 `ContentResolver.query(uri, [OpenableColumns.DISPLAY_NAME])` 拿 suggestedName,去 `.zip` 后缀,过 `SaveSlotService.isValidName`,失败 fallback `"imported"`。
       - `try (InputStream is = contentResolver.openInputStream(uri)) { result = SaveSlotService.importFromStream(is, suggestedName); }`
       - 如果 `!result.ok` → `Game.runOnRenderThread(() -> cb.onComplete(false, null, result.message))`。
    5. **render thread 第二阶段**(UI 决策):
       - 如果 `result.conflict` → 弹 `WndOptions` 问覆盖/重命名/取消。
       - 否则直接 `commitImport(result, result.name, false)`。
       - 用户选取消 → `SaveSlotService.cancelImport(result)`,cb 回 false。
    6. **worker thread 第三阶段**:执行 `SaveSlotService.commitImport(...)`,完成后 `Game.runOnRenderThread(() -> cb.onComplete(ok, finalName, msg))`。
- `android/.../AndroidLauncher.java`
  - 加 `private final Map<Integer, ActivityResultHandler> activityResultCallbacks = new HashMap<>();`,其中 `interface ActivityResultHandler { void onResult(int resultCode, Intent data); }`(用 BiConsumer<Integer, Intent> 等价)。
  - 加 `public void registerActivityResult(int requestCode, ActivityResultHandler cb)` —— bridge 启动选择器前注册。
  - override `onActivityResult(requestCode, resultCode, data)`:查表调对应 cb,执行完 remove。
  - **不在 `onCreate` 里直接 `SaveSlotService.setBridge`** —— 注入由 `ShatteredPixelDungeon` 构造函数走 `platformSupport.saveSlotBridge()` 统一完成。

### 新增(desktop)

- `desktop/src/main/java/com/shatteredpixel/shatteredpixeldungeon/desktop/DesktopSaveSlotBridge.java`
  - `available()` 返回 true。
  - `exportSlot(slotName, cb)`:`new Thread(() -> { JFileChooser chooser = ...; showSaveDialog(...); if (approved) { try (OutputStream os = new FileOutputStream(chooser.getSelectedFile())) { SaveSlotService.exportToSlot(...); } } Game.runOnRenderThread(...); }).start();`
  - `importSlot(cb)`:类似,用 `showOpenDialog`,选完文件后走 worker → render 冲突确认 → worker commit 流程,与 Android 对称。
- `desktop/.../DesktopPlatformSupport.java` override `saveSlotBridge()` 返回 `new DesktopSaveSlotBridge()`。

**iOS 不在范围内** —— `IOSPlatformSupport` 不 override,默认 `saveSlotBridge()` 返回 null,UI 自动隐藏。

### 不改动

- `Hero.java`、`WndGame.java`、`FileUtils.java`(已有 `copyDir`,本次不动)等上游 hook 文件 — 本次完全不碰,死亡读档链路保持原状。
- `CLAUDE.md` —— 视实施结果决定是否补一句 export/import 已实现。

## Steps

### Step 1: core 纯逻辑 — `SaveSlotIO`

先写 zip 打包/解包。允许 import `com.badlogic.gdx.files.FileHandle` / `com.watabou.utils.FileUtils` / `com.watabou.utils.Bundle`,**禁止** import `android|org.lwjgl|java.awt|javax.swing`。

- `writeSlotToStream(name, out)`:
  1. `FileUtils.filesInDir("save_slots/" + name)` 列出 root-level 文件名(已是扁平目录)。
  2. 强制把 `meta.bundle` 排第一个 entry(便于 import 早判版本)。
  3. `ZipOutputStream zos = new ZipOutputStream(out)`,buffer 16KB。
  4. 对每个文件:`ZipEntry entry = new ZipEntry(filename)`(纯文件名,无目录前缀),`entry.setSize(file.length())`,`zos.putNextEntry(entry)`,把 `FileHandle.read()` 流式拷到 zos,`closeEntry()`。
  5. 不写目录 entry。
  6. `zos.finish()`(不关底层 stream,由调用方管)。

- `readSlotFromStream(in, suggestedName)`:
  1. 创建 staging 目录:`save_slots/.import-{uuid}/`(用 `java.util.UUID.randomUUID()`,不用 timestamp,避免碰撞)。
  2. `ZipInputStream zis = new ZipInputStream(in)`,buffer 16KB。
  3. **硬规则**:对每个 entry,先取 `entry.getName()`:
     - reject 空 / `.` / `..`
     - reject 含 `/`、`\\`、`:`(Windows drive)
     - reject 以任何上述分隔符开头或结尾
     - reject directory entry(`entry.isDirectory()`)
     - reject 名字已在本次 zip 出现过(防重复)
     - **白名单校验**:`name.matches("[A-Za-z0-9_.\\-]+")` 且不等于 `.` 或 `..`(不使用 `java.nio.file.Paths`,minSdk 21 兼容)
     - **任何违反 → `cleanupStaging` + 返回 `SlotImportResult(ok=false, message="invalid_zip_entry")`**
  4. 每个 entry 写到 `staging/{name}`,使用 `FileHandle.child(name).write(false)`。
  5. **数量上限 64**(slot 一般 ≤ 10 个文件),**累计解压大小上限 64MB**(防 zip bomb)。
  6. 解压完读 `staging/meta.bundle`,校验:
     - `meta.bundle` 必须存在
     - `meta.version == Game.versionCode`,不一致 → cleanup + 返回 `ok=false, message="version_mismatch"`
     - 读出 `meta.name`,与 `suggestedName` 比对:`final suggestedName = isValidName(suggestedName) ? suggestedName : (isValidName(meta.name) ? meta.name : "imported")`
  7. `conflict = slotExists(suggestedName)`
  8. 返回 `SlotImportResult(ok=true, name=suggestedName, meta=meta, stagingRelPath="save_slots/.import-{uuid}", conflict=conflict, message=null)`。

- `commitImport(stagingRelPath, finalName, overwrite)`:
  1. **必须持 `SaveSlotService.IO_LOCK`**(由 caller 保证)。
  2. 校验 `isValidName(finalName)`、staging dir 存在、staging/meta.bundle 可读、版本仍匹配。
  3. 目标 `dst = "save_slots/" + finalName`。
  4. 如果 `dst` 存在:
     - `overwrite == false` → throw / 返回失败(理论上 UI 先确认过,但 service 层防御性检查)
     - `overwrite == true` → `bak = dst + ".bak"; if (bak exists) deleteDir(bak); rename(dst, bak)`(用 `FileHandle.rename`,记录是否成功)
  5. `tmp = dst + ".tmp"; if (tmp exists) deleteDir(tmp); rename(staging, tmp)`
  6. `rename(tmp, dst)`
  7. 如果上一步失败 + 之前 rename 过 bak:尝试 `rename(bak, dst)` 恢复。
  8. 成功后 `deleteDir(bak)`、`deleteDir(staging)`(如果还残留)。
  9. 返回 ok/fail。

- `cleanupStaging(stagingRelPath)`:`FileUtils.deleteDir(stagingRelPath)`,失败不抛。
- `cleanupLeftovers()`:扫 `save_slots/` 下所有名字匹配 `^\.(import|tmp)-` 或结尾 `.tmp` / `.bak` 的 entry,逐个 `deleteDir`。`SaveSlotService.cleanupLeftovers()` 在 `ShatteredPixelDungeon` 构造结束时调一次。

**自检**:`./gradlew :core:compileJava` 通过;手工写一个 main 测试 round-trip + path-traversal 拒绝 + zip bomb 拒绝。

### Step 2: SPD-classes 平台抽象 — `SaveSlotBridge` 接口 + `PlatformSupport` 注入点

- 新建 `SPD-classes/.../utils/SaveSlotBridge.java`(只 Java 类型,无 core 依赖)。
- `PlatformSupport` 加 `public SaveSlotBridge saveSlotBridge() { return null; }`。
- `core/.../SaveSlotService.java` 加 `bridge` 静态字段 + setter/getter。
- `core/.../ShatteredPixelDungeon.java` 构造函数末尾(已有 `this.platformSupport = platformSupport;` 之后)加:
  ```java
  SaveSlotService.setBridge(platformSupport.saveSlotBridge());
  SaveSlotService.cleanupLeftovers();
  ```

### Step 3: core 改 `SaveSlotService` 加 export/import 入口

按 Architecture 段实现。所有 IO 方法 `synchronized(IO_LOCK)`。

### Step 4: core 改 `WndSaveSlotSelect` 加 UI 按钮

- 改构造签名为 `WndSaveSlotSelect(Mode mode)` + `WndSaveSlotSelect(Mode mode, Runnable onCancel)`。保留两个 boolean 重载为 deprecated 转 `Mode.SAVE`/`Mode.LOAD`,或一次性把所有 caller 改成 Mode —— 一次性改,callers 只有 `SaveSlotService.addMenuButtons`(两处)和 `SaveSlotService.interceptDeath`(一处,传 `Mode.DEATH_LOAD`)。
- 每个 `SlotRow` 加 Export `IconButton`(用 `Icons.get(Icons.UNLOCK)` 或 `Icons.get(Icons.DEPTH)`,**不新增 icon 资源**)。`mode == DEATH_LOAD` 时整行不显示 Export 按钮。
- 底部加 Import `RedButton`(label 走 TXT_ZH/TXT_EN),`mode == SAVE && bridge != null && bridge.available()` 时才显示。
- Export 按钮点击:`bridge.exportSlot(meta.name, cb)`,cb 在 render thread 回 → 弹 `WndMessage` 提示成功/失败 → 禁用按钮直到 cb 回来(防双击)。
- Import 按钮点击:`bridge.importSlot(cb)`,cb 回 `ok=true` 时 → `hide()` + 弹 `WndMessage(txt("imported", importedName))`。`ok=false` 时弹错误。
- 新增 `TXT_ZH/TXT_EN` 键:`btn_export` / `btn_import` / `imported` / `exported` / `export_failed` / `import_failed` / `version_mismatch` / `invalid_zip` / `name_conflict_title` / `name_conflict_body` / `btn_rename` / `btn_overwrite` / `btn_cancel_import`。

### Step 5: android 实现 — `AndroidSaveSlotBridge` + Activity 结果路由

按 Architecture 段实现。**所有 stream 用 try-with-resources**。**取消选择不 report exception**。

**风险记录**:`startActivityForResult` 在 API 30+ 标 deprecated 但仍工作。如果未来切到 `registerForActivityResult`,bridge 内部改即可,接口不变。

### Step 6: desktop 实现

按 Architecture 段实现。JFileChooser 在 `new Thread()` 里跑,完成后 `Game.runOnRenderThread`。冲突确认用 `WndOptions`(在 render thread 弹)。

### Step 7: 集成测试

- `./gradlew :core:compileJava`
- `./gradlew :android:assembleDebug`
- `adb -s 20210119085654 install -r android/build/outputs/apk/debug/android-debug.apk`
- 测试矩阵(每条都跑一遍):
  1. 创建一个 slot → export → 在 Files 应用里看到 zip(可分享)
  2. 删除 slot → import 同一个 zip → 恢复成功,depth/level/hero_class 一致
  3. **修改 zip 内的 `meta.bundle` version 字段 → 导入被拒,提示 version_mismatch,staging 自动清理**
  4. daily run 下 Export/Import 按钮不可见(菜单 load/save 按钮已隐藏,验证 export/import 跟随隐藏)
  5. import 名字冲突 → 弹 `WndOptions`(覆盖/重命名/取消) → 选覆盖后旧 slot 被新内容替换,**旧目录 rename 到 .bak 再 rename 新目录进位**
  6. import 时断电 / 杀进程 → 临时目录残留不污染主 slot 列表(下次启动 `cleanupLeftovers` 清掉 `.import-*` / `*.tmp` / `*.bak`)
  7. **路径穿越测试**:手工构造一个 zip,里面含 `../evil.txt` 和 `C:\evil.txt` 和 `subdir/file.txt` → import 拒绝,提示 invalid_zip
  8. **zip bomb 测试**:构造一个含 1000 个 entry 或解压 > 64MB 的 zip → import 拒绝
  9. **死亡读档链路不受影响**:触发死亡 → 弹 slot 选择窗口(DEATH_LOAD 模式) → 验证 SlotRow 没有 Export 按钮、底部没有 Import 按钮
  10. **并发测试**:export 进行中(用大 slot)点击 delete slot → 不会出现 zip 半新半旧(IO_LOCK 保证)

## Acceptance

| # | 验收点 | 验证方法 |
|---|---|---|
| 1 | core 模块编译无 android/desktop/AWT/Swing import | `grep -rnE "^import (android|org.lwjgl|java.awt|javax.swing)" core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/saveslot/` 应为空 |
| 2 | SPD-classes 不依赖 core | `grep -rn "com.shatteredpixel" SPD-classes/src/main/java/com/watabou/utils/SaveSlotBridge.java SPD-classes/src/main/java/com/watabou/utils/PlatformSupport.java` 应为空(SaveSlotBridge 接口完全 Java-only) |
| 3 | Android 真机能 export slot 到 Downloads | adb 测试矩阵 #1 |
| 4 | 能从 zip 导入并恢复 | 测试矩阵 #2 |
| 5 | 版本不匹配拒绝导入 | 测试矩阵 #3 |
| 6 | daily run 下按钮不可见 | 测试矩阵 #4 |
| 7 | 名字冲突有确认流程 + 覆盖时旧 slot 不丢 | 测试矩阵 #5 |
| 8 | import 失败不污染现有 slot | 测试矩阵 #6 |
| 9 | zip 路径穿越 / zip bomb 防御 | 测试矩阵 #7 + #8 |
| 10 | 死亡读档链路完全不受影响 | 测试矩阵 #9 |
| 11 | 上游既有文件改动面 = 1(只 `PlatformSupport.java` 加 default 方法;新增 `SaveSlotBridge.java` 不算上游既有文件改动) | `git diff master...feature/save-slot-export --stat -- SPD-classes/src/main/java/com/watabou/utils/PlatformSupport.java` 仅 1 个文件;新增 `SPD-classes/.../SaveSlotBridge.java` 允许 |
| 12 | fork 代码全部在 `saveslot/` 子包 + `android/`/`desktop/` 模块内 | 见上 Files 段 |
| 13 | 并发安全 | 测试矩阵 #10 |
| 14 | 启动时清理 staging/tmp/bak 残留 | 测试矩阵 #6 后续启动 |

## 风险与备选

- **SAF 在某些 MIUI 版本上行为不一致**:测试机 TYH201H 是 MIUI,需要真机验证。如果 `ACTION_CREATE_DOCUMENT` 弹不出来或 `ActivityNotFoundException`,fallback 选项是 bridge 内部 catch 后返回 `cb.onComplete(false, "saf_unavailable")`,UI 提示用户手动复制 `save_slots/` 目录。**不**走 `FileHandle.external()` 直接写,因为 targetSdk 36 下 Android 11+ 限制更严。
- **zip 解压路径穿越**:`SaveSlotIO.readSlotFromStream` 强制 root-level、reject `/\\:.`,normalize 校验,数量 64 上限,大小 64MB 上限。
- **import commit 原子性**:用 `.bak` + `.tmp` 双 rename,失败时尝试 restore bak。
- **大文件阻塞**:game.bundle 可能几 MB,buffer ≥ 16KB,UI 层 export/import 时禁用按钮防止双击。
- **desktop JFileChooser 阻塞**:必须在 worker thread,完成后切回 render thread。

## Out of scope

- 批量打包所有 slot 成一个 zip
- zip 内嵌 screenshot(目前 slot 不存截图,需要额外生成)
- 加密 / 校验和(zip 损坏检测交给 `ZipInputStream` 自带的 CRC 错误)
- iOS 实现
- 自动云备份(走 SPD 自身的 news/updates 服务会引入新依赖,不做)

## Pending Issues (reviewer round 1 已处理的 must/should-fix,留待实施验证)

无 —— round 1 所有 must-fix 和 should-fix 已落到上面 Architecture / Files / Steps / Acceptance 中。
