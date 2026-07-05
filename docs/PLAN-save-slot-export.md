# PLAN: Save Slot Export / Import

**Slug**: `save-slot-export`
**Branch**: `feature/save-slot-export` (based on `feature/local-save-slots`)
**Date**: 2026-06-30 (rev 5 — tightened headless test fixture state management, parameterized security cases, locked down `copySlotToCurrentGame` semantics per reviewer round 1)

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

### 新增(core 测试)

- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/saveslot/SaveSlotIOHeadlessTest.java` — 无头 JUnit 测试,覆盖 slot 保存文件夹的 zip export/import 纯逻辑
  - 用 `new HeadlessApplication(new ApplicationAdapter(){}, config)` 初始化 `Gdx.files` / `Gdx.app`(传空 `ApplicationAdapter`,**不**传真实 `Game` 实例,避免走图形/纹理初始化)。`HeadlessApplicationConfiguration` 设最小配置。
  - 每个 test 使用独立临时目录作为 libgdx local storage:`@Before` 调 `FileUtils.setDefaultFileProperties(Files.FileType.Local, tmpDir.getAbsolutePath() + "/")`,`@After` 删除 tmp 并恢复为 `Files.FileType.Local, ""`。`FileUtils` 本身无 FileHandle cache,但 default root 是 static 字段,必须显式恢复。
  - 不启动完整 `ShatteredPixelDungeon` 场景,不创建真实 UI,只测试 `SaveSlotIO` / `SaveSlotService` 的文件 IO 与 Bundle 逻辑。测试不依赖真实 render loop。
  - 构造最小 slot 目录:`save_slots/{name}/meta.bundle` + 若干 dummy bundle/data 文件;`meta.bundle` 用 `FileUtils.bundleToFile` 写真实 `Bundle`,版本号用 `Game.versionCode`(`@BeforeClass` 从 `System.getProperty("spd.appVersionCode")` 读取,回退 `appVersionCode` 即 896)。
  - **全局状态 save/restore**(`@BeforeClass` / `@AfterClass`):
    - `Game.versionCode`: save old → set test value → restore on teardown
    - `FileUtils` default root(type+path): `@Before` set per-test tmpDir → `@After` restore to `Local, ""`
    - `Dungeon.daily` / `Dungeon.dailyReplay`: 每个 test 用 try/finally 恢复
    - `GamesInProgress.curSlot`: 每个 test 用 try/finally 恢复;涉及 `slotStates` 缓存的 test 调用 `GamesInProgress.setUnknown(slot)` 清除缓存
  - **测试项**(每项独立 zip,不合并):
    1. `export_then_import_round_trip_preserves_slot_files_and_meta` — 构造 slot → `SaveSlotService.exportToStream` → 删除 slot → `importFromStream` + `commitImport` → 文件列表与 meta(depth/level/hero_class/version/name)一致。
    2. `import_rejects_version_mismatch_and_cleans_staging` — zip 内 meta.version 改成 `Game.versionCode - 1`,断言 `ok=false,message=version_mismatch`,staging 目录不存在。
    3. **path traversal 参数化**(4 个独立 zip,分别测):
       - `import_rejects_dotdot_path` — `../evil.txt`
       - `import_rejects_subdir_path` — `subdir/file`
       - `import_rejects_windows_drive_path` — `C:\\evil.txt`
       - `import_rejects_colon_in_name` — `evil:file.txt`
       - 每个都断言 `ok=false, message=invalid_zip_entry` 且 staging 不残留。
    4. **zip bomb 拆两个独立用例**:
       - `import_rejects_too_many_entries` — zip 含 65 个合法 entry(> MAX_ENTRY_COUNT=64),断言 `message=too_many_entries`,staging 清理。
       - `import_rejects_total_bytes_exceeded` — zip 含累计解压 > 64MB 的 entry(用 1 个大 entry 即可,例如 65MB),断言 `message=zip_too_large`,staging 清理。
    5. `commit_import_without_overwrite_refuses_existing_slot` — 已存在同名 slot 且 overwrite=false 时 commit 失败,原 slot 内容不变,staging 仍存在(供 caller cancel/retry)。
    6. `commit_import_overwrite_restores_or_replaces_atomically` — overwrite=true 成功后旧 slot 被替换,`.bak/.tmp/.import-*` 无残留,无 `.import-complete` marker 残留。
    7. `cleanup_leftovers_removes_staging_tmp_bak_but_not_real_slots` — 构造残留目录(`.import-{uuid}` / `name.tmp` / `name.bak` + 对应或不对应 live slot),调用 cleanup 后只保留合法 slot;marker 存在的 live slot 配套 .bak 被清,marker 缺失的 live slot 被 .bak 恢复。
    8. `import_rejects_missing_meta_bundle` — zip 不含 `meta.bundle`,断言 `message=missing_meta`,staging 清理。
    9. `import_rejects_empty_zip` — 零 entry 的 zip,等价于 missing meta 但路径不同,断言 `message=missing_meta`,staging 清理。
    10. `import_rejects_duplicate_entries` — 同名 entry 出现两次,第二次必须被拒(`message=invalid_zip_entry`),staging 清理。
    11. `import_rejects_corrupted_meta_bundle` — `meta.bundle` 是垃圾字节(非合法 bundle 格式),断言 `message=meta_read_failed`,staging 清理。
    12. `commit_import_with_missing_staging_returns_false` — staging 路径不存在或已被清掉,`commitImport` 返回 false,现有 slot 不变(轻量退化用例)。
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/saveslot/SaveSlotServiceHeadlessTest.java` — 无头 JUnit 测试,覆盖 service 层 guard 与当前存档 save/load 拷贝
  - 用 dummy `GamesInProgress.curSlot` 与 `GamesInProgress.gameFolder(curSlot)` 构造当前游戏目录,不创建真实 `Dungeon.hero` 时只测试 `loadFromSlot` 之前可独立验证的低层路径。
  - 抽 package-private helper `static void copySlotToCurrentGame(String name) throws IOException`,helper 自身 `synchronized (IO_LOCK)`(Java monitor 可重入,`loadFromSlot` 已持锁时调用不死锁)。helper 校验 `isValidName`、slot 存在,只做 `deleteDir(dst)` + `copyDir(src, dst)`,失败抛 `IOException`;**不**做 scene switch / `WndResurrect.instance` / UI 行为。`loadFromSlot` 保留 daily/meta/version 校验、`GamesInProgress.setUnknown`、`WndResurrect.instance=null`、scene switch,但 copy 部分改调 helper。
  - 测试项:
    1. `export_requires_valid_existing_slot` — 无效名称抛 `IllegalArgumentException`,不存在 slot 抛 `IOException`,并恢复 `Dungeon.daily` / `Game.versionCode` / tmp root。
    2. `daily_disables_export_import` — 临时设置 `Dungeon.daily=true` 和 `Dungeon.dailyReplay=true`(分别覆盖),断言 `exportToStream` 抛 `IllegalStateException` / `importFromStream` 返回 `ok=false,message=daily_disabled`,finally 恢复静态状态。
    3. `load_from_imported_slot_copies_into_current_game_folder` — 构造 slot folder 与 current `game{curSlot}` folder,调用 helper,断言 current game folder 被替换、slot 根与 `game{n}` 根仍隔离,并对该 slot 调 `GamesInProgress.setUnknown(curSlot)` 避免 `slotStates` 缓存跨 tmp root。
- `core/build.gradle` — 添加 test 依赖与任务配置
  ```gradle
  dependencies {
      testImplementation 'junit:junit:4.13.2'
      testImplementation "com.badlogicgames.gdx:gdx-backend-headless:$gdxVersion"
      testImplementation "com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop"
  }

  test {
      useJUnit()
      systemProperty 'java.awt.headless', 'true'
      systemProperty 'spd.appVersionCode', appVersionCode
  }
  ```
  如果根项目已有版本变量名不是 `gdxVersion`,改用现有 libgdx 版本变量(项目事实:1.14.0),不要硬编码第二套版本号。

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

### Step 7: 无头 JUnit 测试(save/load + import/export)

在手测前先补 headless JUnit,目标是把**不依赖 Android SAF UI**的核心行为全部固定下来。

- 配置 `core/build.gradle`:
  1. 增加 JUnit4、`gdx-backend-headless`、desktop natives test 依赖。
  2. `test { useJUnit(); systemProperty 'java.awt.headless', 'true'; systemProperty 'spd.appVersionCode', appVersionCode }`。
  3. 复用项目已有 libgdx 版本变量;如果没有公开变量,从现有依赖声明读取 1.14.0,不要引入不一致版本。
- 测试 fixture:
  1. `@BeforeClass` 启动一次 `new HeadlessApplication(new ApplicationAdapter(){}, config)`,确保 `Gdx.files` / `Gdx.app` 可用;测试不依赖真实 render loop,不传真实 `Game`。
  2. `@BeforeClass` 保存旧 `Game.versionCode`,再从 `System.getProperty("spd.appVersionCode")` 设置测试 version;`@AfterClass` 恢复旧值并 `application.exit()`。
  3. 每个 test 使用独立临时 local root:`@Before` 设置 `FileUtils.setDefaultFileProperties(Files.FileType.Local, tmpDir + "/")`,`@After` 删除 tmp 并恢复 `Local, ""`,避免污染真实用户存档。
  4. 用真实 `Bundle` + `FileUtils.bundleToFile` 写 `meta.bundle`,不要手写二进制格式。
  5. 静态全局状态(`Dungeon.daily`, `Dungeon.dailyReplay`, `GamesInProgress.curSlot`)必须在 `try/finally` 或 `@After` 恢复;涉及 `GamesInProgress.check/load copy` 的测试调用 `GamesInProgress.setUnknown(slot)` 清缓存。
  6. 当前 Gradle/JUnit 默认串行执行;若未来开启并行,这一组测试必须禁用并行或加资源锁,因为它接管 `FileUtils` / `Game.versionCode` / `Dungeon` 全局状态。
- 必测用例:
  1. round-trip: `save_slots/a` → `exportToStream` → 删除原 slot → `importFromStream` → `commitImport` → meta 和文件内容一致。
  2. service save/load copy:构造 current game folder + slot folder,验证 package-private `copySlotToCurrentGame` 会替换 current game folder,且 slot 根与 `game{n}` 根仍隔离;helper 自身 `synchronized(IO_LOCK)` 并只做 copy,不做 scene switch/UI。
  3. version mismatch:导入被拒 + staging 清理。
  4. path traversal:分别拒绝 `../evil.txt`、`subdir/file`、`C:\evil.txt`、`evil:file.txt`(独立 zip,避免只测到第一个非法 entry)。
  5. zip bomb:分别覆盖超过 64 entries 和超过 64MB 解压上限。
  6. conflict/overwrite:false 拒绝覆盖,overwrite:true 替换且无 `.bak/.tmp/.import-*` / `.import-complete` 残留。
  7. cleanup leftovers:只清理 staging/tmp/bak,不删合法 slot;同时覆盖 marker-present 删除 bak、marker-missing 恢复 bak 两条路径。
  8. daily guard:daily/dailyReplay 下 export/import 都被拒。
  9. malformed zip inputs:empty zip / missing meta / duplicate entries / corrupted meta.bundle 都拒绝并清理 staging。
  10. commit missing staging:返回 false 且不改已有 slot。
- 运行命令:
  ```bash
  ./gradlew :core:test --tests '*SaveSlot*HeadlessTest'
  ./gradlew :core:compileJava :android:compileDebugJavaWithJavac :desktop:compileJava
  ```

### Step 8: 集成测试

- `./gradlew :core:test --tests '*SaveSlot*HeadlessTest'`
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
| 3 | 无头 JUnit 覆盖 zip export/import round-trip | `./gradlew :core:test --tests '*SaveSlot*HeadlessTest'`,用例 `export_then_import_round_trip_preserves_slot_files_and_meta` 通过 |
| 4 | 无头 JUnit 覆盖当前存档 save/load copy 与 slot/game 目录隔离 | `SaveSlotServiceHeadlessTest.load_from_imported_slot_copies_into_current_game_folder` 或等价 copy helper 测试通过 |
| 5 | 无头 JUnit 覆盖版本不匹配、路径穿越、zip bomb、cleanup leftovers | 对应用例全部通过,且临时目录无残留 |
| 6 | 无头 JUnit 覆盖 conflict/overwrite 与 daily guard | overwrite=false/true 和 daily/dailyReplay 用例全部通过 |
| 7 | Android 真机能 export slot 到 Downloads | adb 测试矩阵 #1 |
| 8 | 能从 zip 导入并恢复 | 测试矩阵 #2 |
| 9 | 版本不匹配拒绝导入 | 测试矩阵 #3(手测) + JUnit version mismatch |
| 10 | daily run 下按钮不可见 | 测试矩阵 #4(手测) + JUnit daily guard |
| 11 | 名字冲突有确认流程 + 覆盖时旧 slot 不丢 | 测试矩阵 #5(手测) + JUnit overwrite 用例 |
| 12 | import 失败不污染现有 slot | 测试矩阵 #6(手测) + JUnit cleanup 用例 |
| 13 | zip 路径穿越 / zip bomb 防御 | 测试矩阵 #7 + #8(手测/构造包) + JUnit 用例 |
| 14 | 死亡读档链路完全不受影响 | 测试矩阵 #9 |
| 15 | 上游既有文件改动面 = 1(只 `PlatformSupport.java` 加 default 方法;新增 `SaveSlotBridge.java` 不算上游既有文件改动) | `git diff master...feature/save-slot-export --stat -- SPD-classes/src/main/java/com/watabou/utils/PlatformSupport.java` 仅 1 个文件;新增 `SPD-classes/.../SaveSlotBridge.java` 允许 |
| 16 | fork 代码全部在 `saveslot/` 子包 + `android/`/`desktop/` 模块内 | 见上 Files 段 |
| 17 | 并发安全 | 测试矩阵 #10 |
| 18 | 启动时清理 staging/tmp/bak 残留 | 测试矩阵 #6 后续启动 + JUnit cleanup leftovers |

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
