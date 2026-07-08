# PLAN: Mod 创作者文档(docs/MOD-AUTHORING.md)

## Goal
写一份面向**第三方 mod 作者**的完整创作指南 `docs/MOD-AUTHORING.md`:从 mod.json schema、scripts/ 结构、全部 `register_*` API、`RPD.*` 运行期 API 参考,到一个端到端最小 mod 教程(写 → 打包 zip → 导入 → 重启生效)。让 fork 的 modding 平台**可被外部使用**(目前只有零散的 PLAN + test_mod 示例,无面向创作者的单一入口)。**纯 docs**,零代码风险,与 M12b/c/d/M11e 零冲突。

## Context
- M0-M12 累计铺了完整 Lua modding 平台(register_item/mob/buff/spell/talent/level/painter/trap + RPD.* id/cell/char/item/buff/mana/talent/shield/terrain API + zip import)。但**文档分散在各 PLAN**,创作者无单一入口。
- M12 让 mod 可分发(zip import:desktop M12b / android M12c),创作者文档必须覆盖「打包 + 导入」闭环。
- 准确性要求:API 参考**必须从代码核对**(LuaEngine 的 register_* 函数签名、RpdApi 的方法列表、ModManifest 字段、ModScanner 校验规则),不能凭记忆写错签名(创作者照抄会运行期静默失败)。

**设计决策**:
- 单文件 `docs/MOD-AUTHORING.md`,长但自包含(创作者一处读完)。
- **API 参考从代码生成/核对**:worker 读 `LuaEngine.java`(各 `RegisterXxxFunction` 的 table 字段期望)+ `RpdApi.java`(全部 `addFunction` 注册的方法)+ `ModManifest.java`(fromJson 字段)+ `ModScanner.java`(校验规则)。
- 包含 M12 分发闭环:zip 结构(mod.json 在根 / 唯一顶层 dir)、desktop/android 导入步骤、restart 生效契约、external vs builtin(origin 角标)。
- 示例用真实的 correct lua(参考 test_mod + M6 PoC),不是伪代码。

## 文档结构(docs/MOD-AUTHORING.md)
1. **Quick Start**:5 行 mod.json + 1 个 register_item,跑起来。
2. **mod.json Schema**:全字段(id 正则 `^[a-z0-9_]+$` / name / version / spd_version=versionCode / author? / default_enabled? / description? / entry? / balance?) + 校验规则(id==dirname、spd_version 必须匹配、entry 路径约束)。来源:`ModManifest.java` + `ModScanner.java:161-184`。
3. **目录结构**:`mods/<id>/{mod.json, entry.lua, scripts/{items,mobs,buffs,spells,allies,heroes,npcs,shops,talents,painters,traps}/*.lua}`。来源:`LuaEngine.loadXxxScripts` 的 subdir 约定。
4. **register_* API 参考**(逐个,含 table 字段 + 示例):register_item / register_mob / register_buff / register_spell / register_talent(tier1-4 + on_upgrade)/ register_level / register_painter / register_trap。来源:`LuaEngine.java` 各 RegisterXxxFunction + 对应 LuaXxx wrapper 的回调槽。
5. **RPD.* 运行期 API 参考**(分组):id/cell(charAtCell/cellDistance/emptyCellNextTo/blink)、char(hp/pos/enemyOf)、item(giveItem/removeBackpackItem/itemName/randomBackpackItem)、buff(affectBuff/removeBuff/setBuffLevel)、mana(getMana/setMana/spendMana)、talent(pointsInTalent)、shield(addShield/absorbShield/charShield)、terrain(terrain/setTerrain/isWall/isSolid/dig/dropItem)、blob(glog/placeBlob/addImmunity)。来源:`RpdApi.java` 全部 addFunction。
6. **生命周期 & 回调**:entry.lua 何时跑、scripts 何时加载、restart 生效契约(M12a)、mod enabled/disabled 切换。
7. **安装 & 分发(按 M12a 代码实状)**:当前唯一分发方式 = 手动把 `<id>/` 文件夹放进可写 `mods_user/`(Android=app 内部 files dir、desktop=工作目录,`Gdx.files.local` 无需权限);放好后**重启** → 扫描 → 模组管理器勾选 → 再重启生效。zip 打包建议(zip 根放 `<id>/mod.json+scripts/`,或唯一顶层 dir),玩家解压到 `mods_user/`。origin 角标 `[内建]`/`[外部]` 区分来源。**zip 一键导入(desktop JFileChooser / android SAF)属 M12b/c 规划,尚未实现** —— 本节明确标注「计划中」,不写未实现的 cap/路径穿越规则为当前行为。
8. **调试 & 常见错误**:SPD logLevel=2 默认过滤(用 Gdx.app.error/GLog)、Gdx.app.log 不显示、mod 不生效先查 enabled + restart、id 冲突 builtin 胜。
9. **完整示例**:最小可玩 mod(mod.json + entry + 1 item + 1 mob + 1 spell),参考 test_mod / regression_demo(M11e)。

## Files (worker — 已核对 2026-07-08)
- **`docs/MOD-AUTHORING.md`**(新,主交付物):上述 9 节,API 参考从代码核对。
- (可选)`docs/MOD-AUTHORING-CHEATSHEET.md`:1 页速查。**决定不做** —— 主文档第 4/5 节本身就是分块速查表,单独建文件会 drift。
- 不改任何 .java / assets / 测试(纯 docs)。

### 代码核对结论(权威来源,写文档以此为准)
- **mod.json schema**:`ModManifest.fromJson`(`ModManifest.java:88-128`)。必填 `id`(正则 `^[a-z0-9_]+$`)/`name`/`version`/`spd_version`(int,`[1, Integer.MAX_VALUE]`,须 `== Game.versionCode` 即 896);选填 `author`/`default_enabled`(默认 false)/`description`/`entry`(.lua 相对路径,禁 `/` 首/反斜杠/`..`)/`balance`(object,键→数字)。`origin`/`baseDir` 是运行时字段(scanner 设,不进 mod.json)。
- **校验规则**:`ModScanner.scanChildren`(`ModScanner.java:147-187`)。`id` 必须等于目录名;同 channel 重复 id skip;`spd_version != versionCode` skip;`scan()` 合并 builtin(`assets/mods/`)+ external(`mods_user/`),id 冲突 **builtin 胜、external skip+log**;排序按目录名字典序。
- **register_* 共 13 个**(PLAN 原 8 个漏了 5 个,必须全列):`register_item`/`register_mob`/`register_ally`/`register_hero`/`register_spell`/`register_npc`/`register_shop`/`register_level`/`register_buff`/`register_talent_override`/`register_talent`/`register_painter`/`register_trap`。各必填/选填字段见 `LuaEngine.java:574-1069`(已逐个核对,见文档第 4 节)。
- **脚本子目录**(11 个,`LuaEngine.loadXxxScripts`):`scripts/{items,mobs,allies,heroes,spells,npcs,shops,buffs,talents,painters,traps}/*.lua`。全局 `scripts/init.lua` + 每 mod `entry`(manifest)在子目录脚本之后加载。加载顺序见 `LuaEngine.java:161-189`。
- **RPD.\* 方法**(50+ 个,`RpdApi.build()` `RpdApi.java:142-251`):逐个 call() 签名已核对(见文档第 5 节分组表)。常量表 `RPD.Blobs`/`RPD.Buffs`/`RPD.Terrain`。
- **回调槽**(per-entity,`LuaItemCallbacks` 分发):item→`onUse/onEquip/onDeactivate/attackProc`;mob→`act/attackProc/defenseProc/die/spawn`;ally→`act/attackProc/defenseProc/die`;spell→`onUse/onUseAt`;npc→`onInteract`;buff→`act/attachTo/detach/attackProc/defenseProc/damage/drBonus/speed/tintChar/setGlowing/charSpriteStatus/info/onRestore`;trap→`onActivate`;painter→`paint/decorate`;material→`onUse/onThrow/info`。回调 best-effort:缺失/非数字返回 → fallback。
- **沙箱**(`LuaSandbox.exposedGlobals`):剥离 `io/os/package/debug/luajava/load/loadfile/dofile/loadstring/require/getfenv/setfenv`;**无 `require`/`luajava`**(宿主侧编译脚本,不能跨文件 require)。
- **enable 状态**(`ModRegistry`):prefs key `mod_enabled_<id>`,默认 `default_enabled`;切换 **重启生效**(注册不可逆,无 hot-reload)。
- **分发(当前代码实状 — ⚠ 与原 PLAN 假设不符)**:`ModInstaller.java` **不存在**;无 zip 导入 UI(`JFileChooser`/SAF 全仓零命中)。代码只到 **M12a**(`da6dae7f1`):外部 mod 走可写 `mods_user/`(`Gdx.files.local`,Android=app 内部 files dir、desktop=cwd,**无需存储权限**),手动放 `<id>/mod.json+scripts/` 即被扫描。M12a PLAN 第 53 行明言「完整 UI(import 按钮/错误)留 M12b/c」—— **M12b/c 尚未实现**。
  - **文档处理**:第 7 节按 M12a 实状写(手动放 `mods_user/<id>/` = 当前唯一分发方式),zip 导入标「计划中(M12b/c 路线图),尚未实现」。不把未实现的 zip 契约(256 entry/64MB cap/路径穿越规则)写成当前行为。

### 显式延后
- **API 自动生成**:理想是 javadoc/注解自动生成 API 参考(避免文档 drift)。本 milestone 人工核对 + 在文档顶部标「基于 M12 代码,API 变更时更新」。自动生成留后续(可作为 processor 模块的扩展)。
- **mod 模板项目**:一个 `cookiecutter`/zip 模板让创作者 fork 即用。留后续(可在仓库加 `tools/mod-template/`)。
- **i18n**:文档先中文(项目主语言),英文版留后续。
- **M12b/c zip 导入**:尚未实现(见上),文档只描述规划方向,不展开未实现细节。

## Steps
1. **读代码建 API 事实表**(准确性核心)— **已完成(2026-07-08)**,结论见上「代码核对结论」:
   - `ModManifest.java` + `ModScanner.java` → mod.json schema + 校验 ✓
   - `LuaEngine.java` → 全部 **13 个** RegisterXxxFunction 的 table 字段 ✓
   - `RpdApi.java` → 全部 `rpd.set(...)` 注册的方法(分组 + call() 签名)✓
   - 各 Lua wrapper + `LuaItemCallbacks` → 回调槽 ✓
   - `WndModManager.java` + `ModRegistry.java` + `LuaSandbox.java` → origin 角标 / enable 持久化 / 沙箱边界 ✓
   - **`ModInstaller.java` 不存在**(全仓零命中)→ M12b/c zip 导入未实现,文档按 M12a 实状写 ✓
   - `CLAUDE.md` + `test_mod`/`demo_m58` assets → logLevel/i18n/真实 lua 语法参考 ✓
2. 写 `docs/MOD-AUTHORING.md`(9 节),API 参考逐条对照代码。
3. 交叉核对:随机抽 5 个 register_*/RPD.* 签名,与代码一致(防 drift)。
4. codex 评审(Phase 1 PLAN + Phase 2 doc):重点是**准确性**(签名/schema 与代码一致)+ 完整性(覆盖 M0-M12 全 API)+ 可操作性(教程能跑通)。codex 对文档的评审聚焦事实核对而非风格。
5. (可选)按教程手搓一个最小 mod 验证文档可操作性(非阻塞,代码核对即验收)。

## Acceptance
- [ ] `docs/MOD-AUTHORING.md` 存在,9 节齐全
- [ ] mod.json schema 与 `ModManifest`/`ModScanner` 一致(字段 + 校验规则)
- [ ] 全部 **13 个** register_* API 覆盖,签名与 `LuaEngine` 各 RegisterXxxFunction 一致
- [ ] RPD.* API 分组参考,方法列表与 `RpdApi.build()` 的 `rpd.set(...)` 一致
- [ ] 含分发说明(按 M12a 实状:`mods_user/` 手动安装 + restart + origin 角标;zip 导入标「M12b/c 计划中」)
- [ ] 含完整最小可玩 mod 示例(correct lua,非伪代码)
- [ ] codex 评审:无事实性错误(签名/schema 与代码 drift = must-fix)
- [ ] 与 M12b/c/d/M11e 零文件冲突(只加 docs/)

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- codex 评审用 `codex exec --sandbox read-only`,**不 assign codex_reviewer**(memory:必超时)
- **API 必须从代码核对**,不能凭记忆(test_mod 可能过时;以 LuaEngine/RpdApi/ModManifest 当前代码为准)
- 文档顶部标注「基于 M12a 代码基线」+ 最后更新日期,防未来 drift
- 示例 lua 用 correct 签名(参考 LuaXxx wrapper 回调槽),创作者照抄能跑
- 纯 docs,不改 .java/assets/test
- **⚠ 分发章节按 M12a 实状写**:代码无 zip 导入 UI(M12b/c 未实现);不把未实现的 zip 契约写成当前行为,明确标「计划中」
