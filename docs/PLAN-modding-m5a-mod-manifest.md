# PLAN: M5a — mod 清单 + 版本门(ModScanner / ModManifest / ModRegistry)

## Goal

建立 SPD×Remixed modding 平台的 **mod 清单机制**:定义 `mod.json` 格式,扫描 `assets/mods/<id>/mod.json`,做**版本兼容门**(`spd_version` vs `Game.versionCode`),维护 `ModRegistry`(已发现 mod + enabled 状态,持久化)。为 M5b(开关 UI)和 M5c(默认关闭打包)打地基。

**M5a 是纯加法**:不碰 LuaEngine 脚本加载机制,不重组现有 `assets/scripts/`,不移动 `test_safezone.json`。只新增元数据层。

## Context

M0-M4 完成了 Lua modding 闭环(item/mob/ally/hero/spell/npc/shop/level 全部可 Lua 定义),但**没有 "mod" 概念**:
- Lua 脚本按类型散在 `assets/scripts/{items,mobs,npcs,shops,allies,heroes,spells}/`(扁平,无包归属)
- `assets/mods/` 只有 `levels/test_safezone.json`(裸关卡定义,无清单)
- `LuaEngine` 启动时无差别扫所有 `scripts/<type>/*.lua` 注册,**无版本检查、无启停开关、无包隔离**

后果:
1. 无法区分"原版内置"vs"扩展内容"——所有 Lua 内容总是加载
2. 版本升级(versionCode 变)后,旧 Lua 脚本可能跑挂(无门)
3. 玩家无法关扩展包(C3 回归基线守不住,M5c 验收依赖此)

M5a 解决 1+2(清单 + 版本门),M5b 解决 3(开关 UI),M5c 收拢默认关闭 + 平衡回归。

**调研要点**(worker Phase 1 先做):
- SPD JSON 解析:看 `modding/DataDrivenLevel.fromJsonValue`(M4a,libgdx JsonValue 模式)
- libgdx 目录扫描:`Gdx.files.internal("mods").list()`(同目录列举,注意 headless 测试 Gdx.files 可能 null)
- prefs:`SPDSettings extends GameSettings`(`SPD-classes/.../watabou/utils/GameSettings.java`),`getInt/getBoolean(KEY, default)` + 对应 setter。M5a 直接用 `GameSettings.getBoolean("mod_enabled_<id>", default)`(C2 OK:用上游公共 API,不散回)
- 版本号:`Game.versionCode`(SPD-classes `com.watabou.noosa.Game:57`,public static int,=896)。确认 ModRegistry.scan 调用时 versionCode 已设(Game 构造时)
- 现有测试 mock 模式:看 `LuaItemTest`/`LuaNpcTest` 等 headless 测试如何初始化 LuaEngine + 是否需 prefs mock

## Files

- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModManifest.java`(新)— mod.json POJO + `fromJson(JsonValue)`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModScanner.java`(新)— 扫 `assets/mods/*/mod.json` + 版本门
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModRegistry.java`(新)— 单例,scan + enabled 状态(GameSettings prefs 持久化)
- `core/src/main/assets/mods/test_mod/mod.json`(新)— 示例清单(M0-M4 测试内容的占位归属,M5c 才真正收拢脚本进此包)
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModScannerTest.java`(新)— 扫描 + 版本门 + Registry round-trip
- **上游改动**:0(纯 modding/ 加法)。GameSettings / Game.versionCode / Gdx.files 是正当用上游 API。

## mod.json 格式(定稿)

```json
{
  "id": "test_mod",
  "name": "测试内容包",
  "version": "0.1.0",
  "spd_version": 896,
  "author": "fork",
  "default_enabled": false,
  "description": "M0-M4 测试内容(剑/NPC/商店/城镇)的占位清单"
}
```

字段:
- `id`(必填,string,`[a-z0-9_]+`)— mod 唯一标识,作 prefs key 后缀
- `name`(必填,string)— 显示名
- `version`(必填,string,sem-ish)— mod 自身版本
- `spd_version`(必填,int)— 兼容的 SPD versionCode,**必须 == `Game.versionCode`** 才接受(MVP 严格匹配,不做迁移)
- `author`(可选,string)
- `default_enabled`(可选,bool,默认 false)— 首次发现的 enabled 默认值
- `description`(可选,string)

解析容错:缺必填 / 类型错 / id 非法 → log + skip 该 mod(不抛,不拖垮启动)。

## ModScanner 行为(定稿)

```
scan():                                     # runtime entry, LuaEngine.init 后可调
  if Gdx.files == null:                     # headless 早启 / 异常态兜底
    safeLog("Gdx.files null, mod scan skipped")   # Gdx.app null → System.err,不 NPE
    return []
  return scanDir(Gdx.files.internal("mods"))

scanDir(FileHandle baseDir):                # 真实逻辑 + 测试 seam
  mods = []
  if baseDir == null || !baseDir.exists():
    return mods                             # 无 mods 目录 = 空,不报错
  children = baseDir.list()
  sort children by child.name()             # 文件系统 list 顺序非稳定契约,排序去 flaky
  seen = new HashSet<String>()              # 重复 id 防御
  for child in children:
    if !child.isDirectory(): continue       # 顶层非目录文件 skip
    manifestFile = child.child("mod.json")
    if !manifestFile.exists() || manifestFile.isDirectory(): continue  # mods/levels/ 等无清单/坏清单 skip
    try:
      json = new JsonReader().parse(manifestFile.readString("UTF-8"))
      m = ModManifest.fromJson(json)        # 必填校验 + id 正则 + spd_version>0,失败抛
      if !m.id.equals(child.name()):        # id ↔ 目录名一致性
        safeLog("mod dir %s id %s mismatch, skip", child.name(), m.id); continue
      if !seen.add(m.id):                   # 重复 id 二重保险
        safeLog("duplicate mod id %s, skip", m.id); continue
      if m.spd_version != Game.versionCode:
        safeLog("mod %s spd_version=%d != %d, skip", m.id, m.spd_version, Game.versionCode)
        continue                            # 版本门:不匹配 skip
      mods.add(m)
    catch e:
      safeLog("mod manifest parse fail: " + child.path(), e)
      continue                              # 单 mod 解析失败不拖垮整体
  return mods
```

**关键决策**:
- **严格版本匹配**(`==`,不做 `<=` 或范围)——MVP 简单,SPD versionCode 单调推进,旧 mod 该拒绝。codex round-1 可评估是否需 `spd_version_min/max` 范围。
- **`mods/levels/` 这类无 mod.json 的目录自然 skip**(`child.child("mod.json").exists() == false`)——不破坏现有 `test_safezone.json` 位置(M5c 才收拢)
- **测试 seam = `scanDir(FileHandle)` overload**(非 `IFileHandleResolver`):目录列举 + `.child("mod.json")` 走 FileHandle 链,resolver 接口不自然。`scan()` null-guard `Gdx.files` 后 delegate 到 `scanDir(Gdx.files.internal("mods"))`;测试用 TemporaryFolder 建内存 fs 调 `scanDir(fixtureDir)`(沿用 `SaveSlotIOHeadlessTest.TmpRedirectFiles` 惯例)
- **`scan()` 运行时 Gdx.files null 兜底**:log + 返回空(不 NPE 崩)

## ModRegistry 行为(定稿)

```
class ModRegistry:
  private static List<ModManifest> scanned = []
  private static boolean initialized = false

  static void scan():                          # 启动调一次(LuaEngine.init 末尾 或 显式)
    scanned = ModScanner.scan()
    initialized = true

  static List<ModManifest> all():
    if !initialized: scan()
    return scanned

  static ModManifest get(id): ...

  static boolean isEnabled(id):
    m = get(id); if m == null: return false
    return GameSettings.getBoolean("mod_enabled_" + id, m.default_enabled)

  static void setEnabled(id, enabled):
    GameSettings.put("mod_enabled_" + id, enabled)
```

**关键决策**:
- **prefs key**:`mod_enabled_<id>`(命名空间隔离,不撞 SPDSettings 现有 key)
- **default_enabled 来自清单**(M5c 会把测试内容标 `default_enabled: false`,原版体验默认零 Lua 内容)
- **scan 幂等**:重复调替换 scanned 列表
- **LuaEngine 接入留 M5b**:M5a 只建 Registry,不改 LuaEngine。M5a 让 `all()` 自触发 scan(lazy),单测能跑通;M5b 让 LuaEngine.loadXxxScripts 按 `isEnabled` 过滤,并在 `ShatteredPixelDungeon.create()` 的 `LuaEngine.init()`(ShatteredPixelDungeon.java:83)旁显式调一次 `ModRegistry.scan()`。
- **测试隔离**:GameSettings 无 `clear()`(私有 static prefs 缓存,无 reset)。测试在 `@Before` 调 `GameSettings.set(freshFakePrefs)` 注入 HashMap-backed `Preferences`,隔离 mod_enabled_* 状态

## Steps

### 1. 调研(已完成,产出笔记)

**已核实事实**:
- **JsonValue 解析模式**(M4a `DataDrivenLevel.fromJsonValue`,core/.../modding/DataDrivenLevel.java:98-137):必填 `root.getInt("k")`(缺则 libgdx 抛 IllegalArgumentException)、显式必填 `root.require("k")`、可选带默认 `root.getBoolean("k", def)`、可选数组 `root.get("k")` 返 null 后 guard。校验失败一律 `IllegalArgumentException`(不 NPE)。M5a `ModManifest.fromJson` 照此:`getInt("spd_version")` 必填、`getBoolean("default_enabled", false)` 可选、`getString("author", "")` 可选。
- **JsonReader**:`new JsonReader().parse(text)`(DataDrivenLevel.java:147)或 `.parse(FileHandle)`(libgdx 支持,内部 readString)。M5a 用 `new JsonReader().parse(manifestFile)`。
- **GameSettings**(SPD-classes/.../watabou/utils/GameSettings.java):libgdx `Preferences` 后端(非 java.util.prefs),`get().flush()` 急写。API:`getBoolean(k, def)`(无单参 overload)、`put(k, boolean)`、`put(k,int)`、无 `clear()/remove()`。私有 `static Preferences prefs` 懒初始化,可 `GameSettings.set(prefs)` 注入(测试 seam)。headless 下 `Gdx.app.getPreferences("settings.xml")` 返 `HeadlessPreferences`(HashMap 后端),可工作;但跨测试共享,需注入 fake 隔离。
- **Game.versionCode**(SPD-classes/.../noosa/Game.java:57):`public static int`,无初始值(默认 0)。由各平台 launcher 在构造 Game 前赋值(AndroidLauncher:116 / DesktopLauncher:124 等),`create()` 前必设。headless 测试需自设(`Game.versionCode = <n>`,沿 DataDrivenLevelTest/SaveSlotIOHeadlessTest 惯例)。
- **LuaEngine.init 调用点**:`ShatteredPixelDungeon.create():83`(`LuaEngine.init()` 唯一调用),此时 Gdx.files 已活、Game.versionCode 已设——M5b 接入 `ModRegistry.scan()` 的天然位点。M5a 不动此文件。
- **headless test 惯例**(15 个既有测试,185 @Test):全部 `@BeforeClass` 建 `HeadlessApplication(new ApplicationAdapter(){}, config)`(自动装 Gdx.app/files/graphics)。`core/build.gradle` 把 `src/main/assets` 加进 test resources srcDirs → `Gdx.files.internal("mods")` 在测试可解析。`DataDrivenLevelTest` 用内存 JSON string + `JsonReader` 测解析,不读盘;`SaveSlotIOHeadlessTest` 用 `TemporaryFolder` + 自定义 `Files` 注入测 Local 路径。
- **assets/mods 现状**:`mods/levels/test_safezone.json` 是唯一文件,**无任何 mod.json**。M5a 新增 `mods/test_mod/mod.json`(与 `levels/` 并列)。
- **test 基线**:15 文件 / 185 @Test(`grep -rh "@Test"` 核实)。

### 2. ModManifest

- POJO(`public final` 字段):`String id, String name, String version, int spd_version, String author, boolean default_enabled, String description`
- `static ModManifest fromJson(JsonValue v)`:
  - `v == null` → `IllegalArgumentException("manifest json is null")`
  - 必填:`id = v.getString("id")`、`name = v.getString("name")`、`version = v.getString("version")`、`spd_version = v.getInt("spd_version")`(任一缺 → libgdx 抛 IllegalArgumentException,ModScanner catch)
  - 可选带默认:`author = v.getString("author", "")`、`default_enabled = v.getBoolean("default_enabled", false)`、`description = v.getString("description", "")`
  - id 正则 `^[a-z0-9_]+$`(作 prefs key 后缀,禁特殊字符),非法 → `IllegalArgumentException`(让 ModScanner catch + skip)
  - `spd_version <= 0` → `IllegalArgumentException`(防负数/0;`Game.versionCode==0` 是配置错态,接受 `spd_version:0` 会掩盖坏初始化)
- 构造器包级私有,`fromJson` 是唯一构造路径

### 3. ModScanner

- `static List<ModManifest> scan()`:null-guard `Gdx.files`(== null → safe-log + `return Collections.emptyList()`);否则 `return scanDir(Gdx.files.internal("mods"))`。**safe-log**:`Gdx.app != null` 用 `Gdx.app.error`,否则 `System.err.println`(防早启 Gdx.app 也 null 时 NPE,守住"不拖垮启动")
- `static List<ModManifest> scanDir(FileHandle baseDir)`:真实遍历逻辑。`baseDir == null || !exists()` → 空列表。子项按 `child.name()` **排序后**遍历(文件系统 list 顺序非稳定契约,排序让 M5b UI/测试不 flaky)。只处理 `isDirectory()` 的 child
- manifest 探测:`manifestFile = child.child("mod.json")`;`manifestFile.exists() && !manifestFile.isDirectory()` 才尝试解析(`mod.json` 是目录则 skip,意图清晰)
- **id ↔ 目录名一致性**:解析得 `m` 后校验 `m.id.equals(child.name())`,不一致 → log + skip(防 `mods/foo/mod.json` 声明 `id:"bar"` 污染 prefs key / UI label / 未来打包)
- **重复 id 防御**:维护 `Set<String> seen`,同次 scan 内重复 id → log + skip 第二个(id==dirname 已天然防跨目录重复,seen 是二重保险;scan 幂等重跑不影响)
- 版本门:`m.spd_version != Game.versionCode` → log + skip
- 容错:per-mod try/catch,`new JsonReader().parse(manifestFile)` + `ModManifest.fromJson` + id 校验 + 版本门全在 try 内;失败 safe-log + continue(单 mod 失败不拖垮整体)

### 4. ModRegistry

- 静态 `scan()` / `List<ModManifest> all()`(lazy:首次调 scan)/ `ModManifest get(String id)` / `boolean isEnabled(String id)` / `void setEnabled(String id, boolean enabled)`
- `isEnabled`:`ModManifest m = get(id); return m != null && GameSettings.getBoolean("mod_enabled_" + id, m.default_enabled)`
- `setEnabled`:`GameSettings.put("mod_enabled_" + id, enabled)`(急写 flush)
- 内部 `private static volatile List<ModManifest> scanned`(初始空 list,避免 null);`private static volatile boolean initialized`
- `scan()` 覆盖 scanned + 置 initialized=true;`all()` 若 !initialized 触发 scan
- 测试 seam:`ModRegistry` 不显式 clear(留给测试通过 `GameSettings.set(freshFakePrefs)` + 重置 `initialized=false` 的包级访问,或 `scan()` 重扫)

### 5. test_mod/mod.json

- `assets/mods/test_mod/mod.json`:`spd_version = 896`(= 当前 `appVersionCode`,build.gradle:17),`default_enabled = false`,`id = "test_mod"`
- **不移动脚本**(M5c 做),M5a 只建清单占位

### 6. 测试 `ModScannerTest`(headless,目标 ≥6 @Test)

Bootstrap:沿 `DataDrivenLevelTest` 惯例 `@BeforeClass` 建 `HeadlessApplication`;`Game.versionCode = 896`(或读 `System.getProperty("spd.appVersionCode")`)。`@Before` 注入 fresh HashMap-backed fake `Preferences`(`GameSettings.set(fake)`)隔离 mod_enabled_*。fixture 策略用 `TemporaryFolder`(沿 `SaveSlotIOHeadlessTest`):建 `modsDir/<id>/mod.json` 写内容,调 `ModScanner.scanDir(modsDir)`。

- `scan_findsTestModManifest`:`ModScanner.scanDir(new FileHandle("<core>/src/main/assets/mods"))`(或 classpath `Gdx.files.internal("mods")`)→ 含 test_mod,id/name/spd_version 正确,**levels 不在列表**(无 mod.json skip)
- `scan_rejectsVersionMismatch`:TemporaryFolder 建 mod,`spd_version=999`(≠896)→ scanDir → 不在列表
- `scan_acceptsMatchingVersion`:fixture mod `spd_version=896`(==Game.versionCode)→ 在列表
- `scan_skipsDirWithoutManifest`:fixture 含一个无 mod.json 的子目录 → 不报错,其他 mod 仍扫到
- `scan_skipsMalformedManifest`:fixture 含坏 JSON(缺必填 / id 非法 / spd_version<=0 / 类型错)→ 该 mod skip,同目录其他 mod 不受影响
- `scan_rejectsIdDirnameMismatch`:fixture `mods/foo/mod.json` 声明 `id:"bar"` → scanDir → foo skip(codex round-1 must-fix)
- `scan_skipsDuplicateId`:fixture 两个目录同名 id(因 id==dirname 强制,需构造 `mods/a/mod.json`+`mods/a2/mod.json` 都写 `id:"a"` 但 a2 目录名是 a2 → a2 被 id-mismatch 拒;另测 seen 防御用合法同 dirname)→ 仅一个入选(codex round-1 must-fix)
- `scan_nullGdxFiles_returnsEmpty`:临时置 `Gdx.files=null` → `ModScanner.scan()` 返回空不 NPE,随后还原
- `registry_isEnabled_usesDefaultOnFirstRead`:scan 含 test_mod(default_enabled=false)→ `isEnabled("test_mod")==false`
- `registry_setEnabled_persistsViaPrefs`:`setEnabled("test_mod",true)` → `isEnabled("test_mod")==true`(GameSettings round-trip,fake prefs)
- `registry_scan_isIdempotent`:两次 scan 列表 size/id 一致
- 回归:185 既有测试零回归(M5a 新增 ≥6 后总数 ≥191)

### 7. codex 评审 + 回归验证

- `codex exec --sandbox read-only`(CAO codex_reviewer 管道对 v0.142.0 失效,沿用 M4d workaround:codex CLI 直驱,smoke 过即采用)
- `./gradlew :core:test` 全过(185 既有 + M5a 新增)
- C3 基线:零上游改动 → 原版一周目不受影响

## Acceptance

- ✅ `assets/mods/<id>/mod.json` 格式定义清晰,M5b/c 可复用
- ✅ `ModScanner` 扫描 `assets/mods/*/mod.json`,跳过无清单目录(如 `mods/levels/`),不破坏现有 `test_safezone.json`
- ✅ **版本门**:`spd_version != Game.versionCode` 的 mod 被 skip + log(不抛)
- ✅ `ModRegistry.isEnabled/setEnabled` 通过 GameSettings prefs 持久化,default 来自清单
- ✅ 零上游改动,零 LuaEngine 行为改动(M5a 不影响 m0-m4 内容加载)
- ✅ ≥5 单元测试:扫描 + 版本门 + Registry round-trip
- ✅ 185 既有测试零回归
- ✅ codex_reviewer APPROVED

## 风险 + 注意

- **R1: headless Gdx.files**(已解决)。`core/build.gradle` 已把 `src/main/assets` 加进 test resources,`HeadlessApplication` 装 `HeadlessFiles` → `Gdx.files.internal("mods")` 在测试可解析(真实文件,非 jar 内)。`scan()` 仍 null-guard(防异常早启)。版本门/坏清单测试用 `TemporaryFolder` fixture 调 `scanDir(FileHandle)`,不依赖 classpath。
- **R2: prefs 测试初始化**(已解决)。`HeadlessApplication` 提供 `HeadlessPreferences`(HashMap 后端),`GameSettings` 可工作;但私有静态缓存跨测试共享且无 reset。**对策**:测试 `@Before` 注入 fresh HashMap-backed fake `Preferences`(`GameSettings.set(fake)`)隔离 mod_enabled_* 状态。
- **R3: versionCode 时序**(已核实)。各平台 launcher 在 `create()` 前设 `Game.versionCode`;`LuaEngine.init()` 在 `ShatteredPixelDungeon.create():83`,此时 versionCode 已设。headless 测试自设(`Game.versionCode = 896`)。版本门测试显式设 versionCode 控制 accept/reject。
- **R4: C2 包隔离**。所有新类进 `modding/` 子包(与 DataDrivenLevel 等 M4 fork 类同包)。ModScanner/ModManifest/ModRegistry 是 fork 新增,不散回上游。GameSettings/Game.versionCode/Gdx.files 是正当用上游 API(不算污染)。
- **R5: 不重组脚本**。M5a **不动** `assets/scripts/` 结构,不移动 `test_safezone.json`。只加 `mods/test_mod/mod.json` 占位清单。脚本收拢是 M5c。
- **R6: scan 调用点**(已定)。M5a 让 `ModRegistry.all()` lazy 自触发 scan,单测可跑。接入 `ShatteredPixelDungeon.create()` 显式调 `ModRegistry.scan()` 的 1 行留 M5b(M5b 才需要 LuaEngine 按 enabled 过滤)。M5a 不改 LuaEngine / ShatteredPixelDungeon。
- **R7: logLevel 过滤**。SPD 默认 libgdx logLevel=2(LOG_ERROR),`Gdx.app.log`(INFO)被过滤。ModScanner 错误用 `Gdx.app.error(TAG, msg, exception)` 保证可靠输出(见 CLAUDE.md 调试约定)。

## 参考

- M4a `modding/DataDrivenLevel.fromJsonValue`(JsonValue 解析模式)
- SPD-classes `com.watabou.noosa.Game:57`(`public static int versionCode`)
- SPD-classes `com.watabou.utils/GameSettings.java`(prefs:`getBoolean/put/getInt`)
- `core/.../SPDSettings.java`(GameSettings 子类示例)
- M0-M4 `LuaEngine.java`(`register_*` global + `loadXxxScripts`,M5b 将按 ModRegistry.enabled 过滤)
- modding 范式 + 约束 C1-C5 + CLAUDE.md

## Codex Review Log

### Round 1(PLAN,`codex exec --sandbox read-only`,smoke 过即采用)

结论:**plan sound overall**,3 must-fix + 3 nice-to-have,全部采纳:

- [must-fix] **id ↔ 目录名一致性**:解析后校验 `m.id.equals(child.name())`,不一致 skip+log。防 `mods/foo` 声明 `id:"bar"` 污染 prefs key/UI/打包。
- [must-fix] **重复 id 行为**:id==dirname 天然防跨目录重复;再加 `Set<String> seen` 二重保险。
- [must-fix] **Gdx.files==null 守卫的日志**:早启态 `Gdx.app` 可能也 null,`Gdx.app.error` 会 NPE 违反"不拖垮启动"。改 safeLog:`Gdx.app!=null` 用 error,否则 `System.err.println`。
- [nice-to-have] **排序 `baseDir.list()`**:list 顺序非稳定契约,排序去 flaky(M5b UI/测试)。
- [nice-to-have] **`mod.json` 是目录则 skip**:`exists() && !isDirectory()` 显式表达意图。
- [nice-to-have] **`spd_version <= 0` 拒绝**(不只 `<0`):`versionCode==0` 是配置错态,拒绝 `spd_version:0` 避免掩盖坏初始化。

采纳后 PLAN 更新:ModScanner 伪码 + Steps §2/§3 + 测试 §6(reflect 上述)。Round-2 不再跑(smoke 过即采用,沿用 M4d workaround)。

### Round 2-3(实现评审,共 3 轮,2 must-fix 已修 + 1 非可操作 finding 记录)

- [Round2 must-fix,已修] **类型严格解析**:libgdx `getString`/`getInt` 静默 coerce 错误类型(数字 id / 字符串 spd_version / 字符串 default_enabled)。改 `isString()`/`isLong()`/`isBoolean()` 门 + `requireString`/`optionalString`/`optionalBoolean` helper。+4 测试。
- [Round2 must-fix,已修] **`ModRegistry.get(id)` 不 lazy-scan**:原直接遍历未初始化的 `scanned` 返 null。改 delegate 到 `lookup(id)`(与 `isEnabled` 同路径)。
- [Round3 must-fix,已修] **int 窄化绕过版本门**:`asInt()` 经 `l2i` 静默截断超范围 long,`spd_version:4294968192`(2^32+896)会 wrap 成 896 通过门。改 `asLong()` + 范围校验 `[1, Integer.MAX_VALUE]` 再窄化。+1 测试。
- [Round3 must-fix,非可操作,记录] **非规范 JSON 数字 token**(`0896`/`+896`):libgdx lenient JsonReader 把 `0896` 解析为整数 896。**判定非 bug**:版本门比较整数 *值*,声明值(896)== 评估值(896)== versionCode(896),作者声明 0896 即意指 896,无完整性绕过(恶意作者可直接写 `896`)。且 JsonValue 不暴露原始 token,在值层无法区分 `0896` vs `896`,要拒绝须弃用 JsonValue 改 raw-text 解析, disproportionate。`manifest_acceptsNonCanonicalDecimalSpdVersion` 测试显式文档化此 intentional 行为。若未来要求严格 JSON,需切严格解析器(留 M5b+ 评估)。

## Pending Issues

- **严格 JSON 数字 token**(codex round-3 提出,worker 判定非可操作):`0896`/`+896` 等非规范 token 被 libgdx lenient 解析为同值整数。当前按版本门 `==` 值比较判定正确(声明值==评估值)。若产品要求 strict JSON,需在 ModScanner 引入严格数字校验(raw-text 层),非 M5a 范围。
