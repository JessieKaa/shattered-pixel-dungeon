# PLAN — M20a: remixed_full Lua traps 内容补全

## Goal
为 `remixed_full` mod 补全 **traps** 内容类型(当前 `scripts/traps/` 缺失)。交付 ≥2 个 remixed 风格 Lua 陷阱,其中 1 个演示 M19a 的 per-instance `data` 持久化。

## Context
- LuaEngine 的 M10b loader 自动扫描 `mods/remixed_full/scripts/traps/*.lua` 并逐个编译 → **worker 只需往该目录加文件,不需要改 entry.lua,不需要手动 require**。
- `register_trap` 已支持 per-instance data(M19a,`onActivate(cell, charId, data)` 第 3 参;`LuaDataCodec` 做 Bundle 往返;旧式 2 参脚本向后兼容)。
- `RemixedFullPackTest` 的硬编码计数只覆盖 item/spell/mob/npc/shop,**不含 trap** → 加 trap 不会破坏它,**不要改它**。
- test_mod 已有 PoC:`scripts/traps/demo_trap.lua`(纯触发)、`scripts/traps/demo_data_trap.lua`(带 data)。
- RpdApi 可用:`RPD.damageChar(charId, amt)`、`RPD.GLog(msg)`、`RPD.Terrain`、`RPD.setTerrain` 等。

## Files
- 新增 `core/src/main/assets/mods/remixed_full/scripts/traps/remixed_full_alarm_trap.lua` — 纯触发型(demo_trap 风格):`onActivate(cell, charId)` 内 `RPD.GLog` 提示 + `if charId ~= 0 then RPD.damageChar(charId, 4) end`。
- 新增 `core/src/main/assets/mods/remixed_full/scripts/traps/remixed_full_charged_dart_trap.lua` — data 型(demo_data_trap 风格):`onActivate(cell, charId, data)`,data 带 `{charges=3, depleted=false}`,每次触发 charges-1 并写回(直接 `data.charges = data.charges - 1`,因 `LuaTrap.activate` 传入的是实例持有的同一 LuaTable,引用语义下原地修改即持久),charges==0 时置 `data.depleted=true` 并走"耗尽"分支(只 GLog 不伤害)。
- 新增 `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RemixedFullTrapContentTest.java` — 两条用例:(1) enable remixed_full + `LuaEngine.init()` 后断言两个 trap 已注册;(2) charged_dart_trap 的 `data.charges` 在 `activate()` 后递减,且跨 `Bundle` round-trip 后仍保持递减值(白盒反射读私有 `data` 字段)。
- **修订(引擎 bug 根因修复)** `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaDataCodec.java` — 实施时发现 M19a 的一个真实 bug,详见下节 `## Discovery`。这是 feature 达成其核心目标(M19a 数字 data 的算术持久化)的必要根因修复。

## Discovery(实施时发现的引擎 bug,已根因修复)
**现象**:charged_dart_trap 的 `data.charges > 0` / `data.charges - 1` 在 Lua 里抛 `attempt to compare string with number`。诊断确认:`LuaDataCodec.deepCopy` 把 spec 里 **number** 类型的 `charges=3` 变成了 **string** `"3"`,而 Lua 算术/比较对字符串报错。
**根因**:`deepCopy` 与 `encode` 两处都在 `isnumber()` **之前** 先判 `isstring()`。在 luaj 里 `LuaNumber`/`LuaInteger` 的 `isstring()` 返回 **true**(数字可被字符串强制转换),于是每个数字都掉进字符串分支被 `tojstring()` 成字符串。
**为何 M19a 的既有测试没发现**:既有 `LuaTrapRegistryTest` 全部用 `toint()` 断言数字值,而 `toint()` 会把数字字符串 `"3"` 解析回 `3`,掩盖了类型翻转。charged_dart_trap 是第一个对持久化数字 data 做**算术**的内容,才暴露。
**修复**:`deepCopy` 与 `encode` 各把 `isnumber()` 分支提到 `isstring()` 之前(各 1 处重排),并加注释说明 luaj 这个坑。**安全**:既有读取全部经 `toint()`/`tojstring()`(对真数字同样有效),Lua 的 `..` 会对数字做强制转换,故无回归;旧存档里被错存为 T_STRING 的数字,`decode`→`toint()` 仍可读,向后兼容。
**验证**:`:core:test` 全绿(701 tests, 0 failures),含 `LuaTrapRegistryTest` 全部 data 用例 + 两条新 trap 用例。


### 命名约定(关键细化,与 pack 一致)
已核实:`register_trap` 注册的是 **原始 `id`**,引擎**不加 mod 前缀**;`remixed_full` pack 的 item/spell/mob/npc 全部把 `remixed_full_` 前缀**写进 lua 的 `id` 字段**(如 `id = "remixed_full_hooked_dagger"`)。因此两个 trap 的 `id` 也写成 `remixed_full_alarm_trap` / `remixed_full_charged_dart_trap`,文件名对应 `remixed_full_alarm_trap.lua` / `remixed_full_charged_dart_trap.lua`,与 pack 命名一致,避免被 reviewer 标为不一致。(dispatcher 原 PLAN 写的 `rf_alarm_trap` 无前缀,与 pack 约定冲突,此为细化修正。)

## Steps
1. ✅ 读 PoC 学 schema(已确认):`register_trap { id=, name=, [color=, shape=, data=], onActivate=function(cell, charId, [data]) ... end }`;旧式 2 参回调向后兼容(luaj 丢弃多余参数)。
2. ✅ 核实 RPD API:`RPD.damageChar(charId, amt)`(走 `Char.damage`,charId=0 时 `resolveChar` 返回 null → 静默 NIL,测试安全)、`RPD.GLog(msg)`(走 `GLog.i` → headless 安全)。无需读 PoC 确认 API 名,已直接读 `RpdApi.java` 确认。
3. ✅ 核实加载链路:`LuaEngine.init() → loadTrapScripts()` 枚举 `mods/<id>/scripts/traps/*.lua` 逐个编译,**无需改 entry.lua、无需手动 require**。
4. ✅ 核实 data 往返:`LuaTrap` 构造时 `data = LuaDataCodec.deepCopy(spec.data)`(实例独立拷贝);`activate()` 把实例的 `data` 作为第 3 参传入(LuaTable 引用语义,原地修改即改实例字段);`storeInBundle`/`restoreFromBundle` 经 `LuaDataCodec` 往返 → **in-memory 修改 + 跨存档均持久**。
5. 写 `remixed_full_alarm_trap.lua`:2 参 `onActivate`,`RPD.GLog("...")` + `if charId ~= 0 then RPD.damageChar(charId, 4) end`。
6. 写 `remixed_full_charged_dart_trap.lua`:3 参 `onActivate`,`data={charges=3,depleted=false}`;`charges>0` 时 `data.charges=data.charges-1`、`charId~=0` 时 `RPD.damageChar(charId,6)`、到 0 置 `data.depleted=true`;`charges<=0` 时走耗尽分支(只 GLog)。
7. 写 `RemixedFullTrapContentTest.java`(复用 `RemixedFullPackTest` 的 headless+enable 模板):
   - `enabled_registersAlarmAndChargedDartTraps`:`enableRemixedFull()`+`LuaEngine.init()` → `assertTrue(LuaTrapRegistry.contains("remixed_full_alarm_trap"))`、`contains("remixed_full_charged_dart_trap")`、`hasAny()`。
   - `chargedDartTrap_chargesDecrementAcrossBundleRoundTrip`:取 `LuaTrapRegistry.getTable("remixed_full_charged_dart_trap")` → `new LuaTrap(spec)` → 反射读 `data.charges` 断言 3 → `activate()` 断言 2 → `storeInBundle`+`restoreFromBundle` 断言仍 2 → 再 `activate()` 断言 1 → 连续 `activate` 直到断言 `data.charges==0` 且 `data.depleted==true`。
8. 跑 `./gradlew :core:test`。已知 flaky:`GeneratorLuaItemTest.luaItemProbabilityPersistsAcrossFullReset` / `GeneratorLuaSpellTest.setLuaSpellDropProbabilityUpdatesLiveDeck` 是概率断言,单独重跑即过,**不当回归**。

## Acceptance
- [ ] `remixed_full/scripts/traps/` 下 ≥2 个 `.lua`(id 为 `remixed_full_alarm_trap` / `remixed_full_charged_dart_trap`),enable remixed_full 后 `LuaTrapRegistry` 注册成功。
- [ ] `RemixedFullTrapContentTest` 两条用例通过(注册 + charges 递减跨 Bundle 往返)。
- [ ] `./gradlew :core:test` 全绿(已知 flaky 重跑)。
- [ ] **未改** `entry.lua`、`RemixedFullPackTest.java`、任何 master 共享测试计数。

## Constraints(强制)
- 只在自己 worktree 内改动;**绝不** 直接动主仓。
- **绝不** 改 `entry.lua`(本方向不需要,M20f 独占它)、`RemixedFullPackTest.java`(M20g 独占它的计数行)。
- **绝不** `git add -A` 或 `git add .`;按文件名 stage。
- **绝不** commit `.claude/` 目录。
- **绝不** force push / reset --hard。

## 评审协议(重要)
完成实现 + 测试绿后,用 **`assign("codex_reviewer", ...)`** 评审(分两阶段:先 PLAN,再实现)。**严禁** 直接调用 `codex` / `codex-cli` / shell exec codex。
- 若 `assign("codex_reviewer")` 失败或评审静默 hang 死 → **跳过评审**,但在回报 dispatcher 时明确说明"codex 评审 assign 失败/静默,已跳过",由 dispatcher 决定是否亲自评审。
- 复用同一个 reviewer terminal:第一次 `assign("codex_reviewer", ...)` 创建,之后用 `send_message(receiver_id=<同一个 reviewer terminal_id>, ...)`。

## 回报协议
完成(或卡点)后,`send_message`(无 `receiver_id`)回报 caller(dispatcher)。回报内容包含:
- 状态:`[DONE]` / `[BLOCKED]`(`[BLOCKED]` 必须带选项 + 你的建议)
- commit hash
- reviewer terminal_id + 评审轮数(或"assign 失败,跳过")
- 测试结果(总数 / 失败数,标注 flaky)
- 改动文件清单
