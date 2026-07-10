# PLAN: M19a — Trap data API + minimal trap content

## Goal
扩展 Lua trap 运行时,让 `register_trap` 能携带 `data` 并在触发时传给 `onActivate(cell, charId, data)`,解除 m17b 中 SpecialBeacon/配置型 trap 因无 data 参数而 no-op 的阻塞。

## Context
m17b 调研确认 remixed 的内容型 trap 中 `SpecialBeacon` 依赖 trap data(目标 mob class/name),但 fork 当前 `LuaTrap.onActivate(cell,charId)` 没第三参数,`LuaTrap` 只持久化 `lua_trap_id`。本 feature 先做最小安全能力:data 的字符串/数字/布尔/table 安全子集持久化 + 触发回调透传。不要一次性实现装备卸装/STR setter/mob beckon 全家桶,避免和后续 API 冲突。

## Design

### data 语义:per-instance(不是 spec 共享)
- `register_trap{ id=..., data=... }` 的 `data` 是 spec table 的一个字段;整个 table 由
  `RegisterTrapFunction` 存进 `LuaTrapRegistry`(现状,**不改 registry**)。
- `LuaTrap` 实例在 **create 时**(`new LuaTrap(tbl)`)把 spec 的 `data` 字段拷贝到实例字段
  `this.data`,此后这份 data 归该实例私有。
- 触发时 `onActivate` 收到的是 **实例的 data**(即 create 时拷贝 / restore 后从 Bundle 重建的),
  不是每次去 registry 重读。这样:(1) 放置后即使 mod 更新/spec 变了,本陷阱保留它生成时的 data;
  (2) 为未来 per-instance 状态变更(陷阱自改 data)铺路。

### create vs restore 的拆分(关键,不能让 restore 用 spec data 覆盖持久化 data)
- 现状 `restoreFromBundle` 调 `hydrate(tbl)` 从 registry 重建 name/color/shape —— 这些是
  **definitional** 字段,该行为保留。
- 但 `data` 是 **per-instance**,必须走 Bundle。所以 `hydrate(tbl)` 保持只处理 definitional
  字段(id/name/color/shape),**不动 data**。
- data 的来源分两路:
  - create(`new LuaTrap(tbl)`):构造器在 `hydrate(tbl)` 之后做
    `this.data = LuaDataCodec.deepCopy(tbl.get("data"))` —— **深拷贝安全子集**,绝不能直接
    `= tbl.get("data")`(那只是 spec table 的引用,会跨实例/跨 spec 互染)。
  - restore:`restoreFromBundle` 用 codec 从 Bundle 重建 `this.data`,**不**碰 spec data。
- missing-script 降级路径(registry 无 table → `active=false`)仍正常,data 从 Bundle 独立恢复。

### 安全子集 + codec
新增 package-private 工具类 `LuaDataCodec`,三个静态入口:
- `static LuaValue deepCopy(LuaValue v)` — create 时隔离用。递归拷贝安全子集到全新 LuaTable 树,
  number 保留 int/double(无损);遇到 table 用 identity `visited` 集合 **防环**、超 `MAX_DEPTH`
  (取 32) **skip+warn**;非安全类型 / 非 string key skip + `Gdx.app.error`;**不**抛异常。
- `static Bundle encode(LuaValue v)` / `static LuaValue decode(Bundle env)` — Bundle 持久化用。

**编码方案:per-value envelope(自描述、可逆、无类型歧义、无保留键冲突)。**
- 任意一个 LuaValue(含 **root scalar**)编码成一个 envelope Bundle,恰好两个内部键:
  - `__t`:type code —— `n`(nil)/ `b`(bool)/ `s`(string)/ `i`(int→long)/ `f`(double→float)/ `t`(table)
  - `__v`:载荷 —— 标量按对应类型 put(bool/String/long/float);table 则是一个 **table bundle**;
    nil 无 `__v`。
- table bundle 的每个 **用户 string 键** k → 子 envelope Bundle(递归 `encode`)。非 string 键 skip+warn。
- 用户键永不与 `__t`/`__v` 冲突 —— 这两个内部键只活在 envelope Bundle 里,而用户键活在 table bundle 里,
  分属不同 Bundle,因此 **彻底消除** "保留键被用户占用" 的边角问题(不再需要 `__lua_types` 平行表)。
- 编码带 **防环**(identity `Set<LuaTable>` 已访问即 skip+warn)+ **深度上限**(>MAX_DEPTH skip+warn)。
  解码侧 Bundles 天然无环,仍加深度保护作防御。
- **不支持** `function`/`userdata`/`thread` → skip + `Gdx.app.error`,**不**抛异常。

### onActivate 升级到 3 参 + 旧脚本兼容
- `LuaItemCallbacks.callOpt(tbl, "onActivate", pos, charId, data)`(`callOpt` 已是 varargs)。
- 旧 2 参 Lua 函数:luaj 多余实参自动忽略,不报错 → 旧 trap 脚本零改动。加测试覆盖。
- `data` 为 nil(旧 trap 无 data 字段)时第三参传 `NIL`,行为等价于今天。

## Files
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaTrap.java` —
  新增实例字段 `data`(`LuaValue`,默认 NIL)、create 时 `deepCopy` 隔离、
  `storeInBundle`/`restoreFromBundle` 经 codec 持久化(独立 key `lua_trap_data`,不污染
  `lua_trap_id`)、`activate()` 第三参透传。`hydrate(tbl)` 收敛为 definitional-only。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaDataCodec.java` —
  **新增**。package-private。`deepCopy` / `encode` / `decode` 三入口;per-value envelope 编码;
  防环 + 深度上限;非安全类型/非 string key skip+error log。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaTrapRegistry.java` —
  **不改**(spec table 已含 data;`getTable(id).get("data")` 即可)。
- `core/src/main/assets/mods/test_mod/scripts/traps/demo_data_trap.lua` —
  **新增**。最小示例:`data={ message=..., damage=..., loud=... }`,`onActivate(cell,charId,data)`
  里用 `RPD.GLog` 读 `data.message`。不实现 SpecialBeacon 全量。
- 测试:
  - 扩展 `core/src/test/.../modding/LuaTrapRegistryTest.java` —
    (a) data store→restore→onActivate 收到第三参 table;
    (b) **两实例隔离**:改实例 A 的 data 不影响实例 B 也不污染 registry spec;
    (c) 旧 2 参函数收到 3 参不破;(d) nil data 不破。
  - 新增 `core/src/test/.../modding/LuaDataCodecTest.java` —
    root scalar(nil/bool/string/int/float)、table、嵌套 table 往返;不支持类型 skip;
    **自引用环 skip**;**超深 skip**;deepCopy 与原 spec 不共享 table。
- `docs/PLAN-m19a-trap-data-api.md` — 本文件。

## Steps
1. 读 `LuaTrap.java`/`LuaTrapRegistry.java`/`LuaEngine.RegisterTrapFunction`/`LuaItemCallbacks.callOpt`/
   现有 trap 测试与脚本,确认 spec 持有 data、registry 无需改、callOpt 是 varargs。(已完成,见 Design)
2. 写 `LuaDataCodec`:
   - `deepCopy(v, visited, depth)` 递归;identity 防环、MAX_DEPTH=32;number 保留 int/double;
     非 string key / 非安全类型 skip+`Gdx.app.error`。
   - `encode(v, visited, depth)` → envelope Bundle `{__t, __v}`;table → table bundle(每用户键递归);
     nil/bool/string/int(long)/float 逐一映射;防环+深度上限。
   - `decode(env, depth)` → LuaValue;按 `__t` 分派;深度防御。
3. 改 `LuaTrap`:`hydrate` 收敛 definitional(id/name/color/shape);构造器
   `this.data = LuaDataCodec.deepCopy(tbl.get("data"))`;`LUA_TRAP_DATA` key + store/restore 用
   `encode`/`decode`;`activate` 传第三参 `data`(默认 NIL)。
4. 加示例脚本 `demo_data_trap.lua`(镜像 `demo_trap.lua` 结构,加 data + 第三参)。
5. 写测试(见 Files 测试节的两份文件清单)。
6. `./gradlew :core:test` 绿(已知 `GeneratorLuaItemTest.luaItemProbabilityPersistsAcrossFullReset`
   是 flaky 概率测试,单独失败重跑即可,不计为本 feature 回归)。
7. reviewer:Phase 1 评审 PLAN;Phase 2 review 实施(各 ≤3 轮)。assign 失败/静默按协议跳过并回报。

## Acceptance
- [ ] `register_trap{ id=..., data=... }` 的 data 能持久化(Bundle round-trip)并传入
      `onActivate(cell,charId,data)`。
- [ ] create 时 data 经 deepCopy 隔离:实例间不共享、不污染 registry spec(有测试)。
- [ ] codec 防环 + 深度上限:自引用/超深 data 不崩溃,skip+warn(有测试)。
- [ ] root scalar(data 为 string/number/bool/nil)可逆往返(有测试)。
- [ ] 旧 trap 脚本(2 参 onActivate / 无 data)零改动不破。
- [ ] 不碰 LuaMob/DataDrivenLevel;不提交 `.claude/`;不用 `git add -A`(按文件名 stage)。

## Pending Issues
(none yet)
