# PLAN: M9a — save-slot × modding 交互(mod 启用快照 + 加载检测)

## Goal
让 local save slot 加载时能检测「存档创建时启用的 mod 集」与「当前启用 mod 集」的差异,把当前的**静默降级**(Lua item/buff 在 registry 无表时显示 ??? / 自摘除)变成**显式告知**(GLog 警告 + slot 行标记)。**不重建 registry、不破坏 "Changes apply after restart" 契约**。

## Context
fork 有两套独立系统:local save slot(`save_slots/<name>/`,SaveSlotService)和 Lua modding(LuaEngine + static registry)。M9a 调研确认:

- **registry 是进程级 static 单例**:`LuaItemRegistry`/`LuaBuffRegistry`/`LuaTalentRegistry` 等都是 `static final Map`。`LuaEngine.init()` 在 `ShatteredPixelDungeon.create()` 全程一次,有 `if (initialized) return` 短路。**生产代码从不 clear**(clear 仅供测试)。
- **loadFromSlot 完全不碰 mod registry**(`SaveSlotService.java:133-164`):不调 LuaEngine.init、不调 Registry.clear、不调 ModRegistry.scan。InterlevelScene CONTINUE 走 Dungeon.loadGame 也不重新 init。
- **mod 启用/禁用是进程级状态**(`GameSettings.mod_enabled_<id>`),`WndModManager` 明示 "Changes apply after restart" —— UI 只持久化开关,不重扫脚本。
- **降级兜底已存在且不崩**:
  - `LuaItem.restoreFromBundle`(`LuaItem.java:134-147`):`LuaItemRegistry.getTable(id)` 返回 null → `nameStr = "??? (" + id + ")"`,所有回调 base return。
  - `LuaBuff.act`(`LuaBuff.java:191-194`):`tbl == null` → `detach()` 自摘除;所有回调 `if (tbl == null) return ...` 早退。
- **versionCode 校验三路径已覆盖**(load/export/import,`SaveSlotService` + `SaveSlotIO`),但**只挡整 app 版本,不挡「同版本 mod 集变化」**。
- **GamesInProgress 隔离 OK**:loadFromSlot 已调 `setUnknown(curSlot)`,game{N} 缓存会刷新;Rankings 不带 mod 状态。

**风险场景(M9a 要解决)**:玩家在 slot A 启用 mod X 存档 → 禁用 mod X → 重启 app → 读 slot A → registry 无 mod X → item 变 ???、buff 消失,玩家不知原因。

**设计决策**:
1. **不重建 registry**:mod 启用集是进程级(不是 per-slot)。slot 切换重建 registry 会和 "restart" 契约冲突 + 静态状态时序风险高。保持现状(重启生效)。
2. **加 mod 快照 + 检测 + 告知**:SaveSlotMeta 存「存档时 enabled mod 集」,loadFromSlot 比对当前集,差异时 GLog 警告 + slot 列表标记。
3. **不阻塞加载**:降级兜底已工作,只告知不阻止。

## Files
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/saveslot/SaveSlotMeta.java` — 加 `enabledMods` 字段(`Set<String>`),bundle 兼容(旧 slot 无此 key → 空集,不警告)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/saveslot/SaveSlotService.java` — `saveToSlot` 写快照;`loadFromSlot` 读快照 + 比对 + 暂存 pending 警告;`onGameSceneReady()` 消费 pending 并 `GLog.w`;纯函数 `computeMissingMods`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/saveslot/WndSaveSlotSelect.java` — slot 行显示 mod 状态标记(⚠ 缺 N mod)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaEngine.java` — 加 `activeEnabledModIds()`:在 `initInternal()` 入口(各 loader 循环之前)捕获当前 enabled 集,存 static `LinkedHashSet`,进程级只读。**save 快照与 load 比对都用它**(不用 `ModRegistry.isEnabled` 直读 prefs,避免 pending-restart toggle 污染快照/假阳性)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java` — **第 3 个上游单点 hook**:`create()` 里 `add(log)` 后加 `SaveSlotService.onGameSceneReady();`(消费 pending 警告,此时 GameLog 已重建,warning 不会被 `InterlevelScene.restore()` 的 `wipe()` 清掉)
- 测试:
  - `core/src/test/java/.../saveslot/SaveSlotMetaModSnapshotTest.java`(`computeMissingMods` 纯函数 + Bundle 往返/兼容契约,无需 headless Gdx)
  - `core/src/test/java/.../modding/LuaEngineActiveModsTest.java`(复用 `ModTestSupport`,验证 `activeEnabledModIds` 捕获 init 时集 + post-init toggle 不改变捕获集)

## Steps
1. **SaveSlotMeta.enabledMods 字段**:`public Set<String> enabledMods = Collections.emptySet();`(默认空集)。**注意:SaveSlotMeta 不是 Bundlable**(序列化在 SaveSlotService 手动 Bundle.put/get,见 Step 3/4),只加字段 + import,不加 storeInBundle/restoreInBundle。bundle key `"enabled_mods"`。
2. **LuaEngine.activeEnabledModIds()**(替代原计划的 `ModRegistry.listEnabledModIds`,理由见下「codex round 1 must-fix」):
   - 加 `private static Set<String> activeEnabledModIds = Collections.emptySet();`
   - 在 `initInternal()` 入口(`if (initialized) return;` 之后、各 loader 循环之前)捕获:`LinkedHashSet<String> s = new LinkedHashSet<>(); for (ModManifest m : ModRegistry.all()) if (ModRegistry.isEnabled(m.id)) s.add(m.id); activeEnabledModIds = Collections.unmodifiableSet(s);`
   - `public static Set<String> activeEnabledModIds() { return activeEnabledModIds; }`
   - `resetForTests()` 末尾加 `activeEnabledModIds = Collections.emptySet();`
   - **为什么不用 prefs 直读**:`ModRegistry.isEnabled` 读 prefs(含 pending-restart toggle),未重启时 toggle 会让快照写错或假阳性。`activeEnabledModIds` 是 init 时快照,反映「本进程实际加载到 registry 的 mod」,正是 save-slot 兼容性判断所需。
3. **saveToSlot 写快照**(`SaveSlotService.java` saveToSlot,meta.put 区域 ~:128 saved_at 之后):`meta.put("enabled_mods", LuaEngine.activeEnabledModIds().toArray(new String[0]))`。空集也写(空数组),保证新 slot 一定有 key(只有「升级前老 slot」才无 key)。
4. **readMetaFromPath 读快照**(`SaveSlotService.java:284-304`):`m.enabledMods = b.contains("enabled_mods") ? new LinkedHashSet<>(Arrays.asList(b.getStringArray("enabled_mods"))) : new LinkedHashSet<>()`。**先 `contains` 守卫**(避免 `getStringArray` 走 JSONException → `Game.reportException` 污染日志);`getStringArray` 返回 null → 再兜底空集。旧 bundle 无 key → 空集(向后兼容,**不警告**)。
5. **loadFromSlot 比对 + 暂存 pending 警告**(`SaveSlotService.java:153` copySlotToCurrentGame 后、switchScene 前):
   - `Set<String> missing = missingMods(meta);`
   - `!missing.isEmpty()` → `pendingLoadWarning = formatWarning(missing)`(static 字段,**不直接 GLog.w**,因为 `InterlevelScene.restore()` 会 `GameLog.wipe()` 清掉)
   - 存档无、当前有的 mod(新增)→ 不警告
6. **GameScene hook 消费 pending**(`GameScene.java:501` `add(log);` 后):加 `SaveSlotService.onGameSceneReady();`。`SaveSlotService`:
   - `static String pendingLoadWarning;`
   - `static void onGameSceneReady()`:局部变量取出 pending → 清空 static → 非空时 `GLog.w(it)`。此时 GameLog 已由 `new GameLog()` 重建并订阅 `GLog.update`,warning 进玩家可见日志。
   - `formatWarning(Set<String> missing)`:`LANG_ZH ? "存档引用了当前未启用的 mod: %s,部分功能可能失效" : "Save references mods not currently enabled: %s. Some content may not work."`,`String.join(", ", missing)` 填参。文案用「未启用」覆盖 disabled/removed/不兼容未扫描 三种情况。
7. **纯函数 helper(SaveSlotService,可测)**:
   - `public static Set<String> missingMods(SaveSlotMeta meta)` → `computeMissingMods(meta.enabledMods, LuaEngine.activeEnabledModIds())`
   - `static Set<String> computeMissingMods(Set<String> snapshot, Set<String> current)`:`snapshot` null/empty → 空集(旧 slot 不警告);否则 `LinkedHashSet<String> m = new LinkedHashSet<>(snapshot); m.removeAll(current != null ? current : Collections.emptySet()); return m;`(`current` null → 当空集,防御)。**LinkedHashSet 保序**(日志/测试稳定)。纯函数,不碰 ModRegistry → saveslot 包测试无需 modding fixtures。
8. **WndSaveSlotSelect slot 行标记**(`SlotRow` 构造 ~:442 info 处):`int missing = SaveSlotService.missingMods(meta).size();`,missing>0 时 info 追加 `txt("slot_info_mod_missing", missing)`。txt 新增 key(`TXT_ZH`/`TXT_EN`):
   - ZH:`slot_info_mod_missing` → `"⚠ 缺 %d mod"`
   - EN:`slot_info_mod_missing` → `"⚠ %d mod missing"`
   - 旧 slot(enabledMods 空)→ missing=0 → 无标记。
9. **测试**(两个文件):
   - `core/src/test/java/.../saveslot/SaveSlotMetaModSnapshotTest.java`(纯 JUnit,无需 headless Gdx):
     - `computeMissingMods`:null/empty snapshot → 空;null current → 当空集(防御);snapshot==current → 空;snapshot ⊄ current → 差集(保序);snapshot ⊂ current(当前多)→ 空(只报缺失不报多余);snapshot 含重复 → 去重保序。
     - Bundle 契约:`put("enabled_mods", arr)` → `contains` true → `getStringArray` 等价;无 key → `contains` false(向后兼容守卫)。
   - `core/src/test/java/.../modding/LuaEngineActiveModsTest.java`(复用 `ModTestSupport`,headless + FakePreferences + scanDir):
     - `init` 后 `activeEnabledModIds()` 恰好 = enabled 集;
     - `init` 后再 `setEnabled(test_mod, false)`(模拟 pending toggle 未重启)→ `activeEnabledModIds()` 不变(仍是 init 时集);
     - `resetForTests` 后捕获集清空。

## codex 评审 round 1 must-fix(已并入上文 Steps)
- **GLog 时序**:`loadFromSlot` 里直接 `GLog.w` 会被 `InterlevelScene.restore()` 的 `GameLog.wipe()`(`InterlevelScene.java:737`)清掉,玩家看不到。→ Step 5/6 改为 pending + GameScene hook 消费。
- **enabled 集来源**:`ModRegistry.isEnabled` 读 prefs 含 pending-restart toggle,未重启 toggle 会污染快照/假阳性。→ Step 2 改为 `LuaEngine.activeEnabledModIds()`(init 时捕获,反映实际加载集)。
- 采纳的 nice-to-have:`computeMissingMods` null current 防御;`LinkedHashSet` 保序;文案「已禁用」→「未启用」。

**round 2**:`codex exec` 跑 30+ 分钟 0 输出(疑似卡死,已杀),两个 must-fix 修复均经独立核对源码成立(`InterlevelScene.java:737` wipe 在 restore() 内、CONTINUE 走 restore;`GameScene.java:499` new GameLog();`LuaEngine.initInternal:68` 入口在 loader 循环前)。进实施,阶段 2 codex diff review 兜底。

## codex 评审 阶段 2(diff review)
- **round 1 must-fix**:`pendingLoadWarning` 只在 `missing` 非空时赋值,clean load 不清 → 会泄露上一轮 stale 警告。真 bug。
  - 修复:提取 `stageLoadWarning(Set<String> missing)` 始终赋值(空/null → null),loadFromSlot 调用它。field 改 package-private 便于测试。
  - 回归测试 4 个(setsWhenMissing / clearsOnCleanLoad_noStaleLeak / clearsOnNullMissing / onGameSceneReady_drainsPendingWarning)。
- **confirm round**:codex exec 超时(已杀);must-fix 修复由 437 全绿测试(含 4 个 stale-warning 回归用例)自证。
- 最终:`./gradlew :core:test` 437 green(419 existing + 14 SaveSlotMetaModSnapshotTest + 4 LuaEngineActiveModsTest)。

## Acceptance
- [x] SaveSlotMeta.enabledMods bundle 往返正确(store → restore 等价) — `enabledModsBundle_roundTrip` + `emptyEnabledModsArray_roundTripsEmpty`
- [x] 旧 slot(无 enabled_mods key)加载不崩、不警告(向后兼容) — `legacyBundleWithoutKey_containsFalse_noThrow` + readMetaFromPath contains 守卫 + emptySnapshot_yieldsEmpty
- [x] save 时 enabled mod X → 禁用 X → 重启 → load 该 slot → GLog 出现「未启用的 mod:X」警告(经 GameScene hook,不被 wipe 清掉) — pending→onGameSceneReady 链路 + `stageLoadWarning_setsWhenMissing` / `onGameSceneReady_drainsPendingWarning`
- [x] WndSaveSlotSelect slot 行显示「⚠ 缺 1 mod」(有缺失时);无缺失/旧 slot 无标记 — SlotRow missingMods 条件追加 txt
- [x] mod 集不变时 load 无警告、无标记 — `equalSets_yieldsEmpty` + `extraCurrentMods_notFlagged`
- [x] **pending toggle 未重启**:init 后 toggle mod 不改变 `activeEnabledModIds`,snapshot/标记不被 pending 状态污染 — `pendingToggleAfterInit_doesNotChangeCapturedSet`
- [x] `./gradlew :core:test` 全绿(419 现有 + 新增) — 437 green
- [x] C3 不破(不碰 vanilla loot/spawn pool;本改动只加检测/告知,不重建 registry) — 改动面纯告知,无 registry 重建/loot 改动

## 注意
- **绝不 `git add -A`**:`.claude/` 是 CAO memory artifact,绝不进 commit。只 `git add` 你改动的具体文件。
- **codex 评审用 codex exec workaround**:不要 `assign("codex_reviewer", ...)`(必超时失败 + 造孤儿终端烧配额)。直接 shell 跑 `codex exec --sandbox read-only "<评审本 PLAN 的指令>"`,smoke 通过即采用。
- 新增文案:按 fork i18n 约定,`WndSaveSlotSelect` 的标记用 `txt()` 硬编码 ZH/EN map(不依赖 Messages.get,见 CLAUDE.md)。
