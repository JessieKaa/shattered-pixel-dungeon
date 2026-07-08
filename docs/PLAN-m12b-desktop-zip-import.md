# PLAN: M12b — mod zip import(共享 core + desktop picker + UI)

## Goal
玩家从外部 zip 文件导入 mod:点 WndModManager 的 "Import Mod" 按钮 → 平台文件选择器选 `.zip` → 解压到 `mods_user/<id>/` + 校验 `mod.json` → 重启生效。本 feature 交付**平台无关的共享 core**(`ModInstaller` 解压+校验 + `ModImporter` 平台 seam interface + UI 按钮),并实现 **desktop 平台**(AWT `FileDialog`)。为 M12c(Android SAF)铺好 seam —— M12c 只加 android 模块的 SAF impl + 注册,**与 M12b 零冲突**。

## Context
- M12a 已落地 `mods_user/`(`Gdx.files.local`,可写无权限)+ loader 按 `baseDir` 定位 + `ModScanner.scanExternal` 扫描。但**当前无法把外部 mod 放进去**(玩家无 import 入口,只能手动拷目录)。
- `SaveSlotIO.readSlotFromStream`(`core/.../saveslot/SaveSlotIO.java:149-199`)已有成熟的 zip 解压模板:`ZipInputStream` + `isSafeEntryName`(路径穿越防御)+ `MAX_ENTRY_COUNT=64` + `MAX_TOTAL_BYTES=64MB` + `failAndCleanup`。**但它拒绝目录 entry**(slots 是扁平的,line 168-171),mod zip 需要嵌套目录(`scripts/items/foo.lua`)→ `ModInstaller` 需要支持目录 entry 的变体。
- `ModScanner` 已有完整校验(`ModScanner.java:161-184`):`mod.json` 存在 + `fromJson` + `id==dirname` + `spd_version==versionCode` + id 正则。`ModInstaller` 解压后**复用**这套校验(把解出的目录当 external mod 扫一遍)。
- **平台约束**:`core` 跨平台编译,Android 无 `java.awt`,desktop 的 AWT `FileDialog` 不能进 `core`。所以 picker impl 必须放平台模块:`desktop/.../DesktopLauncher` 注册 desktop impl,`android/.../AndroidLauncher` 注册 SAF impl(M12c)。
- 启动入口已确认:`desktop/src/main/java/com/shatteredpixel/shatteredpixeldungeon/desktop/DesktopLauncher.java`、`android/.../android/AndroidLauncher.java`。

**设计决策**:
- **平台 seam**:`core/modding/ModImporter.java` interface(`pickZip(Callback)`)+ 静态 `impl` holder(`setPlatformImpl` / `get`,platform 启动时注入)。WndModManager 只调 `ModImporter.get()`,不感知平台。
- **优雅降级**:未注册 platform impl 时(M12c 未做、或 iOS)按钮**隐藏**(`ModImporter.get()==null` → 不显示按钮),不崩。
- **共享 core**:`core/modding/ModInstaller.java` —— 平台无关,`installFromStream(InputStream, Callback)` 解压 + 校验,返回成功/失败 + mod id(供 UI 提示)。
- **解压安全**:复刻 `SaveSlotIO` 的 `isSafeEntryName` + `MAX_ENTRY_COUNT` + `MAX_TOTAL_BYTES`;**允许目录 entry**(`entry.isDirectory()` → `mkdirs`,不跳过)。
- **目标目录**:`Gdx.files.local("mods_user/" + <dirName>)`,其中 `<dirName>` 从 zip 内 `mod.json` 的 `id` 解析(解压到临时 staging → 读 `mod.json` 拿 id → 原子 rename 到 `mods_user/<id>/`)。id 冲突(目录已存在)→ 失败提示 "mod already exists"(不覆盖,防误删)。
- **重启生效契约不变**(M12a):import 成功后提示 "restart to load",不热加载。

## Files (worker-verified)
- **`core/.../modding/ModInstaller.java`**(新):`installFromStream(InputStream, ImportCallback)` —— staging 解压(`Gdx.files.local` 临时目录)+ 安全校验 + 读 `mod.json` 拿 id + 原子 rename 到 `mods_user/<id>/`;失败 cleanup staging。纯平台无关(只依赖 `FileHandle`/`ZipInputStream`)。
- **`core/.../modding/ModImporter.java`**(新):interface `pickZip(ImportCallback cb)` + `static ModImporter PLATFORM_IMPL; static void setPlatformImpl(ModImporter); static ModImporter get();`。Callback:`onSuccess(String modId)` / `onError(String code)` / `onCancel()`。
- **`core/.../modding/WndModManager.java`**:构造函数末尾(hint 前,line ~111)条件加 "Import Mod" `RedButton`(`if (ModImporter.get() != null)`);click → `ModImporter.get().pickZip(cb)`,cb 回调渲染线程更新(hint 或新 `RenderedTextBlock` 显示结果:成功 "imported <id>, restart" / 失败码本地化 / 取消静默)。
- **`desktop/.../desktop/DesktopModImporter.java`**(新,desktop 模块):AWT `FileDialog`(`*.zip` filter)在 EDT 弹出 → 选中的 `File` → `FileInputStream` → `ModInstaller.installFromStream`。`Gdx.app.postRunnable` 回到渲染线程调 callback。
- **`desktop/.../desktop/DesktopLauncher.java`**:启动(main / 初始化处)调 `ModImporter.setPlatformImpl(new DesktopModImporter())`。
- **测试**:`core/src/test/.../modding/ModInstallerTest`(staging 解压正确性 + 目录 entry 支持 + 路径穿越拒绝 + mod.json 缺失失败 + id/dirname 不符失败 + 已存在 id 失败 + 成功 rename 到 `mods_user/<id>/`)。`ModInstaller` 用 `Gdx.files.local` 临时目录(test 注入 temp root,镜像 `LuaEngineExternalLoadTest` 的 temp dir 模式)。

### 显式延后(不在 M12b 范围)
- **Android SAF picker**(M12c):`AndroidSafModImporter` + `AndroidLauncher` 注册。M12b 只把 seam(`ModImporter` interface + holder)建好,desktop 注册好;android 侧 `ModImporter.get()` 在 M12c 前返回 null(按钮隐藏)。
- **热加载**:import 成功不热加载,提示重启(M12a 契约)。
- **覆盖/更新已存在 mod**:id 已存在直接失败(不覆盖)。更新流程(卸载旧 → 装新)留后续。
- **进度条**:大 zip 的解压进度 UI 留后续(有 `MAX_TOTAL_BYTES=64MB` 上限兜底,不会卡死)。

## Steps

> **Phase-1 refinements (worker):**
> - `MAX_ENTRY_COUNT` raised 64 → **256**. Why: a sprite mod with ~30 items already ships 60+ files (1 lua + 1 png each) + spritesheets; 64 would reject legitimate mods. The 64 MB uncompressed byte cap (unchanged) is the real zip-bomb guard; entry count is a secondary sanity ceiling.
> - **id-is-authoritative strategy**: target dir is always `mods_user/<mod.json id>`, regardless of the zip's top-level dir name. So there is **no `id_mismatch` error code** — a zip whose top dir is `foo/` but whose `mod.json` declares `id=bar` installs cleanly as `mods_user/bar/` (and `ModScanner` then admits it because dirname==id==`bar`). This is more lenient than builtin's strict dirname check, but import is an explicit user action so silent rename-to-id is acceptable.
> - **Test seam**: `ModInstaller.installFromStream` (public, resolves `Gdx.files.local`) delegates to package-private `installInto(FileHandle externalRoot, InputStream, cb)` so headless tests inject a temp `FileHandle` without a real libgdx local mount (mirrors `LuaEngineExternalLoadTest`'s `new FileHandle(tmpDir)` pattern). Tests never call `installFromStream` (which needs live Gdx).
> - **Holder**: interface fields are implicitly `public static final`, so the mutable picker reference lives in a nested `final class Holder { static volatile ModImporter impl; }` inside the `ModImporter` interface; `setPlatformImpl`/`get` are static methods on the interface.

### S1. `ModImporter` interface + holder(core)
```java
public interface ModImporter {
    void pickZip(ImportCallback cb);
    interface ImportCallback {
        void onSuccess(String modId);
        void onError(String code);   // "io_error"/"invalid_zip"/"too_many_entries"/"zip_too_large"/"bad_manifest"/"version_mismatch"/"already_exists"
        void onCancel();
    }
    // platform registration (set by DesktopLauncher / AndroidLauncher)
    ModImporter PLATFORM_IMPL = null; // static holder
    static void setPlatformImpl(ModImporter i) { ... }
    static ModImporter get() { return PLATFORM_IMPL; }
}
```
(实际用 `private static ModImporter platformImpl;` + getter/setter,线程可见性注意:启动时单线程注入,渲染线程读,无需 volatile 也安全;保守起见加 `volatile`。)

### S2. `ModInstaller` 平台无关 core(core)
```java
public class ModInstaller {
    // 复刻 SaveSlotIO 安全常量(entry count 提到 256,见 Phase-1 refinements)
    static final int MAX_ENTRY_COUNT = 256;
    static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    static final int BUFFER_SIZE = 8192;

    public static void installFromStream(InputStream in, ModImporter.ImportCallback cb) {
        // 解析 root = Gdx.files.local("mods_user/"); mkdirs; 失败 → cb.onError("io_error") return
        // → installInto(root, in, cb)
    }
    static void installInto(FileHandle externalRoot, InputStream in, ModImporter.ImportCallback cb) {
        // 1. externalRoot.mkdirs(); staging = externalRoot.child(".staging-" + UUID); staging.mkdirs()
        // 2. unzipSafely(staging, in): ZipInputStream 遍历,**每条 entry 走同一校验前置于写**:
        //    a. name = entry.getName()
        //    b. isSafeEntryName(name) — **目录 entry 也校验**(防 `../evil/` 目录穿越)
        //    c. ++count > MAX_ENTRY_COUNT → throw too_many_entries(**目录 entry 也计数**)
        //    d. 校验全过后才分支:
        //       - directory entry → staging.child(name).mkdirs() 后 continue
        //       - file entry → mkdirs parent + 写入 + 累加 bytes(超 MAX_TOTAL_BYTES → throw zip_too_large)
        //    核心不变量:**任何 FS 写入(mkdir 或 write)都在 b/c 校验通过之后**(codex round-1 must-fix)
        // 3. contentRoot = resolveModRoot(staging): staging 有 mod.json → root=staging;
        //    否则找唯一含 mod.json 的顶层 dir → root=它;否则 bad_manifest
        // 4. readManifest(contentRoot): JsonReader.parse(mod.json) → ModManifest.fromJson(类型严格)
        //    - spd_version != Game.versionCode → version_mismatch
        // 5. target = externalRoot.child(mf.id);若 target.exists() → already_exists(不覆盖)
        // 6. contentRoot.moveTo(target)(同类型 Local→rename 原子;跨类型 copyTo+deleteDirectory)
        // 7. cleanup(staging)(若还存在);cb.onSuccess(mf.id)
        // 任一步失败 → cleanup(staging) + cb.onError(code)
    }
    // isSafeEntryName(name):拒绝首字符 '/'、盘符(X:)、反斜杠、含 '..' 或 '.' 段;
    //   允许嵌套 '/'(mod 目录树 scripts/items/x.lua)。与 SaveSlotIO 的 charset-locked 变体不同。
    //   *每条 entry 在写入前先过此校验*,fail-closed(遇穿越条目整包失败 + 清 staging)。
}
```
**zip 根结构策略**(已定):优先 zip 根含 `mod.json`;若无,扫描顶层 dir,唯一含 `mod.json` 的 dir 作 root(剥前缀);多个 dir 各带 manifest / 无 manifest → `bad_manifest`。id 以 mod.json 为准,target 永远是 `mods_user/<id>`(无 `id_mismatch` 错误码)。

### S3. WndModManager Import 按钮(core)
- TXT_ZH/EN 加:`import_button`("导入模组"/"Import Mod")、`import_ok`("已导入 %s,重启生效"/"Imported %s. Restart to load.")、错误码 map(`already_exists` → "已存在"/"already exists" 等)、`import_cancel`。
- 构造函数 hint 前:若 `ModImporter.get() != null`,加 `RedButton`(txt("import_button")),`setRect` 放 hint 上方(调整 `hintTop` 或按钮叠在 scroll 底部固定区);click → `ModImporter.get().pickZip(cb)`,cb 在渲染线程(`Gdx.app.postRunnable`)更新一个结果 `RenderedTextBlock`(成功/失败/取消)。
- **按钮显隐**:`ModImporter.get()==null`(M12c 前的 android、iOS)→ 不加按钮,窗口退化为 M12a 状态(不崩)。

### S4. DesktopModImporter(desktop 模块)
```java
public class DesktopModImporter implements ModImporter {
    public void pickZip(ImportCallback cb) {
        // spawn worker thread ("spd-mod-import"):
        //   wrapped = renderThreadWrapper(cb)  // 每个 cb 方法都 postRunnable 回渲染线程
        //   EventQueue.invokeAndWait {          // Swing 组件必须在 EDT 构造/弹出
        //       chooser = new JFileChooser(); setFileFilter("zip"); rc = showOpenDialog(null);
        //       if APPROVE: picked[0] = chooser.getSelectedFile()   // 在 EDT 上抓 file
        //   }   // 阻塞 worker 直到用户选完;libgdx render thread 独立,继续渲染
        //   if rc != APPROVE || picked==null → wrapped.onCancel(); return
        //   is = new FileInputStream(picked[0]) → ModInstaller.installFromStream(is, wrapped) (finally close is)
        //     // 解压在 worker thread(不阻塞 EDT);64MB 上限兜底
    }
}
```
**线程模型(已定,codex Phase-2 must-fix)**:JFileChooser 的构造 + `showOpenDialog` 必须在 Swing EDT(`EventQueue.invokeAndWait`),不违反 Swing 线程规则;选中的 `File` 在 EDT 内捕获,之后 worker 线程不再触碰 chooser。解压在 worker thread(不阻塞 EDT;64MB 上限兜底,本 milestone 接受同步解压,进度 UI/异步留后续)。回调统一经 `renderThreadWrapper` → `Gdx.app.postRunnable` 回渲染线程。(M12a 的 `DesktopSaveSlotBridge` 在 worker thread 直接用 chooser 是既有技术债,本 feature 不复刻。)

### S5. DesktopLauncher 注册(desktop 模块)
- `main()` 或 SPD-classes Game 初始化后,调 `ModImporter.setPlatformImpl(new DesktopModImporter())`。
- 时机:在 `new SPDSettings(...)` / `PixelDungeon` 构造前均可,只要在第一个 `WndModManager` 打开前。建议放 `DesktopLauncher.main` 里 `Lwjgl3Application` 创建之前(impl holder 是 static,早注册无副作用)。

### S6. 测试(core,`ModInstallerTest`)
- temp `mods_user/` root(`Gdx.files.local` 或 mock;镜像 `LuaEngineExternalLoadTest` 的 temp Local 模式 —— 该测试已在 core test 跑通 Gdx.files.local,参考其 setup)。
- cases:
  - `install_flatZip_modJsonAtRoot_success`:zip 根含 `mod.json`(id=`imp_a`, spd_version=versionCode)+ `scripts/items/x.lua` → 解压 → `mods_user/imp_a/` 存在 + 含 mod.json + cb.onSuccess("imp_a")。
  - `install_zipWithTopDir_success`:zip 内 `mymod/mod.json` + `mymod/scripts/...` → 剥前缀 → `mods_user/mymod/`(id 必须是 mymod 或匹配 mod.json id —— 按 mod.json id)。
  - `install_directoryEntries_created`:zip 含 `scripts/items/` 目录 entry + `scripts/items/a.lua` → 目录被 mkdirs,a.lua 写入。
  - `install_pathTraversal_rejected`:zip 含 `../evil.lua` → `onError("invalid_zip")` + staging 清理 + `mods_user/` 无残留。
  - `install_pathTraversalDir_rejected`:zip 含**目录 entry** `../evil/`(codex round-1 must-fix)→ `onError("invalid_zip")` + staging 清理 + `mods_user/` 外无 `evil/` 目录。
  - `install_missingModJson_fails`:zip 无 mod.json → `onError("bad_manifest")`。
  - `install_topDirDiffersFromId_renamesToId`:zip 顶层 dir `mymod/` 但 mod.json `id=imp_b` → 成功 rename 到 `mods_user/imp_b/`(验证 id 权威性)。
  - `install_versionMismatch_fails`:mod.json spd_version != versionCode → `onError("version_mismatch")`。
  - `install_alreadyExists_fails`:`mods_user/imp_a/` 预先存在 → `onError("already_exists")` + 原目录不被动。
  - `install_tooManyEntries_fails`:257 个 entry → `onError("too_many_entries")`(目录 entry 也计数)。
  - `install_zipTooLarge_fails`(codex round-1 must-fix):单 entry 写入 > MAX_TOTAL_BYTES(64MB)→ `onError("zip_too_large")` + staging 清理。实现:向 ZipOutputStream 以 1MB chunk 写 64MB+1 字节(ZipOutputStream 自动 deflate,zip 本体小;读回时 ZipInputStream inflate 触发累加计数),避免测试堆里持有 64MB。
- DesktopModImporter 本身不写单测(AWT 弹窗不可 headless 测);靠手动验证 + 集成。

## Steps 执行顺序(worker)
1. S1 `ModImporter` interface + holder(core)
2. S2 `ModInstaller`(core,含安全 + 校验 + 原子 rename)
3. S6 `ModInstallerTest`(core,先写,驱动 S2 正确性)
4. S3 WndModManager 按钮 + 文案(core)
5. S4 `DesktopModImporter`(desktop 模块)
6. S5 `DesktopLauncher` 注册(desktop 模块)
7. `./gradlew :core:test` 全绿
8. `./gradlew :desktop:debug` 编译过(DesktopLauncher + DesktopModImporter 无编译错)
9. codex 评审(Phase 1 PLAN + Phase 2 diff,codex exec --sandbox read-only)
10. 手动验证:desktop 跑起来 → 打开 Mod 管理 → 点 Import → 选一个测试 zip → 提示成功 → 重启 → 新 mod 出现(标 [外部])

## Acceptance
- [ ] `ModImporter` interface + static holder 在 core(modding 包)
- [ ] `ModInstaller.installFromStream` 平台无关,支持目录 entry + 路径穿越防御 + 256 entry/64MB 上限
- [ ] 解压后复用 ModScanner 校验(mod.json + id + spd_version),失败给错误码
- [ ] 原子 rename 到 `mods_user/<id>/`,id 已存在 → `already_exists` 失败(不覆盖)
- [ ] WndModManager 有 "Import Mod" 按钮,`ModImporter.get()==null` 时隐藏(不崩)
- [ ] desktop:DesktopModImporter(AWT FileDialog)+ DesktopLauncher 注册,能选 zip 导入
- [ ] import 成功提示 "restart to load"(不热加载,M12a 契约)
- [ ] `./gradlew :core:test` 全绿(566 + 新增)
- [ ] `./gradlew :desktop:debug` 编译过
- [ ] C3 不破:vanilla / builtin mod / 已有 mod 启用状态不受影响
- [ ] M12c seam 就位:`ModImporter` interface + holder,android 侧未注册 → 按钮隐藏(M12c 只加 android impl)

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,**不 assign codex_reviewer**(memory:codex_reviewer terminal 必超时,用 codex exec workaround)
- AWT 代码(`FileDialog`/`JFileChooser`/`Frame`)只在 `desktop` 模块,**绝不进 core**(Android 无 java.awt,会编译挂)
- `ModInstaller` 只依赖 `FileHandle` + `java.util.zip` + `java.util.*`,纯 core 安全
- 解压在阻塞 EDT/render 线程上:本 milestone 接受(64MB 上限);进度 UI/异步留后续
- **路径穿越防御必做**(`isSafeEntryName` 拒绝 `..` / 绝对路径 / 反斜杠 / 盘符)—— zip 解压是经典攻击面
- 重启生效契约不变(M12a)
- 本 feature 只做共享 core + desktop;Android SAF 是 M12c
