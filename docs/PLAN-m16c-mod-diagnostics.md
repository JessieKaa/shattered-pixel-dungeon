# PLAN: M16c — mod diagnostics UI and status

## Goal
让玩家/作者在游戏内看到 mod 扫描与加载状态,包括失败原因、警告、注册内容数量,不再必须依赖 adb/logcat 才知道坏 mod 为什么不能用。

## Context
当前 mod manager 已支持内置/外部 mod 列表、启停、导入、外部卸载等 UX。但错误主要通过 `Gdx.app.error` / log 输出,没有持久诊断模型:
- `ModScanner` 负责扫描 builtin/external manifests,遇错多为 log/skip。
- `LuaEngine` 执行 entry.lua/register_* 时可能失败,但错误不集中展示。
- `ModRegistry` 保存 scanned/enabled 状态,但没有 per-mod status/warnings/errors/registered counts。
- `WndModManager` 是展示 mod 状态的自然入口。

目标是实用诊断,不是复杂管理后台。

## Files
已核对并更新:
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModDiagnostics.java` (新):per-mod 诊断模型,包含 status/errors/warnings/counts/lastUpdated。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModRegistry.java`:保存/查询 diagnostics;在 scan() 前清理旧诊断,在 scan() 后合并 scanner 返回的诊断,在 LuaEngine 加载时更新。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModScanner.java`:新增 `ScanResult` 数据类,把 manifest parse、id conflict、external shadow 等扫描问题随结果返回,不再直接写 ModRegistry。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaEngine.java`:entry.lua 执行失败、register_* 失败、unknown/invalid declaration 写入 diagnostics;同时保持现有 log。
- 各 registry 提供 `size()` / `ids()`(已有):LuaEngine 在 `loadScriptsFrom` 内按成功执行的脚本/注册调用增量统计 counts。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/WndModManager.java`:正常 mod 行显示 status tag + 详情按钮;列表底部新增“扫描问题”区域展示 orphan diagnostics(坏 manifest / 被跳过的目录),点击可开详情。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/WndModDetails.java` (新):显示 id/name/version/origin/status/errors/warnings/counts。
- Tests: 新增 `ModDiagnosticsTest`,扩展 `LuaModEntryTest` / `ModScannerTest` 保证诊断行为。

## Steps
1. 新增 `ModDiagnostics.java`
   - 枚举 Status: `DISCOVERED`, `LOADED`, `FAILED`, `DISABLED`, `WARNINGS`。
   - 字段: `status`, `List<String> errors`, `List<String> warnings`, `Map<String,Integer> counts`, `long lastUpdated`。
   - 新增 `String declaredId` 字段,用于扫描问题行展示“声明的 id”(如 id mismatch 时显示 `declaredId = bar`,而 map key 是目录名)。
   - 限制 errors/warnings 最大数量(各 10 条)避免 UI 溢出;超过时最后一条替换为省略提示。
   - 提供 fluent API: `setStatus`, `setDeclaredId`, `addError`, `addWarning`, `setCount`, `incrementCount`, `clear`。

2. 扩展 `ModRegistry.java`
   - 新增 `private static final Map<String, ModDiagnostics> diagnostics = new LinkedHashMap<>()`。
   - `scan()` 开头调用 `clearDiagnostics()`,再调用 `ModScanner.scan()`,最后把 `ScanResult.diagnostics` 合并进来。
   - `scanFrom(List<ModManifest>)` 给每个扫描到的 mod 初始化 `ModDiagnostics.DISCOVERED`。
   - 提供 `public static ModDiagnostics getDiagnostics(String id)`(无诊断返回 null)。
   - 提供 package-static 方法:
     - `setModStatus(String id, Status)`
     - `addModError(String id, String)`
     - `addModWarning(String id, String)`
     - `setModCount(String id, String type, int)` / `incrementModCount(String id, String type)`
   - 提供 `public static Map<String, ModDiagnostics> allDiagnostics()` 只读视图。
   - 提供 `public static Map<String, ModDiagnostics> orphanDiagnostics()` 返回 id 不在 `all()` 中的诊断(用于 WndModManager 扫描问题区)。

3. 修改 `ModScanner.java`
   - 新增 public static final class `ScanResult { List<ModManifest> manifests; Map<String, ModDiagnostics> diagnostics; }`。
   - `scan()` / `scanDir()` / `scanExternal()` 返回 `ScanResult`。
   - `scanChildren` 使用 stable scan key `scan:<origin>:<dirname>` 记录被跳过目录的诊断,避免与合法 mod id 冲突:
     - manifest parse fail: key = `scan:<origin>:<child.name()>`, `addError("manifest parse: " + e.getMessage())`。
     - id/dirname mismatch: key = `scan:<origin>:<child.name()>`, `setDeclaredId(m.id)`, `addError("id mismatch: declared " + m.id + " vs dir " + child.name())`。
     - duplicate id: key = `scan:<origin>:<child.name()>`, `setDeclaredId(m.id)`, `addError("duplicate id " + m.id + " skipped")`。
     - version mismatch: key = `scan:<origin>:<child.name()>`, `setDeclaredId(m.id)`, `addWarning("spd_version " + m.spd_version + " != " + Game.versionCode)`。
     - external shadowed by builtin: key = `scan:EXTERNAL:<child.name()>`, `setDeclaredId(m.id)`, `addWarning("external shadowed by builtin id " + m.id)`。
   - 被跳过目录不产生 ModManifest,只产生 diagnostics entry;合法 manifest 的诊断由 `ModRegistry.scanFrom` 初始化为 `DISCOVERED`。

4. 修改 `LuaEngine.java`
   - 在 `initInternal` 捕获加载结果:
     - 对每个 enabled mod,在 `loadModEntryScripts` 的 try/catch 中,失败则 `addModError(id, "entry: " + 短错误)` 并 `setStatus(id, FAILED)`。
     - 对每个 `loadScriptsFrom` 的目录加载,脚本文件编译/执行失败通过 `addModError(mod.id, label + ": " + 短错误信息)` 记录;空目录警告通过 `addModWarning(mod.id, label + " empty")` 记录。`loadScriptsFrom` 自身不再 increment 内容 counts。
     - entry.lua 中 register_* 调用成功时,由对应 Register*Function 计数。
   - 对 `register_*` 函数内部接受的合法注册,通过 `ModRegistry.incrementModCount(currentMod != null ? currentMod.id : "init.lua", typeKey)` 按类型计数。typeKey 映射:register_item→`items`, register_spell→`spells`, register_mob→`mobs`, register_ally→`allies`, register_hero→`heroes`, register_npc→`npcs`, register_shop→`shops`, register_buff→`buffs`, register_talent/override→`talents`, register_painter→`painters`, register_trap→`traps`, register_level→`levels`。
   - 对 `register_*` 函数内部拒绝的 malformed definition,通过 `ModRegistry.addModError(currentMod != null ? currentMod.id : "init.lua", "register_xxx: " + 短错误)` 记录。
   - 在 `initInternal` 末尾,对 enabled 且没有错误的 mod 批量设置 `LOADED`;有 warning 的 mod 状态为 `WARNINGS`。
   - 对 disabled mod 在 scan 阶段已设为 `DISABLED`。
   - 保持现有 `Gdx.app.error` log 不变,诊断信息是增量补充。

5. 统计注册数量
   - 仅在 `Register*Function` 成功注册/接受定义后调用 `ModRegistry.incrementModCount(currentModId, typeKey)`。
   - currentMod==null 时用 key `"init.lua"`(全局 init.lua 的注册,非 mod 内容,展示为 "init.lua" 诊断条目)。
   - counts 代表“该 mod 成功注册的内容数量”,不统计脚本文件数;若未来需要脚本文件数,单独使用 `scripts_<type>` key。

6. 更新 `WndModManager.java`
   - 新增文案键:
     - ZH/EN: `status_loaded`, `status_failed`, `status_warnings`, `status_disabled`, `details_button`, `details_title`, `counts_label`, `errors_label`, `warnings_label`, `none`, `scan_problems_title`, `scan_problems_empty`。
   - 调整布局:
     - 每行左侧保留 checkbox,右侧新增小的 “详情” 按钮点击打开 `WndModDetails`。
     - 行的主 label 在 mod 名/版本后追加状态 tag,如 `[loaded]` / `[failed]` / `[warnings]` / `[disabled]`;失败/警告用红色/黄色高亮。
     - 长按行仍然触发卸载逻辑(不破坏 M13a)。
   - 列表底部新增“扫描问题”区域:
     - 显示 orphan diagnostics 的 key + 错误/警告数量。
     - 每个 problem row 点击打开 `WndModDetails`,展示 id(errors/warnings)。
     - 无 scan problems 时显示 `scan_problems_empty`。

7. 新增 `WndModDetails.java`
   - 窗口宽度 130,高度固定 160,带 ScrollPane。
   - 显示内容:
     - 标题: mod.name + " v" + version + origin tag(orphan 用 key 作为 name,version 为空,origin 为空)。
     - id: `ID: <id>`;若 `ModDiagnostics.declaredId` 非空,额外显示 `Declared ID: <declaredId>`。
     - Status: 状态字符串(loaded/failed/warnings/disabled/discovered)。
     - Errors: 每条错误一行,无则显示 `none`。
     - Warnings: 每条警告一行,无则显示 `none`。
     - Counts: items/mobs/allies/heroes/spells/npcs/shops/buffs/talents/painters/traps/levels 等,只显示 >0 的项,用紧凑列表。
   - 所有文案使用硬编码 ZH/EN map。

8. 测试
   - 新增 `ModDiagnosticsTest`:
     - `badManifest_recordsError`
     - `versionMismatch_recordsWarning`
     - `duplicateId_recordsError`
     - `badEntry_recordsFailedStatus`
     - `disabled_mod_statusIsDisabled`
     - `enabled_mod_countsArePresent`
     - `rescan_clearsOldDiagnostics`
     - `orphanDiagnostics_excludedFromAllMods`
   - 扩展 `LuaModEntryTest`: `entry_withRuntimeError_recordsError`。
   - 扩展 `ModScannerTest`: 验证 `ScanResult.diagnostics` 包含预期 key。

9. 运行 `./gradlew :core:test`。

10. codex 评审:必须 `assign("codex_reviewer", ...)`;如果 assign 失败或 reviewer 不可用,跳过该评审阶段并在最终回报给 dispatcher 裁决,不要直接调用 codex-cli/codex exec。

## Acceptance
- [ ] 正常 mod 在 UI/registry diagnostics 中显示 loaded。
- [ ] 坏 manifest / entry.lua 错误能被 diagnostics 捕获并可查询。
- [ ] WndModManager 能显示 mod 的 status 与错误/警告入口。
- [ ] 详情中能看到 id/name/version/origin、errors/warnings、注册内容 counts。
- [ ] 旧启停/导入/卸载 UX 不回归。
- [ ] diagnostics 不改变 C3 默认 gameplay 行为。
- [ ] `./gradlew :core:test` 通过。
- [ ] 不提交 `.claude/`,不使用 `git add -A`。

## Notes
- 先做最小有用:错误可见、状态明确、计数粗略准确。不要做排序/过滤/搜索/复杂日志 viewer。
- UI 文案沿用 fork modding 窗口的硬编码 ZH/EN fallback 策略,不要新增依赖 properties 的跨包 Messages。
- 计数按“加载阶段成功注册数”统计,不是按最终 registry owner 精确归属;这是当前架构下最稳健的实现。
