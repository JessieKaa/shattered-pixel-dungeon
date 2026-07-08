# PLAN: M12d — 外部 mod 自定义关卡支持(补完 M12a 延后项)

## Goal
让 `mods_user/<modId>/` 里的外部 mod 能带自定义关卡。补完 M12a 显式延后的「levels 共享目录未解耦」—— M12a 只做了 per-mod scripts/entry 解耦,levels 仍只从 classpath `mods/levels/` 加载,外部 mod 的关卡完全不可见。本 feature 让 `enterLevel(id)` 按 `ModManifest.baseDir` 解析外部 level json,builtin 行为零改动。**与 M12b/M12c 零文件冲突**(levels 子系统全在 core/modding,不碰 import/UI/desktop/android)。

## Context(Explore 2026-07-08 核实)
- **levels 不扫目录,按 id on-demand 加载**:`LuaLevelService.enterLevel(id)`(`modding/LuaLevelService.java:104`)在 `:120` 硬编码 `DataDrivenLevel.fromAsset(LEVELS_DIR + id + ".json", id)`,`LEVELS_DIR = "mods/levels/"`(`:63`)。
- **`DataDrivenLevel.fromAsset`**(`modding/DataDrivenLevel.java:140-149`)只走 `Gdx.files.internal(path).readString()`(classpath/asset),**无法读 `mods_user/` 外部 FS**。
- **`register_level`**(`LuaEngine.java:855-874`,inner class `RegisterLevelFunction`):Lua 注册 `{id, name, path?}`,默认 `path = "mods/levels/" + id + ".json"`(`:867`)。**path 存进 registry 但 `enterLevel` 从不读它**(Explore 确认:metadata-only today)。
- **`LuaLevelRegistry`**(`modding/LuaLevelRegistry.java`):只存 Lua table,用于 Bundle restore re-hydration(R3),**不存 origin/baseDir**。
- **现状**:外部 mod `mods_user/<id>/levels/foo.json` 完全不可见(`enterLevel` 只查 classpath)。
- **test fixture**:`core/src/main/assets/mods/levels/test_safezone.json`;`SafeZoneEnterTest` + `LuaLevelInjectTest` 覆盖 builtin 加载。
- **`ModScannerTest:178`** 断言 `mods/levels/` 被 mod 扫描跳过(无 mod.json)—— levels 不是 mod,是共享 asset 目录。

**设计决策**:
- **按 id 查 registry,按 origin 分流**:`enterLevel(id)` 先查 `LuaLevelRegistry`。命中且 `baseDir != null`(EXTERNAL)→ 走 `baseDir.child(relPath)` 读 FileHandle;否则(builtin / 未注册)→ 走原 `fromAsset` classpath(零改动)。
- **per-mod levels 目录约定**:外部 mod 的 level json 放 `mods_user/<modId>/levels/<levelId>.json`(相对 baseDir = `levels/<levelId>.json`)。与 M12a per-mod scripts/ 一致(`scripts/items/` 等)。builtin 仍是共享 `mods/levels/`(classpath),不动。
- **`register_level` 捕获当前 mod 上下文**:LuaEngine 加载 mod entry 脚本时(`loadModEntryScripts(mod)`)设 `currentMod` 字段;`register_level` 读它,把 origin/baseDir 连同 path 一起存进 registry。entry 加载完清 `currentMod`。
- **path 语义**:默认解析 —— EXTERNAL → `baseDir.child("levels/" + id + ".json")`;BUILTIN → `LEVELS_DIR + id + ".json"`(classpath,不变)。若 mod 显式给 `path`:EXTERNAL → `baseDir.child(path)`(相对 baseDir);BUILTIN → `fromAsset(path)`(classpath)。
- **id 冲突**:多个 mod 注册同一 level id,后注册覆盖 + log(与 M12a mergeById builtin-wins 不同语义:levels 是运行期 registry,后注册胜,简单可预测;不崩)。builtin `mods/levels/<id>` classpath 仍可作为未注册时的 fallback。

## Files (worker-verified)
- **`core/.../modding/LuaLevelRegistry.java`**:存 origin + baseDir + path(扩展现有 entry 结构或加并行 map)。`register(id, table, Origin, FileHandle baseDir, String path)` 重载;`get(id)` 返回含 origin/baseDir/path 的 entry。Bundle re-hydration 路径(R3)不动(只存 id→table,origin/baseDir 运行期重算)。
- **`core/.../modding/LuaEngine.java`**:
  - 加 `private static ModManifest currentMod;`(`loadModEntryScripts(mod)` 前后 set/clear,try/finally 守护)。
  - `RegisterLevelFunction.call`:读 `LuaEngine.currentMod`,调 `LuaLevelRegistry.register(id, tbl, currentMod?.origin, currentMod?.baseDir, path)`。currentMod==null(全局 init.lua / 非 mod 上下文)→ origin=null/baseDir=null(走 builtin classpath 分支)。
- **`core/.../modding/LuaLevelService.java`**:`enterLevel(id)` 改:先 `LuaLevelRegistry.get(id)`;entry!=null && entry.baseDir!=null → 外部路径(`DataDrivenLevel.fromFileHandle(entry.baseDir.child(resolvePath(entry)), id)`);否则原 `fromAsset(LEVELS_DIR + id + ".json", id)`(builtin,零改动)。`resolvePath(entry)`:`entry.path != null ? entry.path : "levels/" + id + ".json"`。
- **`core/.../modding/DataDrivenLevel.java`**:新增 `fromFileHandle(FileHandle fh, String id)`(`fh.readString("UTF-8")` → `JsonReader.parse` → `fromJsonValue`);`fromAsset` 不动(builtin 路径)。可让 `fromAsset` 委托 `fromFileHandle(Gdx.files.internal(path), id)` 去重(可选)。
- **测试**(新增 `LuaExternalLevelTest`,镜像 `LuaEngineExternalLoadTest` 的 temp Local 模式):
  - temp `mods_user/ext_lvl_mod/{mod.json, entry.lua, levels/dungeon_a.json}`;`entry.lua` 调 `register_level{id="dungeon_a", name="Dungeon A"}`。
  - `ModRegistry.scanExternal(tempRoot)` + enable ext_lvl_mod + init → `LuaLevelRegistry.get("dungeon_a")` 非空,origin=EXTERNAL,baseDir 指向 ext_lvl_mod。
  - `LuaLevelService.enterLevel("dungeon_a")`(或直接调 `DataDrivenLevel.fromFileHandle` 验证)→ level 加载成功(width/height/tiles 正确,镜像 SafeZoneEnterTest 断言)。
  - **builtin 回归**:`SafeZoneEnterTest` 仍绿(test_safezone 走 classpath fromAsset,未注册 → fallback 分支)。
  - **currentMod 清理**:entry 加载后 `LuaEngine.currentMod == null`(防御 leak 到下一个 mod)。

### 显式延后(不在 M12d 范围)
- **levels 目录扫描 / 发现**:levels 仍按 id on-demand(不扫目录列清单)。mod 通过 `register_level` 声明有哪些 level。一个 "level 列表 UI"(玩家选关)留后续。
- **builtin `mods/levels/` 迁移到 per-mod**:builtin 仍是共享 classpath 目录,不动(只让 external 能用 per-mod levels)。
- **level id 冲突的 UI 提示**:后注册覆盖 + log,不弹窗。

## Steps (worker-refined 2026-07-08, executable granularity)

> **Worker verification 校正**(读码确认,未改方向):
> - 分流判据用 `entry.origin == EXTERNAL && entry.baseDir != null`,**不是**裸 `baseDir != null` —— builtin mod 的 `ModManifest.baseDir` 也是非空(classpath handle,见 `ModManifest.java:66-72`),裸判会把 builtin mod 的 level 错误导向 `baseDir.child()`。`origin==EXTERNAL` 才是"外部 FS"的精确判据;`&& baseDir!=null` 是 NPE 防御(ModScanner.setRuntimeMeta 总是同时设两者,理论不会 null,但防御)。
> - `LuaEngine` 是 singleton(`private static LuaEngine instance`,`:48`),`RegisterLevelFunction` 是 static inner class → `currentMod` 必须是 **`static`** 字段(static inner class 读 `LuaEngine.currentMod`)。
> - **无 shipped Lua 调 `register_level`**(`grep -rn register_level core/src/main/assets/mods/` 空)—— builtin 行为确为零改动;test_safezone 走未注册 fallback。
> - `ModTestSupport.resetLuaState()`(**漏了** `LuaLevelRegistry.clear()`)需补上,否则新测试的 registry 会跨测试泄漏。
> - `DataDrivenLevel.fromAsset` 改为委托 `fromFileHandle(Gdx.files.internal(path), id)` 去重(语义等价,headless 可读 internal handle)。

1. **`LuaLevelRegistry` 扩展**(新增内部 `Entry` 类,旧 API 保留):
   - `public static final class Entry { public final LuaTable table; public final ModManifest.Origin origin; public final FileHandle baseDir; public final String path; }` —— origin/baseDir/path 运行期,**不进 Bundle**。
   - `private static final Map<String, Entry> levels = new HashMap<>;`(替换旧的 `Map<String, LuaTable>`)。
   - `register(String id, LuaTable t, Origin o, FileHandle base, String path)` → `levels.put(id, new Entry(t,o,base,path))`。
   - `register(String id, LuaTable t)` 保留(委托上面,传 null/null/null)—— `DataDrivenLevel.restoreFromBundle` 之类的旧调用不破(虽然它只 `getTable`,不 register)。
   - `Entry get(String id)` 新增;`getTable(id)` 改为 `Entry e=get(id); return e==null?null:e.table;`。`ids()/contains()/size()/clear()` 适配 Entry map。
2. **`LuaEngine.currentMod` 上下文** + **register_level 输入校验**(codex round-1 must-fix:路径穿越):
   - 加 `static ModManifest currentMod;`(package-private,供测试 `@After` 断言 null)。
   - `loadModEntryScripts()`(`:363`)per-mod body 包 `currentMod = mod; try { ...原逻辑... } finally { currentMod = null; }`。原 `try(InputStream)`+catch 结构保留在 try 内;`continue`/异常都触发 finally 清 currentMod。
   - `RegisterLevelFunction.call`(`:855`)—— **新增 id + path 安全校验**(镜像 `ModManifest.validateEntryPath`/`ID_PATTERN`,external 时 `baseDir.child(id/path)` 会真读 FS,未校验则 `../` 或绝对路径可逃出 mod 目录):
     - `String path = tbl.get("path").optjstring(null);`(null=默认)。
     - **校验 `id`**:复用 `ModManifest.ID_PATTERN = ^[a-z0-9_]+$`(现有 level id:`test_safezone`/`town_portal`/`town_return` 全符合)。不匹配 → `throw new IllegalArgumentException("invalid level id: "+id)`,落入既有 catch(log "register_level rejected a malformed definition" + 返回)。
     - **校验 explicit `path`**(path != null 时):relative(无前导 `/`)、无 `\`、无 `..` 段、`.json` 后缀(类比 entry 的 `.lua`)。不合法 → 同上 throw + 既有 catch。path==null(默认)不校验(运行期合成安全默认)。
     - `ModManifest m = LuaEngine.currentMod;` → `LuaLevelRegistry.register(id, tbl, m!=null?m.origin:null, m!=null?m.baseDir:null, path)`。
     - 删掉原算了 path 又丢弃的死代码(`:867`)。
   - **为何只在此校验**:RegisterLevelFunction 是唯一不可信入口(Lua 脚本);直接 Java 调用 `LuaLevelRegistry.register` 的测试是可信的(类比 `validateEntryPath` 只在 `fromJson` 入口校验)。
3. **`DataDrivenLevel.fromFileHandle(FileHandle fh, String id)`** 新增:`fh.readString("UTF-8")` → `JsonReader.parse` → `fromJsonValue(root, id)`;catch→`Gdx.app.error(TAG,"failed to load level file "+fh.path(),e)`→return null。`fromAsset` 改为 `return fromFileHandle(Gdx.files.internal(path), id);`。`fromJsonValue` 校验(width/height/tiles/entrance)**不动**。
4. **`LuaLevelService.enterLevel` 分流**(`:120` 一行替换为 helper):
   - 抽 `private static DataDrivenLevel loadLevelById(String id)`:
     - `Entry e = LuaLevelRegistry.get(id);`
     - `if (e != null && e.origin == EXTERNAL && e.baseDir != null)` → `String rel = e.path!=null ? e.path : "levels/"+id+".json"; return DataDrivenLevel.fromFileHandle(e.baseDir.child(rel), id);`
     - `else`(未注册 / builtin)→ `String cp = (e!=null && e.path!=null) ? e.path : LEVELS_DIR+id+".json"; return DataDrivenLevel.fromAsset(cp, id);`(builtin 默认路径原样,零改动)。
   - `enterLevel` 把 `DataDrivenLevel.fromAsset(LEVELS_DIR+id+".json", id)` 换成 `loadLevelById(id)`;后续 `if(level==null)` null-check + try/catch 不变(外部 json 失败 → fromFileHandle 返回 null → 现有 error log + return,与 builtin 失败行为一致,**不抛**)。
5. **`ModTestSupport.resetLuaState()`**:加 `LuaLevelRegistry.clear();`(与其它 registry 并列)。
6. **测试 `LuaExternalLevelTest`**(镜像 `LuaEngineExternalLoadTest` harness):
   - temp `mods_user/ext_lvl_mod/{mod.json, entry.lua, levels/dungeon_a.json}`;`entry.lua` = `register_level{id='dungeon_a', name='Dungeon A'}`;`dungeon_a.json` = 小尺寸合法 level(镜像 `LuaLevelInjectTest.sampleSafezoneJson` 的 5×5 wall/floor/entrance 结构)。
   - `scanExternal` + `setEnabled("ext_lvl_mod",true)` + `LuaEngine.init()`。
   - 断言:`LuaLevelRegistry.get("dungeon_a")` 非空;`entry.origin == EXTERNAL`;`entry.baseDir` 指向 ext_lvl_mod 目录;`entry.path == null`(未显式给)。
   - 断言加载:`DataDrivenLevel.fromFileHandle(entry.baseDir.child("levels/dungeon_a.json"), "dungeon_a")` 非空,`.create()` 后 width/height/length/entrance 正确(镜像 SafeZoneEnterTest 断言)。
   - 断言 currentMod 清理:`LuaEngine.currentMod == null`(entry 加载后)。
   - **路径穿越拒绝**(codex round-1 must-fix 回归):`register_level` 对 `id="../evil"`、`path="../../../etc/passwd"`、`path="/abs/foo.json"`、`path="x.txt"`(非 .json)均拒绝 —— registry 不收录(`LuaLevelRegistry.contains(...)` 为 false),不抛到 Lua 外(既有 catch 兜底)。用 inline `globals.load(...)` 跑这些 malformed register 调用断言。
   - **builtin 回归**:不在此测试重复(`SafeZoneEnterTest`/`LuaLevelInjectTest` 已覆盖);`./gradlew :core:test` 全跑确认它们仍绿。
7. **`./gradlew :core:test`** 全绿(577 + 1 新增 class 的方法数)。
8. **codex 评审**:Phase 1 PLAN + Phase 2 diff,`codex exec --sandbox read-only`。重点:currentMod try/finally 清理(防 leak)、`origin==EXTERNAL && baseDir!=null` 兜底(防 builtin 误分流 + NPE)、external json 校验复用 fromJsonValue(不放宽)、Bundle restore 路径不被 origin/baseDir 污染(Entry 不进 Bundle)。
9. **手动验证**(desktop,可选,非阻塞):`mods_user/test_ext_lvl_mod/levels/foo.json` + entry register_level → `enterLevel("foo")` 加载成功。

## Acceptance
- [ ] `LuaLevelRegistry` 存 origin/baseDir/path(运行期,不进 Bundle)
- [ ] `register_level` 捕获 currentMod 上下文,external level 带 baseDir 注册
- [ ] `LuaLevelService.enterLevel` 按 origin 分流:external → `baseDir.child()` 读 FileHandle;builtin → 原 classpath fromAsset(零改动)
- [ ] `DataDrivenLevel.fromFileHandle` 新增,builtin `fromAsset` 不破
- [ ] external mod 的 level json 能被加载(`mods_user/<modId>/levels/<id>.json`)
- [ ] builtin levels 回归:`SafeZoneEnterTest` + `LuaLevelInjectTest` 仍绿
- [ ] currentMod 在 entry 加载后清空(防跨 mod 泄漏)
- [ ] origin==null 兜底走 builtin 分支(不 NPE)
- [ ] `./gradlew :core:test` 全绿(577 + 新增)
- [ ] C3 不破:无 external level 时原版一周目不受影响(builtin fallback 路径不变)
- [ ] 与 M12b/M12c 零文件冲突(只碰 core/modding levels 子系统)

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,**不 assign codex_reviewer**(memory:必超时)
- **builtin 行为零改动** —— test_safezone 走原 fromAsset classpath 路径,`enterLevel` 未注册时 fallback 到 `LEVELS_DIR + id`
- external json 复用 `DataDrivenLevel.fromJsonValue` 校验(width/height/tiles/entrance),**不放宽**
- currentMod 必须 try/finally 清(防上一个 mod 的 baseDir 泄漏到下一个 mod 的 register_level)
- Bundle restore(R3)路径不碰:origin/baseDir 运行期,save/load 不序列化它们;重启后 mod 重 load 时 register 重设
- 本 feature 只做 per-mod external levels;levels 发现 UI / builtin 迁移留后续
