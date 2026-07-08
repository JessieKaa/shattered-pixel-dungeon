# PLAN: M12a — 外部目录共存 + loader 解耦(Phase A)

## Goal
让 mod 不再只从内置 `assets/mods/` 加载,同时支持外部 `mods_user/` 目录(`Gdx.files.local`,可写、无 Android 权限)。LuaEngine loader 去掉 `mods/` 硬编码前缀,改为按 `ModManifest.baseDir` 定位。id 冲突时内置优先、外部 skip + log。为 M12b/c(zip import)铺路。

## Context
调研确认:
- `ModScanner.listModDirs()` 只走 classpath/internal(`ClassLoader.getResource("mods")` + `Gdx.files.internal("mods").list()`)。**无外部路径**。
- `LuaEngine.loadScriptsFrom`(`LuaEngine.java:210` 等)硬编码 `mods/<id>/scripts/...`。
- `ModManifest` 无 origin/baseDir 字段。
- `Gdx.files.local` 已被存档用(Dungeon.saveGame),Android = `getFilesDir()` 可写无权限,desktop = 工作目录。**无需新权限**。
- `FileUtils.copyDir/deleteDir/dirExists` 已存在(SaveSlotService 在用)。
- `LuaEngine.findResource` + `loadScriptsFrom` 走 classpath 双通道枚举,外部目录必须走纯 `FileHandle.list()` 路径。
- id 冲突:内置优先,外部 skip + log(不崩)。

**设计决策**:
- `ModManifest` 加 `origin`(BUILTIN/EXTERNAL)+ `baseDir`(FileHandle)。loader 用 `mod.baseDir.child("scripts/...")` 而非拼 `mods/` 前缀。
- 外部目录:`Gdx.files.local("mods_user/")`。ModScanner 合并 builtin + external 扫描结果。
- id 冲突:builtin 胜,external 同 id skip + safeLog。
- 重启生效契约不变(import/外部改动后重启)。

## Files (worker-verified 2026-07-08)
- `core/.../modding/ModScanner.java` — `scan()` 合并 builtin(classpath)+ external(`Gdx.files.local("mods_user/")`);`scanChildren(FileHandle[], Origin)` 设 origin+baseDir;新增 package-private `scanExternal(FileHandle)` + `mergeById(builtin, external)`(测试 seam + 合并逻辑)
- `core/.../modding/ModManifest.java` — `enum Origin { BUILTIN, EXTERNAL }` + public non-final `origin`/`baseDir` + `setRuntimeMeta(Origin, FileHandle)`(运行时,不进 bundle;ModManifest 无 Bundle 序列化,见调研)
- `core/.../modding/ModRegistry.java` — 新增 package-private `scanExternal(FileHandle)` 测试 seam(镜像 scanDir,让 LuaEngine external 加载测试能注入 EXTERNAL manifest)。origin/baseDir 由 ModScanner 在 manifest 上设好,Registry 仅透传,**不改** scan/scanFrom 主链
- `core/.../modding/LuaEngine.java` — 11 个 `loadXxxScripts` + `loadModEntryScripts` 改用 `mod`+`subdir`;BUILTIN 走原 classpath 双通道(改名 `listBuiltinScriptNames` + `findResource`,**不变**);EXTERNAL 走 `mod.baseDir.child(subdir).list()` + `.read()`(纯 FileHandle,无 classpath)
- `core/.../modding/WndModManager.java` — ModCheckBox label 追加 origin 角标(硬编码 ZH/EN `[内建]`/`[外部]`)
- 测试:`ModScannerTest`(scanExternal fixture + mergeById builtin-wins + missing mods_user)、新增 `LuaEngineExternalLoadTest`(external mod scripts → register_item)

### 显式延后(不在 M12a 范围)
- `LuaEngine.java:784` `register_level` 默认 `path = "mods/levels/<id>.json"` + `LuaLevelService.LEVELS_DIR = "mods/levels/"`:levels 是**共享目录**(非 per-mod `mods/<id>/`),属独立子系统(M4a data-driven levels)。外部 mod 的 levels 定位(若需要)留后续 milestone,M12a 只做 per-mod scripts/entry 解耦。

## Steps
1. **ModManifest 加 origin/baseDir**:
   - `enum Origin { BUILTIN, EXTERNAL }`
   - `public Origin origin; public FileHandle baseDir;`(运行时字段,不进 bundle/不序列化)
   - fromJson 不变(origin/baseDir 由 scanner 设置)。
2. **ModScanner 扫外部**:
   - `scanExternal(FileHandle baseDir)`:用 `baseDir.list()` 扫子目录,复用 `scanChildren` 逻辑,设 manifest.origin=EXTERNAL + baseDir。
   - `scan()`:调 builtin listModDirs + `scanExternal(Gdx.files.local("mods_user/"))`,合并;id 冲突 builtin 胜,external skip + safeLog。
   - `mods_user/` 不存在 → `scanExternal` 返回空(mods_user/ 在玩家首次 import 时创建)。
3. **LuaEngine loader 解耦**:
   - 找所有硬编码 `mods/<id>/...` 路径(grep `mods/` 在 LuaEngine)。
   - 改为 `mod.baseDir.child("scripts/items")` 等(mod = ModManifest)。
   - `findResource` 兼顾 external FileHandle(外部直接 `baseDir.child(path)`,不走 classpath)。
   - 保留 builtin classpath 双通道(M9 LWJGL3 fix)。
4. **测试**:
   - ModScanner external fixture:在 temp Local 目录建 `mods_user/test_external/mod.json`,scan 后 manifest.origin=EXTERNAL + baseDir 正确。
   - id 冲突:builtin test_mod + external test_mod → builtin 胜,external skip。
   - LuaEngine external 加载:external mod 的 scripts/items/*.lua 被加载(register_item)。
   - mods_user/ 不存在 → scan 不崩,返回 builtin only。
5. **WndModManager origin 显示**(最小):
   - mod 行显示 origin 角标(builtin/external),让玩家知道 mod 来源。完整 UI(import 按钮/错误)留 M12b/c。

## Steps (worker 细化 — 具体签名)

### S1. ModManifest
```java
public enum Origin { BUILTIN, EXTERNAL }
public Origin origin;        // 运行时,scanner 设;非 final,fromJson 不碰
public FileHandle baseDir;   // 运行时,scanner 设;mod 自身目录 handle(builtin=mods/<id>, external=mods_user/<id>)
public void setRuntimeMeta(Origin o, FileHandle dir) { this.origin = o; this.baseDir = dir; }
```
fromJson 构造的 manifest 默认 origin=null/baseDir=null;scanner 在 admit 前调 setRuntimeMeta。LuaEngine loader 对 origin==null 兜底走 BUILTIN 分支(防御,不 NPE)。

### S2. ModScanner
- `scanChildren(FileHandle[], Origin origin)`:内部 `m.setRuntimeMeta(origin, child)`(child = mod 子目录 handle)。签名加 Origin 参数。
- `scanDir(FileHandle)`(public 测试 seam):`scanChildren(baseDir.list(), Origin.BUILTIN)` — **行为不变**,现有测试零改动。
- `scanExternal(FileHandle baseDir)`(package-private):null/!exists → empty;否则 `scanChildren(baseDir.list(), Origin.EXTERNAL)`。
- `externalModsRoot()`(private):`try { return Gdx.files.local("mods_user/"); } catch { log; return null; }`
- `scan()`:`builtin = scanChildren(listModDirs(), BUILTIN); external = scanExternal(externalModsRoot()); return mergeById(builtin, external);`
- `mergeById(builtin, external)`(package-private):builtin 全收 + 记 seen;external 逐个,seen 命中 → safeLog "shadowed by builtin, skip",否则收。返回合并 list(保序:builtin 先、external 后)。

### S3. ModRegistry
- 新增 `static synchronized void scanExternal(FileHandle baseDir) { scanFrom(ModScanner.scanExternal(baseDir)); }`(package-private 测试 seam)。scan/scanFrom/all/get/isEnabled/setEnabled **不动**。

### S4. LuaEngine(关键:保 builtin classpath 双通道)
- 11 个 `loadXxxScripts`:循环体改 `loadScriptsFrom(mod, "scripts/items", label, regSize)`(传 mod + 相对 subdir,不再拼 `mods/`)。
- `loadScriptsFrom(ModManifest mod, String subdir, String label, IntSupplier regSize)`:
  - `String[] names = listScriptNames(mod, subdir)`
  - `String base = chunkPath(mod, subdir)`(log/chunk 名,见下)
  - 空 → log + return;否则排序,逐个 `openScriptStream(mod, subdir, n)` → `globals.load(InputStreamReader, base+"/"+n).call()`
- `listScriptNames(ModManifest, subdir)`:
  - EXTERNAL(baseDir!=null):`baseDir.child(subdir).list()` → 过滤 `.lua` → names。纯 FileHandle(external 是 local 真实 FS,list() 可靠)。
  - 其他(BUILTIN/origin==null 兜底):`listBuiltinScriptNames("mods/"+mod.id+"/"+subdir)` — 原 classpath 双通道逻辑**逐字保留**(M9 LWJGL3 fix)。
- `openScriptStream(mod, subdir, name)`:
  - EXTERNAL:`baseDir.child(subdir).child(name)` → exists? read() : null
  - 其他:`findResource("mods/"+mod.id+"/"+subdir+"/"+name)`(原路径,**不变**)
- `chunkPath(mod, subdir)`:EXTERNAL → `baseDir.path()+"/"+subdir`;否则 `"mods/"+mod.id+"/"+subdir`。仅用于 log/chunk 名,不影响加载逻辑。
- `loadModEntryScripts`:entry 流改 `openModEntryStream(mod)`(EXTERNAL → `baseDir.child(mod.entry).read()`;否则 `findResource("mods/"+mod.id+"/"+mod.entry)`)。chunk 名 `mod:<id>:<entry>` 不变。
- `listScriptNames(String dir)` 重命名为 `listBuiltinScriptNames(String dir)`(private,无外部调用)。
- `findResource(String)` **不动**(仍是 builtin classpath/internal 解析,供 init.lua + builtin per-mod 用)。

### S5. WndModManager
- TXT_ZH/EN 加 `origin_builtin`/`origin_external`(ZH `[内建]`/`[外部]`,EN `[built-in]`/`[external]`)。
- ModCheckBox label:`mod.name + " v" + mod.version + " " + originTag(mod)`;`originTag` 按 `mod.origin` 取(origin==null → builtin 文案兜底)。

### S6. 测试
- `ModScannerTest` 加:
  - `scanExternal_setsExternalOriginAndBaseDir`:temp `mods_user/ext_mod/mod.json` → scanExternal → origin=EXTERNAL, baseDir 指向 ext_mod。
  - `merge_builtinShadowsExternalById`:builtin `shared` + external `shared`/`ext_only` → mergeById → {shared(builtin), ext_only(external)},shared.origin=BUILTIN。
  - `scanExternal_missingDir_returnsEmpty`:scanExternal(不存在 dir) → empty。
- 新增 `LuaEngineExternalLoadTest`:temp `mods_user/ext_mod/{mod.json, scripts/items/ext_item.lua}`;`ModRegistry.scanExternal(dir)` + enable ext_mod + init → `LuaItemRegistry.contains("ext_item")` true + `ModRegistry.get("ext_mod").origin==EXTERNAL`。

## Acceptance
- [ ] ModManifest 有 origin + baseDir 字段(运行时,不进 bundle)
- [ ] ModScanner 扫 `mods_user/`(Gdx.files.local)+ 合并 builtin
- [ ] id 冲突 builtin 胜,external skip + log(不崩)
- [ ] LuaEngine loader 用 baseDir 定位,不再硬编码 `mods/` 前缀
- [ ] external mod 的 scripts 被加载(register_* 工作)
- [ ] `mods_user/` 不存在时 scan 不崩
- [ ] WndModManager 显示 origin 标记
- [ ] `./gradlew :core:test` 全绿(560 现有 + 新增)
- [ ] C3 不破(default_enabled 不变,vanilla 不碰)

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,不 assign codex_reviewer
- 外部目录用 `Gdx.files.local`(可写无权限),**不用 external**(要 SD 卡权限)
- 重启生效契约不变
- builtin classpath 双通道(M9 LWJGL3 fix)不能破
- 本 feature 只做「目录共存」,zip import 留 M12b/c
